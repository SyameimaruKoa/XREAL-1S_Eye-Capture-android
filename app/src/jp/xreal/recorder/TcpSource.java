package jp.xreal.recorder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.SystemClock;
import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Receives the previously observed XREAL Eye HEVC stream over USB Ethernet. */
final class TcpSource implements VideoSource {
    private static final String TAG = "XrealTcp";
    private static final String HOST = "169.254.2.1";
    private static final int MAX_MESSAGE = 8 * 1024 * 1024;
    private static final int FRAME_HEADER = 97;
    /** Maximum distance between the camera clock and the receive clock before re-anchoring. */
    private static final long MAX_CLOCK_DRIFT_NS = 100_000_000L;
    /** Smallest allowed step so that a stale timestamp cannot repeat a presentation time. */
    private static final long MIN_PTS_STEP_NS = 1_000_000L;
    private final Context context;
    private final ArrayBlockingQueue<Reply> replies = new ArrayBlockingQueue<>(8);
    private Socket video;
    private Socket control;
    private Thread videoThread;
    private Thread controlThread;
    private volatile boolean running;
    private volatile boolean failed;
    private volatile String error = "";
    private volatile int accessUnits;
    private boolean startSent;
    private UvcSource.Listener listener;
    private long candidateFirstNs;
    private long candidateLastNs;
    private long arrivalFirstNs;
    private long arrivalLastNs;
    private long candidateNonIncreasing;
    private long candidateMaxArrivalDeviationNs;
    private long candidateMinStepNs = Long.MAX_VALUE;
    private long candidateMaxStepNs;
    private long arrivalMinStepNs = Long.MAX_VALUE;
    private long arrivalMaxStepNs;
    private long arrivalShortSteps;
    private long arrivalLongSteps;
    private long shortAfterLong;
    private long previousArrivalStepNs;
    private long cameraPtsAnchorNs;
    private long lastPtsNs;
    private long clockReanchors;
    private long clockRejected;
    private long clockForcedSteps;

    private static final class Reply {
        final int command;
        final byte[] payload;
        Reply(int command, byte[] payload) {
            this.command = command;
            this.payload = payload;
        }
    }

    TcpSource(Context context) {
        this.context = context;
    }

