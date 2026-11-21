package com.syameimarukoa.xreal_recorder;

import android.media.MediaFormat;

import androidx.media3.common.Format;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.FragmentedMp4Muxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * fMP4 muxer using the media3 library. Unlike MuxerSink (regular MP4, moov at
 * the end), fragmented MP4 writes moof/mdat per fragment, so data already
 * written remains playable if recording is interrupted.
 */
final class FragmentedMp4Sink implements RecordingMuxer {
    private static final class Sample {
        final byte[] data;
        final long ptsUs;
        final boolean keyFrame;
        Sample(byte[] data, long ptsUs, boolean keyFrame) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.keyFrame = keyFrame;
        }
    }

    private final Object lock = new Object();
    private final java.nio.channels.WritableByteChannel channel;
    private final java.io.OutputStream stream;
    private final FragmentedMp4Muxer muxer;
    private final List<Sample> pendingVideo = new ArrayList<>();
    private final List<Sample> pendingAudio = new ArrayList<>();
    private int videoTrack = -1;
    private int audioTrack = -1;
    private int videoSampleCount;
    private int audioSampleCount;
    private boolean tracksReady;
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
            android.util.Log.i("XrealFMp4", "video track added id=" + videoTrack
                    + " mime=" + b.build().sampleMimeType);
            flushPendingIfReady();
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
            android.util.Log.i("XrealFMp4", "audio track added id=" + audioTrack);
            flushPendingIfReady();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void flushPendingIfReady() {
        synchronized (lock) {
            if (tracksReady || videoTrack < 0) {
                return;
            }
            // Audio may never arrive (no microphone, replay). Flush video anyway.
            if (audioTrack < 0) {
                tracksReady = true;
                for (Sample s : pendingVideo) {
                    writeNow(videoTrack, s);
                }
                pendingVideo.clear();
                return;
            }
            tracksReady = true;
            for (Sample s : pendingVideo) {
                writeNow(videoTrack, s);
            }
            for (Sample s : pendingAudio) {
                writeNow(audioTrack, s);
            }
            pendingVideo.clear();
            pendingAudio.clear();
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
        synchronized (lock) {
            if (!tracksReady) {
                Sample s = new Sample(data, ptsUs, keyFrame);
                if (track == videoTrack) pendingVideo.add(s);
                else pendingAudio.add(s);
                return;
            }
        }
        writeNow(track, new Sample(data, ptsUs, keyFrame));
    }

    private void writeNow(int track, Sample s) {
        try {
            BufferInfo info = new BufferInfo(s.ptsUs, s.data.length,
                    s.keyFrame ? androidx.media3.common.C.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info);
            if (track == videoTrack) videoSampleCount++;
            if (track == audioTrack) audioSampleCount++;
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void startVideoOnly() {
        // fMP4 has no global start() call; tracks are ready once added.
    }

    @Override
    public void discard() {
        failed = true;
        try {
            muxer.close();
        } catch (Throwable ignored) {
        }
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
        try {
            channel.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void finish() {
        android.util.Log.i("XrealFMp4", "finish: videoSamples=" + videoSampleCount
                + " audioSamples=" + audioSampleCount + " failed=" + failed);
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
