package com.syameimarukoa.xreal_recorder;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Feeds a recording from raw TCP payload dumps captured with tcpdump.
 * Used for UI/debugging on devices without XREAL glasses (e.g. an emulator).
 */
final class ReplayTcpSource implements VideoSource {
    private static final String DIR = "/sdcard/Android/data/com.syameimarukoa.xreal_recorder/files/replay";

    private UvcSource.Listener listener;
    private volatile boolean running;
    private volatile boolean failed;
    private String error = "";
    private Thread thread;
    private int accessUnits;
    private boolean csdSent;
    private int width = 1600;
    private int height = 1200;

    @Override
    public void open() throws Exception {
        File video = new File(DIR, "replay_52995.bin");
        File imu = new File(DIR, "replay_52998.bin");
        android.util.Log.i("XrealReplay", "video exists=" + video.exists()
                + " len=" + video.length() + " path=" + video.getAbsolutePath());
        android.util.Log.i("XrealReplay", "imu exists=" + imu.exists()
                + " len=" + imu.length() + " path=" + imu.getAbsolutePath());
        if (!video.exists()) {
            throw new java.io.IOException("Replay dump not found: " + video.getAbsolutePath());
        }
    }

    @Override
    public void start(UvcSource.Listener listener) throws Exception {
        this.listener = listener;
        running = true;
        thread = new Thread(this::runReplay, "xreal-replay");
        thread.setDaemon(true);
        thread.start();
    }

    private void runReplay() {
        try {
            File video = new File(DIR, "replay_52995.bin");
            while (running) {
              try (InputStream in = new BufferedInputStream(new FileInputStream(video))) {
                byte[] header = new byte[6];
                while (running) {
                    if (!readFully(in, header)) {
                        break;
                    }
                    int command = ((header[0] & 0xff) << 8) | (header[1] & 0xff);
                    int length = ((header[2] & 0xff) << 24) | ((header[3] & 0xff) << 16)
                            | ((header[4] & 0xff) << 8) | (header[5] & 0xff);
                    if (command != 0x2785 || length < 102 || length > 2_000_000) {
                        failed = true;
                        error = "Bad replay frame: " + command + " len=" + length;
                        return;
                    }
                    byte[] body = new byte[length];
                    if (!readFully(in, body)) {
                        break;
                    }
                    int w = le32(body, 12);
                    int h = le32(body, 16);
                    if (w > 0 && h > 0) {
                        width = w;
                        height = h;
                    }
                    byte[] au = java.util.Arrays.copyOfRange(body, 97, body.length);
                    if (listener != null) {
                        int nal = (au[4] >> 1) & 0x3f;
                        if (nal == 32 && !csdSent) {
                            byte[] csd = UvcSource.extractCsd(au);
                            android.util.Log.i("XrealReplay", "CSD nal=32 extracted="
                                    + (csd != null) + " len=" + (csd == null ? 0 : csd.length));
                            if (csd != null) {
                                listener.onCsd(csd);
                                csdSent = true;
                            }
                            continue;
                        }
                        if (!csdSent) {
                            continue;
                        }
                        if (accessUnits < 3 || accessUnits % 1000 == 0) {
                            android.util.Log.i("XrealReplay", "AU " + accessUnits
                                    + " nal=" + nal + " bytes=" + au.length);
                        }
                        listener.onAccessUnit(au, android.os.SystemClock.elapsedRealtimeNanos());
                    }
                    accessUnits++;
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
              }
              if (running) {
                  csdSent = false; // replay the clip from the beginning
              }
            }
        } catch (Throwable t) {
            failed = true;
            error = String.valueOf(t);
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return off == buf.length;
            }
            off += n;
        }
        return true;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isFailed() { return failed; }

    @Override
    public String getError() { return error; }

    @Override
    public int getAccessUnits() { return accessUnits; }

    @Override
    public int getWidth() { return width; }

    @Override
    public int getHeight() { return height; }

    @Override
    public int getFrameRate() { return 60; }
}
