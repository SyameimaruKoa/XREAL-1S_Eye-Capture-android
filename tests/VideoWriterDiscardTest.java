package com.syameimarukoa.xreal_recorder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class VideoWriterDiscardTest {
    public static void main(String[] args) {
        AtomicBoolean discardedFinishCalled = new AtomicBoolean(false);
        AtomicInteger written = new AtomicInteger();
        VideoWriter discarded = new VideoWriter(new VideoWriter.Output() {
            @Override
            public void write(byte[] data, long ptsUs, boolean keyFrame, int auIndex) {
                written.incrementAndGet();
            }

            @Override
            public void finish() {
                discardedFinishCalled.set(true);
            }
        });
        discarded.enqueue(new byte[]{1, 2, 3}, 0L, false, 1);
        discarded.discard();

        require(!discardedFinishCalled.get(), "discard must not publish the MP4");
        require(!discarded.isFailed(), "discard must not be reported as a failure");
        require(written.get() == 1, "queued sample is still drained");

        AtomicBoolean normalFinishCalled = new AtomicBoolean(false);
        VideoWriter normal = new VideoWriter(new VideoWriter.Output() {
            @Override
            public void write(byte[] data, long ptsUs, boolean keyFrame, int auIndex) {
            }

            @Override
            public void finish() {
                normalFinishCalled.set(true);
            }
        });
        normal.finish();
        require(normalFinishCalled.get(), "normal finish still publishes the MP4");

        System.out.println("VideoWriterDiscardTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
