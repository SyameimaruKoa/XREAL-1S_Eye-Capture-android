package jp.xreal.recorder;

import android.media.MediaFormat;

/** A container sink for untouched video frames and encoded audio. */
interface RecordingMuxer {
    void addVideoTrack(MediaFormat format);

    void addAudioTrack(MediaFormat format);

    void writeVideo(byte[] data, long ptsUs, boolean keyFrame);

    void writeAudio(byte[] data, long ptsUs);

    void startVideoOnly();

    void finish();

    boolean isFailed();

    String getError();
}
