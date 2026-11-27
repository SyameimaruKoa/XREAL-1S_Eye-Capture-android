package com.syameimarukoa.xreal_recorder;

import android.media.MediaFormat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** Writes untouched Annex-B HEVC and ADTS AAC for the external ffmpeg muxer. */
final class RawStreamSink implements RecordingMuxer {
    private final File hevcFile;
    private final File aacFile;
    private OutputStream hevc;
    private OutputStream aac;
    private boolean failed;
    private String error = "";

    RawStreamSink(File hevcFile, File aacFile) {
        this.hevcFile = hevcFile;
        this.aacFile = aacFile;
    }

    File hevcFile() {
        return hevcFile;
    }

    File aacFile() {
        return aacFile;
    }

    @Override
    public void addVideoTrack(MediaFormat format) {
        try {
            hevc = new BufferedOutputStream(new FileOutputStream(hevcFile));
            ByteBuffer csd = format.getByteBuffer("csd-0");
            if (csd != null) {
                byte[] bytes = new byte[csd.remaining()];
                csd.duplicate().get(bytes);
                hevc.write(bytes);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void addAudioTrack(MediaFormat format) {
        try {
            aac = new BufferedOutputStream(new FileOutputStream(aacFile));
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void writeVideo(byte[] data, long ptsUs, boolean keyFrame) {
        if (hevc == null || failed) {
            return;
        }
        try {
            hevc.write(data);
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void writeAudio(byte[] data, long ptsUs) {
        if (aac == null || failed) {
            return;
        }
        try {
            // The platform AAC encoder emits raw frames; ffmpeg's aac demuxer
            // needs an ADTS header on each one. AAC LC / 48 kHz / mono.
            aac.write(adtsHeader(data.length + 7));
            aac.write(data);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static byte[] adtsHeader(int frameLength) {
        byte[] h = new byte[7];
        h[0] = (byte) 0xFF;
        h[1] = (byte) 0xF1;
        h[2] = (byte) 0x4C;
        h[3] = (byte) (0x80 | ((frameLength >>> 11) & 0x03));
        h[4] = (byte) ((frameLength >>> 3) & 0xFF);
        h[5] = (byte) (((frameLength & 0x07) << 5) | 0x1F);
        h[6] = (byte) 0x00;
        return h;
    }

    @Override
    public void writeGyrf(byte[] data, long ptsUs) {
    }

    @Override
    public void startVideoOnly() {
    }

    @Override
    public void finish() {
        close();
    }

    @Override
    public void discard() {
        close();
        hevcFile.delete();
        aacFile.delete();
    }

    private void close() {
        try {
            if (hevc != null) {
                hevc.close();
                hevc = null;
            }
        } catch (IOException ignored) {
        }
        try {
            if (aac != null) {
                aac.close();
                aac = null;
            }
        } catch (IOException ignored) {
        }
    }

    private void fail(Throwable t) {
        failed = true;
        error = t.toString();
        close();
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
