package com.syameimarukoa.xreal_recorder;

/** JNI wrapper for the Gyroflow/FFmpeg muxer. All writes go to one file. */
final class GyroflowInjector {
    static {
        System.loadLibrary("gyroflow_inject");
    }

    private GyroflowInjector() {
    }

    /** Open a fragmented MP4 for a single continuous write. */
    static native long openMux(String output, String hevc, String aac);

    /** Open on a duplicated fd of the final SAF uri; one continuous write. */
    static native long openMuxFd(int fd, String hevc, String aac);

    static native boolean writeVideo(long handle, byte[] data, long ptsUs, boolean keyFrame);

    static native boolean writeAudio(long handle, byte[] data, long ptsUs);

    static native boolean writeGyrf(long handle, byte[] data, long ptsUs);

    /** Write the final trailer and release the handle. */
    static native boolean closeMux(long handle);

    static native boolean inject(String telemetryDir, String video, String output);
}
