package jp.xreal.recorder;

import android.media.MediaFormat;

import androidx.media3.common.Format;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.FragmentedMp4Muxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * fMP4 muxer using the media3 library. Unlike MuxerSink (regular MP4, moov at
 * the end), fragmented MP4 writes moof/mdat per fragment, so data already
 * written remains playable if recording is interrupted.
 */
final class FragmentedMp4Sink implements RecordingMuxer {
    private final java.nio.channels.WritableByteChannel channel;
    private final java.io.OutputStream stream;
    private final FragmentedMp4Muxer muxer;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean failed;
    private String error = "";

    FragmentedMp4Sink(java.nio.channels.WritableByteChannel channel, java.io.OutputStream stream)
            throws IOException {
        this.channel = channel;
        this.stream = stream;
        this.muxer = new FragmentedMp4Muxer.Builder(channel)
                .setFragmentDurationMs(1000)
                .build();
    }

    @Override
    public void addVideoTrack(MediaFormat format) {
        try {
            Format.Builder b = new Format.Builder()
                    .setSampleMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    .setWidth(format.getInteger(MediaFormat.KEY_WIDTH))
                    .setHeight(format.getInteger(MediaFormat.KEY_HEIGHT));
            if (format.containsKey("csd-0")) {
                ByteBuffer csd = format.getByteBuffer("csd-0");
                byte[] bytes = new byte[csd.remaining()];
                csd.duplicate().get(bytes);
                // media3 parses Annex-B initialization data itself.
                b.setInitializationData(Collections.singletonList(bytes));
            }
            videoTrack = muxer.addTrack(b.build());
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void addAudioTrack(MediaFormat format) {
        try {
            Format.Builder b = new Format.Builder()
                    .setSampleMimeType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    .setChannelCount(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    .setSampleRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            if (format.containsKey("csd-0")) {
                ByteBuffer csd = format.getByteBuffer("csd-0");
                byte[] bytes = new byte[csd.remaining()];
                csd.duplicate().get(bytes);
                b.setInitializationData(Collections.singletonList(bytes));
            }
            audioTrack = muxer.addTrack(b.build());
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void writeVideo(byte[] data, long ptsUs, boolean keyFrame) {
        // media3 expects Annex-B samples (its AnnexBToAvccConverter converts them).
        write(videoTrack, data, ptsUs, keyFrame);
    }

    @Override
    public void writeAudio(byte[] data, long ptsUs) {
        write(audioTrack, data, ptsUs, false);
    }

    private void write(int track, byte[] data, long ptsUs, boolean keyFrame) {
        if (track < 0 || failed || data == null || data.length == 0) {
            return;
        }
        try {
            BufferInfo info = new BufferInfo(ptsUs, data.length,
                    keyFrame ? androidx.media3.common.C.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(track, ByteBuffer.wrap(data), info);
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void startVideoOnly() {
        // fMP4 has no global start() call; tracks are ready once added.
    }

    @Override
    public void finish() {
        try {
            muxer.close();
        } catch (Throwable t) {
            fail(t);
        }
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }

    private void fail(Throwable t) {
        failed = true;
        error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        android.util.Log.e("XrealFMp4", "fMP4 muxer failure", t);
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
