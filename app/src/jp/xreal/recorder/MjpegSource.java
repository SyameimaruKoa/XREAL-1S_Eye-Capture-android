package jp.xreal.recorder;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** UVC MJPEG bulk receiver for the measured 1080p 30/60 fps modes. */
final class MjpegSource implements VideoSource {
    private static final int MAX_PAYLOAD = 262144;
    private static final int MAX_FRAME = 4147200;
    private final UsbManager manager;
    private final UsbDevice device;
    private final int fps;
    private UsbDeviceConnection connection;
    private UsbInterface iface;
    private UsbEndpoint endpoint;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean failed;
    private volatile String error = "";
    private volatile int frames;

    MjpegSource(UsbManager manager, UsbDevice device, int fps) {
        if (fps != 30 && fps != 60) {
            throw new IllegalArgumentException("MJPEG supports the tested 30 or 60 fps modes");
        }
        this.manager = manager;
        this.device = device;
        this.fps = fps;
    }

    @Override
    public void open() throws Exception {
        for (int i = 0; i < device.getInterfaceCount() && iface == null; i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() != 14 || candidate.getInterfaceSubclass() != 2) {
                continue;
            }
            for (int j = 0; j < candidate.getEndpointCount(); j++) {
                UsbEndpoint ep = candidate.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    iface = candidate;
                    endpoint = ep;
                    break;
                }
            }
        }
        if (iface == null) {
            throw new IllegalStateException("UVC bulk interface not found");
        }
        connection = manager.openDevice(device);
        if (connection == null || !connection.claimInterface(iface, true)) {
            throw new IllegalStateException("Cannot claim UVC interface");
        }
        connection.setInterface(iface);
        byte[] probe = probe(34);
        int result = connection.controlTransfer(0x21, 1, 0x0100, iface.getId(),
                probe, probe.length, 2000);
        if (result < 0) {
            probe = probe(26);
            result = connection.controlTransfer(0x21, 1, 0x0100, iface.getId(),
                    probe, probe.length, 2000);
        }
        if (result < 0) {
            throw new IllegalStateException("MJPEG UVC probe failed");
        }
        byte[] current = new byte[probe.length];
        int received = connection.controlTransfer(0xa1, 0x81, 0x0100, iface.getId(),
                current, current.length, 2000);
        int interval = 10_000_000 / fps;
        if (received < 26 || (current[2] & 255) != 2
                || (current[3] & 255) != (fps == 60 ? 1 : 2)
                || ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN).getInt(4) != interval) {
            throw new IllegalStateException("Camera did not accept requested MJPEG mode");
        }
        System.arraycopy(current, 0, probe, 0, Math.min(received, probe.length));
        if (connection.controlTransfer(0x21, 1, 0x0200, iface.getId(),
                probe, probe.length, 2000) < 0) {
            throw new IllegalStateException("MJPEG UVC commit failed");
        }
    }

    private byte[] probe(int length) {
        ByteBuffer data = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        data.putShort((short) 1);
        data.put((byte) 2);
        data.put((byte) (fps == 60 ? 1 : 2));
        data.putInt(10_000_000 / fps);
        data.position(18);
        data.putInt(MAX_FRAME);
        data.putInt(MAX_PAYLOAD);
        if (length >= 34) {
            data.putInt(1_000_000);
        }
        return data.array();
    }

    @Override
    public void start(UvcSource.Listener listener) {
        running = true;
        thread = new Thread(() -> readLoop(listener), "xreal-mjpeg");
        thread.start();
    }

    private void readLoop(UvcSource.Listener listener) {
        byte[] buffer = new byte[MAX_PAYLOAD + 64];
        ByteArrayOutputStream frame = new ByteArrayOutputStream(256 * 1024);
        boolean started = false;
        int fid = -1;
        long frameTime = 0;
        int timeouts = 0;
        try {
            listener.onCsd(new byte[0]);
            while (running) {
                int n = connection.bulkTransfer(endpoint, buffer, buffer.length, 1000);
                if (n < 0) {
                    if (++timeouts > 5) {
                        throw new IllegalStateException("MJPEG USB input timed out");
                    }
                    continue;
                }
                timeouts = 0;
                if (n < 2) {
                    continue;
                }
                int header = buffer[0] & 255;
                if (header < 2 || header > n) {
                    if (!started && frames == 0 && allZero(buffer, n)) {
                        continue;
                    }
                    throw new IllegalStateException("Invalid MJPEG UVC header");
                }
                int flags = buffer[1] & 255;
                int nextFid = flags & 1;
                if ((flags & 0x40) != 0 || (started && fid != nextFid)) {
                    throw new IllegalStateException("Incomplete MJPEG frame from camera");
                }
                if (!started) {
                    if (n - header < 2 || (buffer[header] & 255) != 0xff
                            || (buffer[header + 1] & 255) != 0xd8) {
                        continue;
                    }
                    frame.reset();
                    frameTime = SystemClock.elapsedRealtimeNanos();
                    started = true;
                }
                fid = nextFid;
                if (n - header > MAX_FRAME - frame.size()) {
                    throw new IllegalStateException("MJPEG frame exceeds maximum size");
                }
                frame.write(buffer, header, n - header);
                if ((flags & 2) != 0) {
                    byte[] jpeg = frame.toByteArray();
                    if (jpeg.length < 4 || (jpeg[jpeg.length - 2] & 255) != 0xff
                            || (jpeg[jpeg.length - 1] & 255) != 0xd9) {
                        throw new IllegalStateException("MJPEG frame is missing its end marker");
                    }
                    listener.onAccessUnit(jpeg, frameTime);
                    frames++;
                    started = false;
                }
            }
        } catch (Throwable t) {
            if (running) {
                failed = true;
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
                listener.onError(error);
            }
        }
    }

    private static boolean allZero(byte[] data, int length) {
        for (int i = 0; i < length; i++) {
            if (data[i] != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                failed = true;
                error = "MJPEG receiver did not stop";
            }
        }
        if (connection != null) {
            connection.releaseInterface(iface);
            connection.close();
            connection = null;
        }
    }

    @Override public boolean isFailed() { return failed; }
    @Override public String getError() { return error; }
    @Override public int getAccessUnits() { return frames; }
    @Override public int getWidth() { return 1920; }
    @Override public int getHeight() { return 1080; }
    @Override public int getFrameRate() { return fps; }
}
