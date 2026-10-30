package jp.xreal.recorder;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** UVC bulk HEVC receiver for the XREAL 1S (IF10 alt 0, EP 0x89, 2048x1512). */
public final class UvcSource implements VideoSource {
    private static final String TAG = "XrealUvc";
    // The UVC probe advertises dwMaxPayloadTransferSize=262144. A single UVC
    // payload can therefore be larger than 64 KiB. bulkTransfer() must be given
    // a buffer at least that large: otherwise the payload is split, the second
    // part is mistaken for a new payload header, and the tail of a keyframe is
    // dropped (the bottom CTU row then shows decoder concealment artifacts).
    private static final int UVC_MAX_PAYLOAD = 262144;
    private static final int READ_BUF = UVC_MAX_PAYLOAD + 64;

    public interface Listener {
        void onCsd(byte[] csd);

        void onAccessUnit(byte[] annexB, long timestampNs);

        void onError(String message);
    }

    public static final class RawWriteException extends RuntimeException {
        RawWriteException(Throwable cause) {
            super("RAW HEVC write failed: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage(), cause);
        }
    }

    private static final class Nal {
        final int start;
        final int end;
        final int type;

        Nal(int start, int end, int type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }
    }

    private final UsbManager usbManager;
    private final UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface iface;
    private UsbEndpoint endpoint;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean failed;
    private volatile String error = "";
    private volatile int accessUnits;
    private byte[] previousAccessUnit;

    public UvcSource(UsbManager usbManager, UsbDevice device) {
        this.usbManager = usbManager;
        this.device = device;
    }

