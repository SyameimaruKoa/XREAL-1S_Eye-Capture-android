package com.syameimarukoa.xreal_recorder;

/** JNI wrapper for Gyroflow's official ffmpeg-next based gyrf muxer. */
final class GyroflowInjector {
    static {
        System.loadLibrary("gyroflow_inject");
    }

    private GyroflowInjector() {
    }

    static native boolean inject(String telemetryDir, String video, String output);
}
