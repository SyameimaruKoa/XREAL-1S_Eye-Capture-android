package jp.xreal.recorder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Writes HEVC access units and AAC frames into one MP4.
 *
 * MediaMuxer requires all tracks before start(). Audio starts about 2 s after
 * video, so video samples are buffered until the audio track is known.
 */
public final class MuxerSink {
    private static final int MAX_PENDING_VIDEO = 1200;

    private static final class Sample {
        final byte[] data;
        final long ptsUs;
        final int flags;

        Sample(byte[] data, long ptsUs, int flags) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }

    private final Object lock = new Object();
    private final MediaMuxer muxer;
    private final ArrayList<Sample> pendingVideo = new ArrayList<>();
    private final ArrayList<Sample> pendingAudio = new ArrayList<>();

    private int videoTrack = -1;
    private int audioTrack = -1;
    private long lastVideoPtsUs = -1;
    private long lastAudioPtsUs = -1;
    private boolean started;
    private boolean finished;
    private boolean failed;
    private String error = "";

    public MuxerSink(String path) throws IOException {
        muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    public void addVideoTrack(MediaFormat format) {
        synchronized (lock) {
            if (videoTrack >= 0 || finished || failed) {
                return;
            }
            try {
                videoTrack = muxer.addTrack(format);
                startIfReadyLocked();
            } catch (Throwable t) {
                failLocked(t);
            }
        }
    }

    public void addAudioTrack(MediaFormat format) {
        synchronized (lock) {
            if (audioTrack >= 0 || finished || failed) {
                return;
            }
            try {
                audioTrack = muxer.addTrack(format);
                startIfReadyLocked();
            } catch (Throwable t) {
                failLocked(t);
            }
        }
    }

    public void writeVideo(byte[] data, long ptsUs, boolean keyFrame) {
        synchronized (lock) {
            if (finished || failed) {
                return;
            }
            // A burst of UVC payloads can give two frames the same monotonic clock
            // reading. Keep PTS strictly increasing for MediaMuxer/players.
            ptsUs = Math.max(ptsUs, lastVideoPtsUs + 1);
            lastVideoPtsUs = ptsUs;
            int flags = keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            if (!started) {
                if (pendingVideo.size() < MAX_PENDING_VIDEO) {
                    pendingVideo.add(new Sample(data, ptsUs, flags));
                }
                return;
            }
            try {
                writeSampleLocked(videoTrack, data, ptsUs, flags);
            } catch (Throwable t) {
                failLocked(t);
            }
        }
    }

    public void writeAudio(byte[] data, long ptsUs) {
        synchronized (lock) {
            if (finished || failed) {
                return;
            }
            ptsUs = Math.max(ptsUs, lastAudioPtsUs + 1);
            lastAudioPtsUs = ptsUs;
            if (!started) {
                pendingAudio.add(new Sample(data, ptsUs, 0));
                return;
            }
            try {
                writeSampleLocked(audioTrack, data, ptsUs, 0);
            } catch (Throwable t) {
                failLocked(t);
            }
        }
    }

    /** Used when the USB microphone fails: keep a video-only MP4. */
    public void startVideoOnly() {
        synchronized (lock) {
            if (started || finished || failed || videoTrack < 0) {
                return;
            }
            try {
                muxer.start();
                started = true;
                flushPendingLocked();
                pendingAudio.clear();
            } catch (Throwable t) {
                failLocked(t);
            }
        }
    }

    private void startIfReadyLocked() {
        if (started || finished || failed || videoTrack < 0 || audioTrack < 0) {
            return;
        }
        try {
            muxer.start();
            started = true;
            flushPendingLocked();
        } catch (Throwable t) {
            failLocked(t);
        }
    }

    private void flushPendingLocked() {
        for (Sample s : pendingVideo) {
            writeSampleLocked(videoTrack, s.data, s.ptsUs, s.flags);
        }
        pendingVideo.clear();
        for (Sample s : pendingAudio) {
            writeSampleLocked(audioTrack, s.data, s.ptsUs, s.flags);
        }
        pendingAudio.clear();
    }

    private void writeSampleLocked(int track, byte[] data, long ptsUs, int flags) {
        if (track < 0) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, data.length, Math.max(0L, ptsUs), flags);
        muxer.writeSampleData(track, buffer, info);
    }

    private void failLocked(Throwable t) {
        failed = true;
        error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
    }

    public void finish() {
        synchronized (lock) {
            if (finished) {
                return;
            }
            finished = true;
            try {
                if (started) {
                    muxer.stop();
                } else if (!failed) {
                    failLocked(new IllegalStateException("MP4 muxer never started"));
                }
            } catch (Throwable t) {
                if (!failed) {
                    failLocked(t);
                }
            }
            try {
                muxer.release();
            } catch (Throwable t) {
                if (!failed) {
                    failLocked(t);
                }
            }
            pendingVideo.clear();
            pendingAudio.clear();
        }
    }

    public boolean isFailed() {
        synchronized (lock) {
            return failed;
        }
    }

    public String getError() {
        synchronized (lock) {
            return error;
        }
    }
}
