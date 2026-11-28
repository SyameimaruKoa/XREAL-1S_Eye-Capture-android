package com.syameimarukoa.xreal_recorder;

import android.media.MediaFormat;

import java.io.File;

/** Writes video/audio straight into the final MP4 via the JNI muxer. */
final class StreamingMuxSink implements RecordingMuxer {
    private final File output;
    private long handle;
    private boolean failed;
    private boolean closed;
    private boolean videoLogged;
    private int pendingWidth;
    private int pendingHeight;
    private byte[] pendingCsd;
    private byte[] gyrfHeader;
    private boolean gyrfHeaderWritten;
    private byte[] pendingFrame;
    private long pendingFramePts;
    private boolean pendingFrameKey;
    private String error = "";

    StreamingMuxSink(File output) {
        android.util.Log.i("XrealRec", "mux output=" + output.getAbsolutePath());
        this.output = output;
    }

    File outputFile() {
        return output;
    }

    void setGyrfHeader(byte[] header) {
        this.gyrfHeader = header;
    }

    @Override
    synchronized public void addVideoTrack(MediaFormat format) {
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
    synchronized public void addAudioTrack(MediaFormat format) {
    }

    @Override
    synchronized public void writeVideo(byte[] data, long ptsUs, boolean keyFrame) {
        if (!videoLogged) {
            android.util.Log.i("XrealRec", "writeVideo called handle=" + handle);
            videoLogged = true;
        }
        if (failed) {
            return;
        }
        if (handle == 0) {
            byte[] csd = pendingCsd;
            byte[] extracted = UvcSource.extractCsd(data);
            if (extracted != null) {
                csd = extracted;
            }
            if (csd == null || csd.length == 0) {
                // The UVC stream may not carry VPS/SPS/PPS yet. Hold the first
                // access unit until a later one supplies the parameter sets.
                pendingFrame = data;
                pendingFramePts = ptsUs;
                pendingFrameKey = keyFrame;
                return;
            }
            android.util.Log.i("XrealRec", "deferred open csd=" + csd.length);
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
            if (pendingFrame != null) {
                byte[] held = pendingFrame;
                long heldPts = pendingFramePts;
                boolean heldKey = pendingFrameKey;
                pendingFrame = null;
                writeVideo(held, heldPts, heldKey);
            }
        }
        byte[] payload = UvcSource.stripParameterSets(data);
        String err = GyroflowInjector.writeVideo(handle, payload, ptsUs, keyFrame);
        if (err != null) {
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < Math.min(32, data.length); i++) {
                head.append(String.format("%02x", data[i] & 0xff));
            }
            android.util.Log.e("XrealRec", "writeVideo reject len=" + data.length
                    + " key=" + keyFrame + " pts=" + ptsUs + " head=" + head);
            fail(err);
        }
    }

    @Override
    synchronized public void writeAudio(byte[] data, long ptsUs) {
        if (failed || handle == 0) {
            return;
        }
        if (!GyroflowInjector.writeAudio(handle, data, ptsUs)) {
            fail("writeAudio failed");
        }
    }

    @Override
    synchronized public void writeGyrf(byte[] data, long ptsUs) {
        if (failed || handle == 0) {
            return;
        }
        if (gyrfHeader != null && !gyrfHeaderWritten) {
            ByteArrayOutputStream merged = new ByteArrayOutputStream(
                    data.length + gyrfHeader.length + 8);
            merged.write(data, 0, data.length);
            merged.write(0x1a);
            int len = gyrfHeader.length;
            while (len >= 0x80) {
                merged.write((len & 0x7f) | 0x80);
                len >>>= 7;
            }
            merged.write(len);
            merged.write(gyrfHeader, 0, gyrfHeader.length);
            data = merged.toByteArray();
            gyrfHeaderWritten = true;
        }
        if (!GyroflowInjector.writeGyrf(handle, data, ptsUs)) {
            fail("writeGyrf failed");
        }
    }

    @Override
    public void startVideoOnly() {
    }

    @Override
    synchronized public void finish() {
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
