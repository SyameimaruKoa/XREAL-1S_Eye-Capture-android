package com.syameimarukoa.xreal_recorder;

import java.util.concurrent.atomic.AtomicReference;

public final class SessionStatsTest {
    public static void main(String[] args) {
        SessionStats first = new SessionStats();
        first.observe(1_000_000_000L, 100);
        first.observe(1_016_666_667L, 200);

        require(first.frames == 2, "frames after two AUs");
        require(first.bytes == 300, "bytes after two AUs");
        require(first.firstNs == 1_000_000_000L, "first timestamp");
        require(first.lastNs == 1_016_666_667L, "last timestamp");
        require(Math.abs(first.averageFps() - 60.0) < 0.01, "average fps");
        require(first.averageBitrateBps() > 0, "average bitrate");

        SessionStats single = new SessionStats();
        single.observe(5_000_000_000L, 50);
        require(single.frames == 1, "fresh session frames");
        require(single.bytes == 50, "fresh session bytes");
        require(single.firstNs == 5_000_000_000L, "fresh session timestamp");
        require(single.averageFps() == 0.0, "single frame has no fps");
        require(first.frames == 2, "previous session untouched by new session");

        // Simulate the reconnect: swapping the shared reference must start clean
        // while the old session keeps its captured stats for finalization.
        AtomicReference<SessionStats> current = new AtomicReference<>(single);
        SessionStats capturedForFinish = current.get();
        current.set(new SessionStats());
        current.get().observe(9_000_000_000L, 10);
        require(capturedForFinish.frames == 1, "captured stats unchanged after rotation");
        require(current.get().frames == 1, "new session starts from zero");
        require(capturedForFinish.firstNs != current.get().firstNs, "sessions have distinct anchors");

        System.out.println("SessionStatsTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
