package com.syameimarukoa.xreal_recorder;

import android.media.MediaFormat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Feeds raw HEVC/AAC to a resident ffmpeg CLI writing a fragmented MP4. */
final class FifoStreamSink implements RecordingMuxer {
    private final File hevcFifo;
    private final File aacFifo;
    private final File output;
    private final Process ffmpeg;
    private OutputStream hevc;
    private OutputStream aac;
    private boolean failed;
    private String error = "";

    FifoStreamSink(File hevcFifo, File aacFifo, File output, Process ffmpeg) {
        this.hevcFifo = hevcFifo;
        this.aacFifo = aacFifo;
        this.output = output;
        this.ffmpeg = ffmpeg;
    }

    /** The MP4 written by ffmpeg; the post-processing injector reads this. */
    File hevcFile() {
        return output;
    }

    File aacFile() {
        return output;
    }

    @Override
    public void addVideoTrack(MediaFormat format) {
        try {
            // Opening the write end blocks until ffmpeg opens the read end.
            hevc = new BufferedOutputStream(new FileOutputStream(hevcFifo));
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void addAudioTrack(MediaFormat format) {
        try {
            aac = new BufferedOutputStream(new FileOutputStream(aacFifo));
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
    public void startVideoOnly() {
    }

    @Override
    public void finish() {
        closeInput();
        try {
            // EOF on both FIFOs makes ffmpeg write the final moov and exit.
            ffmpeg.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void discard() {
        closeInput();
        ffmpeg.destroy();
        hevcFifo.delete();
        aacFifo.delete();
        output.delete();
    }

    private void closeInput() {
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
        closeInput();
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
