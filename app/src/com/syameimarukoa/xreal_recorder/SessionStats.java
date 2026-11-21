package com.syameimarukoa.xreal_recorder;

/** Per-recording-file counters. A reconnect starts a fresh instance. */
final class SessionStats {
    long frames;
    long bytes;
    long firstNs;
    long lastNs;

    void observe(long receivedNs, int byteCount) {
        if (frames == 0) {
            firstNs = receivedNs;
        }
        lastNs = receivedNs;
        frames++;
        bytes += byteCount;
    }

    double averageFps() {
        double spanNs = lastNs - firstNs;
        return frames > 1 && spanNs > 0 ? (frames - 1) * 1_000_000_000.0 / spanNs : 0.0;
    }

    double averageBitrateBps() {
        double seconds = (lastNs - firstNs) / 1_000_000_000.0;
        return seconds > 0 ? bytes * 8.0 / seconds : 0.0;
    }
}
