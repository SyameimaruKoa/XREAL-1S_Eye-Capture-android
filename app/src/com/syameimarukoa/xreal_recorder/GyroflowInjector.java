package com.syameimarukoa.xreal_recorder;

/** JNI wrapper for the Gyroflow/FFmpeg muxer. All writes go to one file. */
final class GyroflowInjector {
    static {
        System.loadLibrary("gyroflow_inject");
    }

    private GyroflowInjector() {
    }

    /** Open a fragmented MP4 for a single continuous write. */
    /** Open on a duplicated fd of the final SAF uri; one continuous write. */
    static native long openMuxFd(int fd, String hevc, String aac);

    static native boolean writeVideo(long handle, byte[] data, long ptsUs, boolean keyFrame);

    static native boolean writeAudio(long handle, byte[] data, long ptsUs);

    static native boolean writeGyrf(long handle, byte[] data, long ptsUs);

    /** Write the final trailer and release the handle. */
    static native boolean closeMux(long handle);

    /** Returns null on success, otherwise the FFmpeg error text. */
    static native String openMuxError(String output, int width, int height, byte[] csd);

    static native long lastHandle();

    static native boolean inject(String telemetryDir, String video, String output);

    /** Convert raw HEVC + ADTS AAC + gyrf frames to an MP4 (no device needed). */
    static native String muxRaw(String hevc, String aac, String telemetryDir, String output);
}
