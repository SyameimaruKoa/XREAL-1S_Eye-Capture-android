package com.syameimarukoa.xreal_recorder;

import android.media.MediaFormat;

import java.io.File;

/** Writes video/audio straight into the final MP4 via the JNI muxer. */
final class StreamingMuxSink implements RecordingMuxer {
    private final File output;
    private long handle;
    private boolean failed;
    private boolean closed;
    private int pendingWidth;
    private int pendingHeight;
    private byte[] pendingCsd;
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
        pendingWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        pendingHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
        pendingCsd = csd;
    }

    @Override
    public void addAudioTrack(MediaFormat format) {
    }

    @Override
    public void writeVideo(byte[] data, long ptsUs, boolean keyFrame) {
        if (failed) {
            return;
        }
        if (handle == 0) {
            byte[] csd = pendingCsd;
            byte[] extracted = UvcSource.extractCsd(data);
            if (extracted != null) {
                csd = extracted;
            }
            android.util.Log.i("XrealRec", "deferred open csd="
                    + (csd == null ? -1 : csd.length));
            String err = GyroflowInjector.openMuxError(output.getAbsolutePath(),
                    pendingWidth, pendingHeight, csd);
            if (err != null) {
                fail("openMux failed: " + err);
                return;
            }
            handle = GyroflowInjector.lastHandle();
            if (handle == 0) {
                fail("openMux returned no handle");
                return;
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