    @Override
    public void open() throws Exception {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            throw new IOException("Network manager unavailable");
        }
        Network network = null;
        for (Network candidate : manager.getAllNetworks()) {
            LinkProperties properties = manager.getLinkProperties(candidate);
            if (properties == null) {
                continue;
            }
            for (LinkAddress address : properties.getLinkAddresses()) {
                InetAddress ip = address.getAddress();
                if (ip instanceof Inet4Address && ip.getHostAddress().startsWith("169.254.2.")) {
                    network = candidate;
                    break;
                }
            }
            if (network != null) {
                break;
            }
        }
        if (network == null) {
            throw new IOException("XREAL USB Ethernet is not available");
        }
        try {
            video = connect(network, 52995);
            control = connect(network, 52999);
        } catch (Exception e) {
            closeSockets();
            throw e;
        }
    }

    private Socket connect(Network network, int port) throws IOException {
        Socket socket = new Socket();
        try {
            network.bindSocket(socket);
            socket.connect(new InetSocketAddress(HOST, port), 5000);
            socket.setSoTimeout(1000);
            return socket;
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    @Override
    public void start(UvcSource.Listener listener) throws Exception {
        this.listener = listener;
        running = true;
        controlThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readControl();
            }
        }, "xreal-tcp-control");
        videoThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readVideo();
            }
        }, "xreal-tcp-video");
        controlThread.start();
        videoThread.start();
        startSent = true;
        try {
            transact(0x2781, 1);
        } catch (Exception e) {
            fail("TCP camera start failed: " + e.getMessage());
            throw e;
        }
    }

    private void readControl() {
        try {
            InputStream input = control.getInputStream();
            while (running) {
                byte[] header = readExact(input, 6, false);
                int command = ((header[0] & 0xff) << 8) | (header[1] & 0xff);
                int length = readLength(header);
                byte[] payload = readExact(input, length, false);
                if (command == 0x2781 || command == 0x2782) {
                    if (!replies.offer(new Reply(command, payload))) {
                        throw new IOException("TCP control response queue full");
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                fail("TCP control read failed: " + e.getMessage());
            }
        }
    }

    private void transact(int command, int sequence) throws Exception {
        byte[] packet = new byte[]{(byte) (command >> 8), (byte) command, 0, 0, 0, 6,
                (byte) 0x80, 0, (byte) (sequence >> 8), (byte) sequence, 0x1a, 0};
        OutputStream out = control.getOutputStream();
        out.write(packet);
        out.flush();
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (true) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                throw new IOException("TCP control acknowledgement timed out");
            }
            Reply reply = replies.poll(remaining, TimeUnit.MILLISECONDS);
            if (reply == null) {
                throw new IOException("TCP control acknowledgement timed out");
            }
            byte[] expected = new byte[]{0, 0, (byte) (sequence >> 8), (byte) sequence, 0x22, 0};
            if (reply.command == command && Arrays.equals(reply.payload, expected)) {
                return;
            }
            throw new IOException("Unexpected TCP camera acknowledgement");
        }
    }

    private void readVideo() {
        boolean csdSent = false;
        try {
            InputStream input = video.getInputStream();
            while (running) {
                byte[] header = readExact(input, 6, true);
                int command = ((header[0] & 0xff) << 8) | (header[1] & 0xff);
                byte[] body = readExact(input, readLength(header), true);
                if (command != 0x2785) {
                    throw new IOException("Unexpected TCP video message: " + command);
                }
                if (body.length < FRAME_HEADER + 5 || body[FRAME_HEADER] != 0
                        || body[FRAME_HEADER + 1] != 0 || body[FRAME_HEADER + 2] != 0
                        || body[FRAME_HEADER + 3] != 1) {
                    throw new IOException("Invalid TCP HEVC frame header");
                }
                ByteBuffer metadata = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                if (metadata.getInt(12) != getWidth() || metadata.getInt(16) != getHeight()) {
                    throw new IOException("Unexpected TCP video dimensions");
                }
                byte[] au = Arrays.copyOfRange(body, FRAME_HEADER, body.length);
                int nalType = (au[4] >> 1) & 0x3f;
                if (nalType == 32) {
                    byte[] csd = UvcSource.extractCsd(au);
                    if (!csdSent && csd != null) {
                        listener.onCsd(csd);
                        csdSent = true;
                    }
                    continue;
                }
                if (nalType >= 32) {
                    throw new IOException("Unexpected TCP HEVC NAL type: " + nalType);
                }
                if (!csdSent) {
                    Log.w(TAG, "Skipping video until HEVC configuration arrives");
                    continue;
                }
                long arrivalNs = SystemClock.elapsedRealtimeNanos();
                long candidateNs = metadata.getLong(24);
                long ptsNs;
                if (accessUnits == 0) {
                    candidateFirstNs = candidateNs;
                    arrivalFirstNs = arrivalNs;
                    cameraPtsAnchorNs = arrivalNs;
                    ptsNs = arrivalNs;
                } else {
                    long candidateStep = candidateNs - candidateLastNs;
                    long arrivalStep = arrivalNs - arrivalLastNs;
                    candidateMinStepNs = Math.min(candidateMinStepNs, candidateStep);
                    candidateMaxStepNs = Math.max(candidateMaxStepNs, candidateStep);
                    arrivalMinStepNs = Math.min(arrivalMinStepNs, arrivalStep);
                    arrivalMaxStepNs = Math.max(arrivalMaxStepNs, arrivalStep);
                    if (arrivalStep < 20_000_000L) {
                        arrivalShortSteps++;
                        if (previousArrivalStepNs > 45_000_000L) {
                            shortAfterLong++;
                        }
                    }
                    if (arrivalStep > 45_000_000L) {
                        arrivalLongSteps++;
                    }
                    previousArrivalStepNs = arrivalStep;
                    if (candidateNs <= candidateLastNs) {
                        candidateNonIncreasing++;
                    }
                    long deviation = Math.abs((arrivalNs - arrivalFirstNs)
                            - (candidateNs - candidateFirstNs));
                    candidateMaxArrivalDeviationNs = Math.max(candidateMaxArrivalDeviationNs, deviation);
                    ptsNs = presentationNs(candidateNs, arrivalNs);
                }
                candidateLastNs = candidateNs;
                arrivalLastNs = arrivalNs;
                listener.onAccessUnit(au, ptsNs);
                accessUnits++;
            }
        } catch (Exception e) {
            if (running) {
                fail("TCP video read failed: " + e.getMessage());
            }
        }
    }

    /**
     * Converts the camera timestamp at frame header offset 24 into a presentation time on the
     * same clock as the audio track. In every measured run the camera timestamp advanced by
     * 33.42 to 33.58 ms per frame while the receive time jittered from 2.9 to 63.5 ms, so the
     * camera clock is used for frame timing and anchored to the first frame's receive time. If
     * the two clocks ever separate by more than {@link #MAX_CLOCK_DRIFT_NS} the anchor moves so
     * that audio stays in sync instead of accumulating drift.
     */
    private long presentationNs(long candidateNs, long arrivalNs) {
        if (candidateNs <= candidateLastNs) {
            clockRejected++;
            return monotonic(arrivalNs);
        }
        long drift = cameraPtsAnchorNs + (candidateNs - candidateFirstNs) - arrivalNs;
        if (drift > MAX_CLOCK_DRIFT_NS || drift < -MAX_CLOCK_DRIFT_NS) {
            cameraPtsAnchorNs -= drift;
            clockReanchors++;
        }
        return monotonic(cameraPtsAnchorNs + (candidateNs - candidateFirstNs));
    }

    private long monotonic(long ptsNs) {
        long result = ptsNs;
        if (result <= lastPtsNs) {
            result = lastPtsNs + MIN_PTS_STEP_NS;
            clockForcedSteps++;
        }
        lastPtsNs = result;
        return result;
    }

    private int readLength(byte[] header) throws IOException {
        long length = ((long) (header[2] & 0xff) << 24) | ((long) (header[3] & 0xff) << 16)
                | ((long) (header[4] & 0xff) << 8) | (header[5] & 0xff);
        if (length < 0 || length > MAX_MESSAGE) {
            throw new IOException("TCP message length out of range: " + length);
        }
        return (int) length;
    }

    private byte[] readExact(InputStream input, int length, boolean requireVideoProgress) throws IOException {
        return TcpReadProgress.read(input, length, requireVideoProgress ? 5000 : 0,
                () -> running);
    }

    private void fail(String message) {
        if (!failed) {
            failed = true;
            error = message;
            if (listener != null) {
                listener.onError(message);
            }
        }
    }

    @Override
    public void stop() {
        if (startSent && control != null && !control.isClosed() && running) {
            try {
                transact(0x2782, 2);
            } catch (Exception e) {
                fail("TCP camera stop failed: " + e.getMessage());
            }
            startSent = false;
        }
        running = false;
        closeSockets();
        join(videoThread);
        join(controlThread);
        if (accessUnits > 1) {
            Log.i(TAG, "camera clock frames=" + accessUnits
                    + " candidateSpanNs=" + (candidateLastNs - candidateFirstNs)
                    + " arrivalSpanNs=" + (arrivalLastNs - arrivalFirstNs)
                    + " nonIncreasing=" + candidateNonIncreasing
                    + " maxArrivalDeviationNs=" + candidateMaxArrivalDeviationNs
                    + " candidateStepRangeNs=" + candidateMinStepNs + ":" + candidateMaxStepNs
                    + " arrivalStepRangeNs=" + arrivalMinStepNs + ":" + arrivalMaxStepNs
                    + " arrivalUnder20ms=" + arrivalShortSteps
                    + " arrivalOver45ms=" + arrivalLongSteps
                    + " shortAfterLong=" + shortAfterLong
                    + " clockReanchors=" + clockReanchors
                    + " clockRejected=" + clockRejected
                    + " clockForcedSteps=" + clockForcedSteps);
        }
    }

    private void closeSockets() {
        try { if (video != null) video.close(); } catch (IOException ignored) { }
        try { if (control != null) control.close(); } catch (IOException ignored) { }
    }

    private void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(2000);
            if (thread.isAlive()) {
                fail("TCP receiver did not stop within 2 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while stopping TCP receiver");
        }
    }

    @Override public boolean isFailed() { return failed; }
    @Override public String getError() { return error; }
    @Override public int getAccessUnits() { return accessUnits; }
    @Override public int getWidth() { return 1280; }
    @Override public int getHeight() { return 720; }
    @Override public int getFrameRate() { return 30; }
}
