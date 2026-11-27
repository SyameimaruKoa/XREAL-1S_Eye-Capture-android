package com.syameimarukoa.xreal_recorder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.SystemClock;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/** Reads the XREAL 1S IMU stream (TCP 52998) for Gyroflow post-processing. */
final class GlassesImuClient {
    private static final String HOST = "169.254.2.1";
    private static final int PORT = 52998;
    private static final int HEADER_SIZE = 6;
    private final List<GyroflowProto.ImuSample> samples = new ArrayList<>();
    private volatile boolean running;
    private Thread thread;
    private Socket socket;
    private long firstTimestampNs = -1L;
    private int imuFrames;
    private int magnetometerFrames;
    private float lastTemperature = Float.NaN;
    private final float[] lastMagnetometer = new float[3];

    private final boolean collectSamples;
    private final Context context;

    interface ImuSink {
        void onSample(long timeMs, float[] gyro, float[] accel);
        void close();
    }

    private final java.util.List<ImuSink> sinks = new ArrayList<>();

    private GlassesImuClient(Context context, boolean collectSamples) {
        this.context = context;
        this.collectSamples = collectSamples;
    }

    void setSink(ImuSink sink) {
        addSink(sink);
    }

    void addSink(ImuSink sink) {
        synchronized (samples) {
            sinks.add(sink);
        }
    }

    static GlassesImuClient start(Context context) {
        return start(context, true);
    }

    static GlassesImuClient start(Context context, boolean collectSamples) {
        GlassesImuClient client = new GlassesImuClient(context, collectSamples);
        // Do not fail when the IMU socket is not available yet. readLoop() keeps
        // retrying so samples are collected once the glasses come online.
        client.thread = new Thread(client::readLoop, "xreal-imu");
        client.thread.setDaemon(true);
        client.thread.start();
        return client;
    }

    private boolean open(Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        Network network = null;
        for (Network candidate : manager.getAllNetworks()) {
            LinkProperties properties = manager.getLinkProperties(candidate);
            if (properties == null) {
                continue;
            }
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address
                        && "169.254.2.10".equals(address.getAddress().getHostAddress())) {
                    network = candidate;
                    break;
                }
            }
            if (network != null) {
                break;
            }
        }
        if (network == null) {
            return false;
        }
        try {
            socket = new Socket();
            network.bindSocket(socket);
            socket.connect(new InetSocketAddress(HOST, PORT), 5000);
            socket.setSoTimeout(1000);
            return true;
        } catch (IOException e) {
            closeSocket();
            return false;
        }
    }

    private void readLoop() {
        running = true;
        byte[] header = new byte[HEADER_SIZE];
        byte[] body = new byte[ImuReportParser.BODY_SIZE];
        while (running) {
            if (socket == null && !reconnect()) {
                return;
            }
            try {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                while (running) {
                    input.readFully(header);
                    if (!ImuReportParser.isHeader(header)) {
                        continue;
                    }
                    input.readFully(body);
                    parse(body);
                }
            } catch (IOException ignored) {
            }
            if (!running) {
                break;
            }
            // The IMU stream is cut when USB is replugged. Retry until it comes back
            // so motion data keeps being logged for the whole recording.
            closeSocket();
            if (!reconnect()) {
                return;
            }
        }
    }

    private boolean reconnect() {
        long deadline = SystemClock.elapsedRealtime() + 60000;
        while (running && SystemClock.elapsedRealtime() < deadline) {
            if (open(context)) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void parse(byte[] body) {
        ImuReportParser.Sample sample = ImuReportParser.parse(body);
        if (sample == null) {
            return;
        }
        synchronized (samples) {
            if (sample.reportType == ImuReportParser.REPORT_MAGNETOMETER) {
                magnetometerFrames++;
                System.arraycopy(sample.magnetometer, 0, lastMagnetometer, 0, 3);
                return;
            }
            if (Float.isFinite(sample.temperature)) {
                lastTemperature = sample.temperature;
            }
            if (!collectSamples || !allFinite(sample.gyro) || !allFinite(sample.accel)) {
                return;
            }
            if (firstTimestampNs < 0) {
                firstTimestampNs = sample.timestampNs;
            }
            // Store raw sensor values unchanged (only rad/s -> deg/s unit conversion
            // in GyroflowProto). Axis/roll correction is left to Gyroflow via the
            // embedded orientation and imu_rotation metadata — no double negation.
            samples.add(new GyroflowProto.ImuSample(sample.timestampNs / 1000.0,
                    sample.gyro[0], sample.gyro[1], sample.gyro[2],
                    sample.accel[0], sample.accel[1], sample.accel[2]));
            imuFrames++;
            for (ImuSink s : sinks) {
                try {
                    s.onSample((sample.timestampNs - firstTimestampNs) / 1_000_000L,
                            sample.gyro, sample.accel);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean allFinite(float[] values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    String formatCsv(String videoFilename, long captureTimeMs) {
        synchronized (samples) {
            // Build CSV rows only for the fallback path; keeping them for every
            // sample would double memory use for no benefit on a successful embed.
            List<String> csvRows = new ArrayList<>(samples.size());
            long firstUs = samples.isEmpty() ? 0L : (long) samples.get(0).timeUs;
            for (GyroflowProto.ImuSample s : samples) {
                csvRows.add(GyroCsvFormatter.row((long) s.timeUs - firstUs,
                        new float[]{s.gyroX, s.gyroY, s.gyroZ},
                        new float[]{s.accelX, s.accelY, s.accelZ}));
            }
            return GyroCsvFormatter.format(videoFilename, captureTimeMs, csvRows);
        }
    }

    int getSampleCount() {
        synchronized (samples) {
            return imuFrames;
        }
    }

    Float getTemperatureC() {
        synchronized (samples) {
            return Float.isFinite(lastTemperature) ? lastTemperature : null;
        }
    }

    List<GyroflowProto.ImuSample> getSamples() {
        synchronized (samples) {
            return new ArrayList<>(samples);
        }
    }


    long getFirstTimestampNs() {
        synchronized (samples) {
            return samples.isEmpty() ? 0L : (long) (samples.get(0).timeUs * 1000.0);
        }
    }

    String getSummary() {
        synchronized (samples) {
            String temperature = Float.isFinite(lastTemperature)
                    ? Float.toString(lastTemperature) : "null";
            return "{\"source\":\"xreal_1s_glasses_imu\",\"imu_frames\":" + imuFrames
                    + ",\"magnetometer_frames\":" + magnetometerFrames
                    + ",\"temperature_c\":" + temperature
                    + ",\"magnetometer\":[" + lastMagnetometer[0] + ","
                    + lastMagnetometer[1] + "," + lastMagnetometer[2] + "]}";
        }
    }

    void stop() {
        running = false;
        closeSocket();
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
    }
}