    public void open() throws Exception {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface itf = device.getInterface(i);
            if (itf.getInterfaceClass() != 14 || itf.getInterfaceSubclass() != 2) {
                continue;
            }
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                UsbEndpoint ep = itf.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    iface = itf;
                    endpoint = ep;
                    break;
                }
            }
            if (iface != null) {
                break;
            }
        }
        if (iface == null || endpoint == null) {
            throw new IllegalStateException("UVC bulk IN interface not found");
        }

        connection = usbManager.openDevice(device);
        if (connection == null) {
            throw new IllegalStateException("UsbManager.openDevice returned null");
        }
        if (!connection.claimInterface(iface, true)) {
            throw new IllegalStateException("claimInterface(IF" + iface.getId() + ") failed");
        }
        // 1S UVC VS is IF10 alt 0. The endpoint lives on alt 0.
        connection.setInterface(iface);

        byte[] probe = buildProbe(1, 1, 60, 5 * 1024 * 1024, 262144, 34);
        int r = connection.controlTransfer(0x21, 0x01, 0x0100, iface.getId(),
                probe, probe.length, 2000);
        if (r < 0) {
            probe = buildProbe(1, 1, 60, 5 * 1024 * 1024, 262144, 26);
            r = connection.controlTransfer(0x21, 0x01, 0x0100, iface.getId(),
                    probe, probe.length, 2000);
        }
        if (r < 0) {
            throw new IllegalStateException("UVC SET_CUR PROBE failed");
        }

        byte[] current = new byte[probe.length];
        int n = connection.controlTransfer(0xA1, 0x81, 0x0100, iface.getId(),
                current, current.length, 2000);
        if (n >= 26) {
            ByteBuffer negotiated = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN);
            Log.i(TAG, "UVC probe GET_CUR bytes=" + n
                    + " format=" + (current[2] & 0xff)
                    + " frame=" + (current[3] & 0xff)
                    + " interval=" + (negotiated.getInt(4) & 0xffffffffL)
                    + " compressionQuality=" + (negotiated.getShort(12) & 0xffff)
                    + " maxFrameBytes=" + (negotiated.getInt(18) & 0xffffffffL)
                    + " maxPayloadBytes=" + (negotiated.getInt(22) & 0xffffffffL));
            int copy = Math.min(n, probe.length);
            System.arraycopy(current, 0, probe, 0, copy);
        }
        r = connection.controlTransfer(0x21, 0x01, 0x0200, iface.getId(),
                probe, probe.length, 2000);
        if (r < 0) {
            throw new IllegalStateException("UVC SET_CUR COMMIT failed");
        }
    }

    public void start(final Listener listener) {
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(listener);
            }
        }, "xreal-uvc");
        thread.start();
    }

    private void readLoop(Listener listener) {
        byte[] buf = new byte[READ_BUF];
        ByteArrayOutputStream frame = new ByteArrayOutputStream(8 * 1024 * 1024);
        boolean haveFrame = false;
        boolean csdSent = false;
        int lastFid = -1;
        int lastEofFid = -1;
        long frameStartNs = 0;
        int timeouts = 0;
        long lastReadEndNs = 0;
        int shortReads = 0;
        int invalidHeaders = 0;
        int missingStartCodes = 0;
        long readSequence = 0;

        try {
            while (running) {
                long readStartNs = SystemClock.elapsedRealtimeNanos();
                int n = connection.bulkTransfer(endpoint, buf, buf.length, 1000);
                long sequence = ++readSequence;
                long readEndNs = SystemClock.elapsedRealtimeNanos();
                long readMs = (readEndNs - readStartNs) / 1_000_000L;
                long processingMs = lastReadEndNs == 0 ? 0
                        : (readStartNs - lastReadEndNs) / 1_000_000L;
                lastReadEndNs = readEndNs;
                if (readMs >= 100 || processingMs >= 100) {
                    Log.w(TAG, "bulk timing readMs=" + readMs + " processingMs="
                            + processingMs + " bytes=" + n + " readSeq=" + sequence);
                }
                if (n < 0) {
                    timeouts++;
                    if (timeouts == 1 || timeouts % 100 == 0) {
                        Log.w(TAG, "bulk read returned " + n + ", consecutive=" + timeouts
                                + " readSeq=" + sequence);
                    }
                    if (timeouts > 100) {
                        throw new IllegalStateException("bulkTransfer timed out repeatedly");
                    }
                    continue;
                }
                timeouts = 0;
                if (n < 2) {
                    shortReads++;
                    if (shortReads <= 3 || shortReads % 100 == 0) {
                        Log.w(TAG, "short payload bytes=" + n + " count=" + shortReads
                                + " readSeq=" + sequence);
                    }
                    continue;
                }

                // bHeaderLength is a full byte in UVC 1.x. Masking with 0x3f
                // truncates legal header lengths above 63.
                int hlen = buf[0] & 0xff;
                if (hlen < 2 || hlen > n) {
                    invalidHeaders++;
                    if (invalidHeaders <= 3 || invalidHeaders % 100 == 0) {
                        Log.w(TAG, "invalid payload header length=" + hlen + " bytes="
                                + n + " count=" + invalidHeaders + " readSeq=" + sequence);
                    }
                    continue;
                }
                int flags = buf[1] & 0xff;
                int fid = flags & 1;
                boolean eof = (flags & 2) != 0;
                boolean err = (flags & 0x40) != 0;
                if (n > 65536 || eof) {
                    Log.i(TAG, "payload n=" + n + " hlen=" + hlen + " fid=" + fid
                            + " eof=" + eof + " existing=" + frame.size()
                            + " readSeq=" + sequence);
                }
                if (err) {
                    Log.w(TAG, "UVC error flag fid=" + fid + " bytes=" + n
                            + " discardedFrameBytes=" + frame.size() + " readSeq=" + sequence);
                    frame.reset();
                    haveFrame = false;
                    lastFid = fid;
                    continue;
                }

                if (haveFrame && fid != lastFid) {
                    Log.w(TAG, "FID changed before EOF: previous=" + lastFid
                            + " current=" + fid + " frameBytes=" + frame.size()
                            + " readSeq=" + sequence);
                    csdSent = emitFrame(frame, frameStartNs, csdSent, listener);
                    haveFrame = false;
                }

                if (!haveFrame) {
                    int off = findStartCode(buf, hlen, n - hlen);
                    if (off < 0) {
                        missingStartCodes++;
                        if (missingStartCodes <= 3 || missingStartCodes % 100 == 0) {
                            Log.i(TAG, "payload skipped without HEVC start code: readSeq="
                                    + sequence + " bytes=" + (n - hlen) + " count="
                                    + missingStartCodes);
                        }
                        lastFid = fid;
                        continue;
                    }
                    int payloadStart = hlen + off;
                    int payloadLength = n - payloadStart;
                    if (lastEofFid == fid) {
                        Log.w(TAG, "new frame has same FID as previous EOF: fid=" + fid
                                + " auIndex=" + accessUnits + " payloadBytes=" + payloadLength
                                + " readSeq=" + sequence);
                    }
                    if (hasLongCommonPrefix(previousAccessUnit, buf, payloadStart, payloadLength)) {
                        Log.w(TAG, "raw USB payload matches previous AU prefix: previousBytes="
                                + previousAccessUnit.length + " payloadBytes=" + payloadLength
                                + " fid=" + fid + " eof=" + eof + " auIndex=" + accessUnits
                                + " readSeq=" + sequence);
                    }
                    haveFrame = true;
                    frameStartNs = SystemClock.elapsedRealtimeNanos();
                    frame.reset();
                    frame.write(buf, hlen + off, n - hlen - off);
                } else if (n > hlen) {
                    frame.write(buf, hlen, n - hlen);
                }
                lastFid = fid;

                if (eof) {
                    csdSent = emitFrame(frame, frameStartNs, csdSent, listener);
                    lastEofFid = fid;
                    haveFrame = false;
                }
            }
        } catch (Throwable t) {
            if (running || t instanceof RawWriteException) {
                failed = true;
                error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
                listener.onError(error);
            }
        }
    }

    private boolean emitFrame(ByteArrayOutputStream frame, long frameStartNs,
                              boolean csdSent, Listener listener) throws Exception {
        if (frame.size() <= 0) {
            frame.reset();
            return csdSent;
        }
        byte[] au = frame.toByteArray();
        frame.reset();
        if (au.length < 4) {
            return csdSent;
        }
        if (hasLongCommonPrefix(previousAccessUnit, au)) {
            Log.w(TAG, "consecutive access units have a complete common prefix: previousBytes="
                    + previousAccessUnit.length + " currentBytes=" + au.length
                    + " auIndex=" + accessUnits);
        }
        previousAccessUnit = au;
        if (!csdSent) {
            byte[] csd = extractCsd(au);
            if (csd != null) {
                listener.onCsd(csd);
                csdSent = true;
            }
        }
        if (au.length > 60000 || isKeyFrame(au)) {
            Log.i(TAG, "AU bytes=" + au.length + " key=" + isKeyFrame(au)
                    + " first=" + String.format("%02x %02x %02x %02x",
                    au[0], au[1], au[2], au[3]));
        }
        listener.onAccessUnit(au, frameStartNs);
        accessUnits++;
        return csdSent;
    }

    /** Diagnostic only: a common prefix is suspicious, not proof of corruption. */
    static boolean hasLongCommonPrefix(byte[] previous, byte[] current) {
        return current != null && hasLongCommonPrefix(previous, current, 0, current.length);
    }

    private static boolean hasLongCommonPrefix(byte[] previous, byte[] current,
                                               int offset, int length) {
        if (previous == null || current == null) {
            return false;
        }
        if (offset < 0 || length < 0 || offset > current.length - length) {
            return false;
        }
        int shorter = Math.min(previous.length, length);
        if (shorter < 512) {
            return false;
        }
        for (int i = 0; i < shorter; i++) {
            if (previous[i] != current[offset + i]) {
                return false;
            }
        }
        return true;
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive() && !failed) {
                error = "UVC receiver did not stop within 2 seconds";
                failed = true;
            }
        }
        if (connection != null) {
            try {
                connection.releaseInterface(iface);
            } catch (Throwable ignored) {
            }
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
            connection = null;
        }
    }

    public boolean isFailed() {
        return failed;
    }

    public String getError() {
        return error;
    }

    public int getAccessUnits() {
        return accessUnits;
    }

    public int getWidth() {
        return 2048;
    }

    public int getHeight() {
        return 1512;
    }

    public int getFrameRate() {
        return 60;
    }

    private static byte[] buildProbe(int formatIndex, int frameIndex, int fps,
                                     int maxFrame, int maxPayload, int size) {
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 1);                  // bmHint: dwFrameInterval
        b.put((byte) formatIndex);
        b.put((byte) frameIndex);
        b.putInt(10_000_000 / fps);             // dwFrameInterval
        b.putShort((short) 0);                  // wKeyFrameRate
        b.putShort((short) 0);                  // wPFrameRate
        b.putShort((short) 0);                  // wCompQuality
        b.putShort((short) 0);                  // wCompWindowSize
        b.putShort((short) 0);                  // wDelay
        b.putInt(maxFrame);                     // dwMaxVideoFrameSize
        b.putInt(maxPayload);                   // dwMaxPayloadTransferSize
        if (size >= 34) {
            b.putInt(1_000_000);                // dwClockFrequency
            b.put((byte) 0);                    // bmFramingInfo
            b.put((byte) 0);                    // bPreferedVersion
            b.put((byte) 0);                    // bMinVersion
            b.put((byte) 0);                    // bMaxVersion
        }
        return b.array();
    }

    static byte[] extractCsd(byte[] au) {
        List<Nal> nals = splitNals(au);
        byte[] vps = null;
        byte[] sps = null;
        byte[] pps = null;
        for (Nal nal : nals) {
            if (nal.type == 32) {
                vps = copy(au, nal.start, nal.end);
            } else if (nal.type == 33) {
                sps = copy(au, nal.start, nal.end);
            } else if (nal.type == 34) {
                pps = copy(au, nal.start, nal.end);
            }
        }
        if (vps == null || sps == null || pps == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(vps.length + sps.length + pps.length + 12);
        try {
            out.write(new byte[]{0, 0, 0, 1});
            out.write(vps);
            out.write(new byte[]{0, 0, 0, 1});
            out.write(sps);
            out.write(new byte[]{0, 0, 0, 1});
            out.write(pps);
        } catch (Exception e) {
            return null;
        }
        return out.toByteArray();
    }

    public static boolean isKeyFrame(byte[] au) {
        for (Nal nal : splitNals(au)) {
            if (nal.type >= 16 && nal.type <= 21) {
                return true;
            }
        }
        return false;
    }

    private static List<Nal> splitNals(byte[] data) {
        ArrayList<Nal> list = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom + 3 < data.length) {
            int rel = findStartCode(data, searchFrom, data.length - searchFrom);
            if (rel < 0) {
                break;
            }
            int sc = searchFrom + rel;
            int scLen = data[sc + 2] == 1 ? 3 : 4;
            int nalStart = sc + scLen;
            if (nalStart >= data.length) {
                break;
            }
            int nextRel = findStartCode(data, nalStart, data.length - nalStart);
            if (nextRel == 0) {
                searchFrom = nalStart + 1;
                continue;
            }
            int nalEnd = nextRel < 0 ? data.length : nalStart + nextRel;
            if (nalEnd > nalStart) {
                list.add(new Nal(nalStart, nalEnd, (data[nalStart] >> 1) & 0x3f));
            }
            if (nextRel < 0) {
                break;
            }
            searchFrom = nalStart + nextRel;
        }
        return list;
    }

    private static byte[] copy(byte[] data, int start, int end) {
        byte[] out = new byte[end - start];
        System.arraycopy(data, start, out, 0, out.length);
        return out;
    }

    /** Returns the offset of a 3/4-byte Annex-B start code relative to off. */
    private static int findStartCode(byte[] data, int off, int len) {
        int end = off + len;
        for (int i = off; i + 2 < end; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i - off;
                }
                if (i + 3 < end && data[i + 2] == 0 && data[i + 3] == 1) {
                    return i - off;
                }
            }
        }
        return -1;
    }
}
