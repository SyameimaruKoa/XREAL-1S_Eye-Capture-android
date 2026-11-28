package com.syameimarukoa.xreal_recorder;

import android.media.MediaFormat;

import java.io.File;

/** Writes video/audio straight into the final MP4 via the JNI muxer. */
final class StreamingMuxSink implements RecordingMuxer {
    private final File output;
    private long handle;
    private boolean failed;
    private boolean closed;
    private boolean csdApplied;
    private String error = "";

    StreamingMuxSink(File output) {
        android.util.Log.i("XrealRec", "mux output=" + output.getAbsolutePath());
        this.output = output;
    }

    File outputFile() {
        return output;
    }

    @Override
    public void addVideoTrack(MediaFormat format) {
        byte[] csd = new byte[0];
        java.nio.ByteBuffer buf = format.getByteBuffer("csd-0");
        if (buf != null) {
            csd = new byte[buf.remaining()];
            buf.duplicate().get(csd);
        }
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        String err = GyroflowInjector.openMuxError(
                output.getAbsolutePath(), width, height, csd);
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

    @Override
    public void addAudioTrack(MediaFormat format) {
    }

    @Override
    public void writeVideo(byte[] data, long ptsUs, boolean keyFrame) {
        if (failed || handle == 0) {
            return;
        }
        if (!csdApplied) {
            byte[] csd = UvcSource.extractCsd(data);
            boolean ok = csd != null && GyroflowInjector.setCsd(handle, csd);
            android.util.Log.i("XrealRec", "uvc csd len="
                    + (csd == null ? -1 : csd.length) + " set=" + ok);
            if (ok) {
                csdApplied = true;
            }
        }
        String err = GyroflowInjector.writeVideo(handle, data, ptsUs, keyFrame);
        if (err != null) {
            fail(err);
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

    @Override
    public void writeGyrf(byte[] data, long ptsUs) {
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
