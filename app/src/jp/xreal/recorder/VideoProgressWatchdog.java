package jp.xreal.recorder;

/** Evaluates whether the video stream has stopped making frame progress. */
final class VideoProgressWatchdog {
    static boolean isStalled(boolean recording, boolean stopping, long frames, long lastFrameNs,
                             long startedMs, long nowMs, long nowNs) {
        if (!recording || stopping) {
            return false;
        }
        if (frames == 0 || lastFrameNs == 0) {
            return nowMs - startedMs >= 10_000L;
        }
        return nowNs - lastFrameNs >= 3_000_000_000L;
    }
}
