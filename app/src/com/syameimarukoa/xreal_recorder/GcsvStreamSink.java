package com.syameimarukoa.xreal_recorder;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Appends IMU samples to a .gcsv sidecar as they arrive. Unlike the MP4 embed,
 * data survives an abnormal termination because every sample is flushed to disk.
 */
final class GcsvStreamSink implements GlassesImuClient.ImuSink {
    private final FileOutputStream out;
    private long lastFlushNs;

    GcsvStreamSink(FileOutputStream out, String videoFilename, long captureTimeMs)
            throws IOException {
        this.out = out;
        out.write(GyroCsvFormatter.header(videoFilename, captureTimeMs)
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void onSample(long timeMs, float[] gyro, float[] accel) {
        String row = String.format(Locale.US, "%d,%.6f,%.6f,%.6f,%.5f,%.5f,%.5f\n",
                timeMs, gyro[0], gyro[1], gyro[2], accel[0], accel[1], accel[2]);
        try {
            out.write(row.getBytes(StandardCharsets.UTF_8));
            // Flush at most once per 100 ms to bound Flash writes while keeping
            // the loss window small on a crash.
            long now = System.nanoTime();
            if (now - lastFlushNs >= 100_000_000L) {
                out.flush();
                lastFlushNs = now;
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        try {
            out.flush();
            out.close();
        } catch (IOException ignored) {
        }
    }
}
