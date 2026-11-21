package com.syameimarukoa.xreal_recorder;

import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RecordingMuxer backed by StreamingGyroflowMuxer: video, audio and Gyroflow
 * protobuf are written together into fMP4 fragments in a single pass.
 */
final class StreamingMuxerSink implements RecordingMuxer {
    private final StreamingGyroflowMuxer muxer;
    private final Object lock = new Object();
    private byte[] pendingAudio;
    private byte[] header;
    private boolean started;
    private int frameNumber;
    private long firstPtsUs = -1;
    private byte[] pendingVideo;
    private long pendingVideoPts;
    private boolean pendingKeyFrame;
    private int width = 1600;
    private int height = 1200;
    private volatile boolean failed;
    private String error = "";

    StreamingMuxerSink(StreamingGyroflowMuxer muxer) {
        this.muxer = muxer;
    }

    void setHeader(byte[] header) {
        this.header = header;
    }

    /** Called for each incoming IMU sample; queued for the next video frame. */
    void onImuSample(GyroflowProto.ImuSample sample) {
        synchronized (lock) {
            queuedImu.add(sample);
        }
    }

    /** ImuSink adapter: converts raw sensor values and queues them. */
    GlassesImuClient.ImuSink imuSink() {
        return new GlassesImuClient.ImuSink() {
            @Override
            public void onSample(long timeMs, float[] gyro, float[] accel) {
                onImuSample(new GyroflowProto.ImuSample(timeMs * 1000.0,
                        gyro[0], gyro[1], gyro[2], accel[0], accel[1], accel[2]));
            }

            @Override
            public void close() {
            }
        };
    }

    private final List<GyroflowProto.ImuSample> queuedImu = new ArrayList<>();

    @Override
    public void addVideoTrack(MediaFormat format) {
        synchronized (lock) {
            width = format.getInteger(MediaFormat.KEY_WIDTH);
            height = format.getInteger(MediaFormat.KEY_HEIGHT);
            ByteBuffer csdBuf = format.getByteBuffer("csd-0");
            if (csdBuf != null) {
                byte[] bytes = new byte[csdBuf.remaining()];
                csdBuf.duplicate().get(bytes);
                muxer.setCsd(bytes, width, height);
            }
            // Do not start here: onCsd may arrive after addVideoTrack. start() is
            // called lazily from writeVideo once the real CSD is available.
        }
    }

    @Override
    public void addAudioTrack(MediaFormat format) {
        // Fixed audio sample entry in the header.
    }

    @Override
    public void writeVideo(byte[] data, long ptsUs, boolean keyFrame) {
        synchronized (lock) {
            if (firstPtsUs < 0) {
                firstPtsUs = ptsUs;
            }
            // Hold the previous AU until the next PTS arrives; only then is its
            // real duration known. The glasses run at ~59.4 fps, so a fixed
            // 16,666 us drifts ~2 s over a 150 s clip and desyncs the gyrf
            // timestamps from the MP4 timeline.
            if (pendingVideo != null) {
                long durationUs = Math.max(1L, ptsUs - pendingVideoPts);
                flushVideo(pendingVideo, pendingVideoPts, pendingKeyFrame, durationUs);
            }
            pendingVideo = data;
            pendingVideoPts = ptsUs;
            pendingKeyFrame = keyFrame;
        }
    }

    private void flushVideo(byte[] data, long ptsUs, boolean keyFrame, long durationUs) {
        if (!started) {
            try {
                muxer.start();
                started = true;
            } catch (IOException e) {
                failed = true;
                error = String.valueOf(e);
                return;
            }
        }
        byte[] audio = pendingAudio != null ? pendingAudio : new byte[]{0};
        pendingAudio = null;
        byte[] gyro = nextGyroPayload(ptsUs - firstPtsUs);
        try {
            muxer.writeSample(data, audio, gyro, durationUs);
        } catch (IOException e) {
            failed = true;
            error = String.valueOf(e);
        }
    }

    private byte[] nextGyroPayload(long ptsUs) {
        if (header == null) {
            return new byte[0];
        }
        frameNumber++;
        double startUs = (double) ptsUs;
        double endUs = startUs;
        List<GyroflowProto.ImuSample> frameSamples = new ArrayList<>(queuedImu);
        queuedImu.clear();
        byte[] frame = GyroflowProto.frame(frameNumber, startUs, endUs,
                frameSamples, width, height);
        return GyroflowProto.main(frameNumber == 1 ? header : null, frame);
    }

    @Override
    public void writeAudio(byte[] data, long ptsUs) {
        synchronized (lock) {
            pendingAudio = data;
        }
    }

    @Override
    public void startVideoOnly() {
    }

    @Override
    public void finish() {
        synchronized (lock) {
            if (pendingVideo != null) {
                flushVideo(pendingVideo, pendingVideoPts, pendingKeyFrame, 16_666L);
                pendingVideo = null;
            }
            try {
                muxer.stop();
            } catch (IOException e) {
                failed = true;
                error = String.valueOf(e);
            }
        }
    }

    @Override
    public void discard() {
        failed = true;
        finish();
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
