package com.syameimarukoa.xreal_recorder;

import android.media.MediaFormat;

import java.io.File;

/** Writes video/audio straight into the final MP4 via the JNI muxer. */
final class StreamingMuxSink implements RecordingMuxer {
    private final File output;
    private final File hevcSeed;
    private final File aacSeed;
    private long handle;
    private boolean failed;
    private boolean closed;
    private String error = "";

    StreamingMuxSink(File output, File hevcSeed, File aacSeed) {
        android.util.Log.i("XrealRec", "mux output=" + output.getAbsolutePath());
        this.output = output;
        this.hevcSeed = hevcSeed;
        this.aacSeed = aacSeed;
    }

    File outputFile() {
        return output;
    }

    @Override
    public void addVideoTrack(MediaFormat format) {
        try {
            java.io.OutputStream seed = new java.io.BufferedOutputStream(
                    new java.io.FileOutputStream(hevcSeed));
            java.nio.ByteBuffer csd = format.getByteBuffer("csd-0");
            if (csd != null) {
                byte[] bytes = new byte[csd.remaining()];
                csd.duplicate().get(bytes);
                seed.write(bytes);
            }
            seed.close();
            java.io.OutputStream aseed = new java.io.BufferedOutputStream(
                    new java.io.FileOutputStream(aacSeed));
            aseed.write(new byte[]{0x11, (byte) 0x88});
            aseed.close();
        } catch (Throwable t) {
            fail("seed write failed: " + t);
            return;
        }
        if (handle == 0) {
            String err = GyroflowInjector.openMuxError(output.getAbsolutePath(),
                    hevcSeed.getAbsolutePath(), aacSeed.getAbsolutePath());
            if (err != null) {
                fail("openMux failed: " + err);
            } else {
                handle = GyroflowInjector.lastHandle();
                android.util.Log.i("XrealRec", "mux handle=" + handle);
                if (handle == 0) {
                    fail("openMux returned no handle");
                }
            }
        }
    }

    @Override
    public void addAudioTrack(MediaFormat format) {
    }

    @Override
    public void writeVideo(byte[] data, long ptsUs, boolean keyFrame) {
        if (failed || handle == 0) {
            return;
        }
        if (!GyroflowInjector.writeVideo(handle, data, ptsUs, keyFrame)) {
            fail("writeVideo failed");
        }
    }

    @Override
    public void writeAudio(byte[] data, long ptsUs) {
        if (failed || handle == 0) {
            return;
        }
        if (!GyroflowInjector.writeAudio(handle, data, ptsUs)) {
            fail("writeAudio failed");
        }
    }

    /** Append one gyrf sample to the same MP4 after recording stops. */
    void writeGyrf(byte[] data, long ptsUs) {
        if (failed || handle == 0) {
            return;
        }
        if (!GyroflowInjector.writeGyrf(handle, data, ptsUs)) {
            fail("writeGyrf failed");
        }
    }

    @Override
    public void startVideoOnly() {
    }

    @Override
    public void finish() {
    }

    void close() {
        if (handle != 0 && !closed) {
            if (!GyroflowInjector.closeMux(handle)) {
                failed = true;
                error = "closeMux failed";
            }
            closed = true;
            handle = 0;
        }
    }

    @Override
    public void discard() {
        if (handle != 0 && !closed) {
            GyroflowInjector.closeMux(handle);
            closed = true;
            handle = 0;
        }
        output.delete();
    }

    private void fail(String message) {
        failed = true;
        error = message;
        android.util.Log.e("XrealRec", "mux fail: " + message);
    }

    @Override
    public boolean isFailed() {
        return failed;
    }

    @Override
    public String getError() {
        return error;
    }
}
