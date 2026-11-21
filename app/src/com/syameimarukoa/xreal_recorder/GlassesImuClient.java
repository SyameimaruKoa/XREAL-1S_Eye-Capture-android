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
    private static final int MAX_ROWS = 200000;

    private final List<String> rows = new ArrayList<>();
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

    private GlassesImuClient(Context context, boolean collectSamples) {
        this.context = context;
        this.collectSamples = collectSamples;
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
        synchronized (rows) {
            if (sample.reportType == ImuReportParser.REPORT_MAGNETOMETER) {
                magnetometerFrames++;
                System.arraycopy(sample.magnetometer, 0, lastMagnetometer, 0, 3);
                return;
            }
            if (Float.isFinite(sample.temperature)) {
                lastTemperature = sample.temperature;
            }
            if (!collectSamples || rows.size() >= MAX_ROWS
                    || !allFinite(sample.gyro) || !allFinite(sample.accel)) {
                return;
            }
            if (firstTimestampNs < 0) {
                firstTimestampNs = sample.timestampNs;
            }
            long timeMs = (sample.timestampNs - firstTimestampNs) / 1_000_000L;
            // Store raw sensor values unchanged (only rad/s -> deg/s unit conversion
            // in GyroflowProto). Axis/roll correction is left to Gyroflow via the
            // embedded orientation and imu_rotation metadata — no double negation.
            rows.add(GyroCsvFormatter.row(timeMs, sample.gyro, sample.accel));
            samples.add(new GyroflowProto.ImuSample(sample.timestampNs / 1000.0,
                    sample.gyro[0], sample.gyro[1], sample.gyro[2],
                    sample.accel[0], sample.accel[1], sample.accel[2]));
            imuFrames++;
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
        synchronized (rows) {
            return GyroCsvFormatter.format(videoFilename, captureTimeMs, new ArrayList<>(rows));
        }
    }

    int getSampleCount() {
        synchronized (rows) {
            return imuFrames;
        }
    }

    Float getTemperatureC() {
        synchronized (rows) {
            return Float.isFinite(lastTemperature) ? lastTemperature : null;
        }
    }

    List<GyroflowProto.ImuSample> getSamples() {
        synchronized (rows) {
            return new ArrayList<>(samples);
        }
    }


    long getFirstTimestampNs() {
        synchronized (rows) {
            return samples.isEmpty() ? 0L : (long) (samples.get(0).timeUs * 1000.0);
        }
    }

    String getSummary() {
        synchronized (rows) {
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
