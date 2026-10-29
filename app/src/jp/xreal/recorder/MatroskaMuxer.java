package jp.xreal.recorder;

import android.media.MediaFormat;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;

/** Bounded MJPEG/AAC sink; video and audio buffers are written without re-encoding. */
final class MatroskaMuxer implements RecordingMuxer {
    private static final long MAX_PENDING_BYTES = 32L * 1024 * 1024;
    private static final long REORDER_WINDOW_US = 1_000_000;

    private static final class Sample {
        final int track;
        final byte[] data;
        final long ptsUs;
        final boolean key;

        Sample(int track, byte[] data, long ptsUs, boolean key) {
            this.track = track;
            this.data = data;
            this.ptsUs = ptsUs;
            this.key = key;
        }
    }

    private final OutputStream output;
    private final ArrayList<Sample> pending = new ArrayList<>();
    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private MatroskaWriter writer;
    private long pendingBytes;
    private long latestPtsUs;
    private long lastEmittedPtsUs = -1;
    private boolean videoOnly;
    private boolean failed;
    private boolean finished;
    private String error = "";

    MatroskaMuxer(OutputStream output) {
        this.output = output;
    }

    @Override
    public synchronized void addVideoTrack(MediaFormat format) {
        if (videoFormat == null && !finished && !failed) {
            videoFormat = format;
            startIfReady();
        }
    }

    @Override
    public synchronized void addAudioTrack(MediaFormat format) {
        if (audioFormat == null && !videoOnly && !finished && !failed) {
            audioFormat = format;
            startIfReady();
        }
    }

    @Override
    public synchronized void writeVideo(byte[] data, long ptsUs, boolean keyFrame) {
        enqueue(new Sample(1, data, ptsUs, true));
    }

    @Override
    public synchronized void writeAudio(byte[] data, long ptsUs) {
        if (!videoOnly) {
            enqueue(new Sample(2, data, ptsUs, true));
        }
    }

    private void enqueue(Sample sample) {
        if (finished || failed) {
            return;
        }
        if (sample.data.length > MAX_PENDING_BYTES - pendingBytes) {
            fail(new IllegalStateException("Matroska pending buffer limit reached"));
            return;
        }
        pending.add(sample);
        pendingBytes += sample.data.length;
        latestPtsUs = Math.max(latestPtsUs, sample.ptsUs);
        flush(false);
    }

    @Override
    public synchronized void startVideoOnly() {
        if (writer == null && !finished && !failed) {
            videoOnly = true;
            startIfReady();
        }
    }

    private void startIfReady() {
        if (writer != null || videoFormat == null || (!videoOnly && audioFormat == null)) {
            return;
        }
        try {
            byte[] config = null;
            if (!videoOnly) {
                ByteBuffer csd = audioFormat.getByteBuffer("csd-0");
                if (csd == null || !csd.hasRemaining()) {
                    throw new IllegalStateException("AAC configuration is missing");
                }
                csd = csd.duplicate();
                config = new byte[csd.remaining()];
                csd.get(config);
            }
            writer = new MatroskaWriter(output, videoFormat.getInteger(MediaFormat.KEY_WIDTH),
                    videoFormat.getInteger(MediaFormat.KEY_HEIGHT),
                    videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE), config);
            flush(false);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void flush(boolean all) {
        if (writer == null || failed) {
            return;
        }
        pending.sort(Comparator.comparingLong(sample -> sample.ptsUs));
        int count = 0;
        try {
            while (count < pending.size()) {
                Sample sample = pending.get(count);
                if (!all && sample.ptsUs > latestPtsUs - REORDER_WINDOW_US) {
                    break;
                }
                if (!videoOnly || sample.track == 1) {
                    if (sample.ptsUs < lastEmittedPtsUs) {
                        throw new IllegalStateException("Audio/video interleave exceeded the buffering window");
                    }
                    writer.writeSample(sample.track, sample.data, sample.ptsUs, sample.key);
                    lastEmittedPtsUs = sample.ptsUs;
                }
                pendingBytes -= sample.data.length;
                count++;
            }
        } catch (Exception e) {
            fail(e);
        }
        if (count > 0) {
            pending.subList(0, count).clear();
        }
    }

    private void fail(Exception e) {
        failed = true;
        error = e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    @Override
    public synchronized void finish() {
        if (finished) {
            return;
        }
        flush(true);
        finished = true;
        try {
            if (writer != null) {
                writer.finish();
            } else {
                output.close();
                if (!failed) {
                    fail(new IllegalStateException("Matroska tracks were not ready"));
                }
            }
        } catch (Exception e) {
            if (!failed) {
                fail(e);
            }
        }
    }

    @Override
    public synchronized boolean isFailed() { return failed; }

    @Override
    public synchronized String getError() { return error; }
}
