package jp.xreal.recorder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class VideoWriterTest {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        final AtomicInteger written = new AtomicInteger();
        final AtomicInteger finished = new AtomicInteger();
        final CountDownLatch firstWriteEntered = new CountDownLatch(1);
        final CountDownLatch allowFirstWrite = new CountDownLatch(1);
        VideoWriter writer = new VideoWriter(new VideoWriter.Output() {
            public void write(byte[] data, long pts, boolean key, int index) throws Exception {
                require(index == written.getAndIncrement(), "order");
                if (index == 0) {
                    firstWriteEntered.countDown();
                    allowFirstWrite.await();
                }
            }
            public void finish() { finished.incrementAndGet(); }
        });
        writer.enqueue(new byte[]{1}, 0, false, 0);
        require(firstWriteEntered.await(2, TimeUnit.SECONDS), "writer did not start");
        for (int i = 1; i < 100; i++) writer.enqueue(new byte[]{1}, i, false, i);
        require(written.get() == 1, "writer unexpectedly resumed");
        allowFirstWrite.countDown();
        require(writer.finish() && !writer.isFailed(), "normal finish");
        require(written.get() == 100 && finished.get() == 1, "drain or finalize");
        require(writer.finish() && finished.get() == 1, "duplicate finish");
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        VideoWriter overflow = new VideoWriter(new VideoWriter.Output() {
            public void write(byte[] data, long pts, boolean key, int index) throws Exception {
                entered.countDown(); release.await();
            }
            public void finish() { }
        });
        byte[] large = new byte[1024 * 1024];
        overflow.enqueue(large, 0, false, 0);
        require(entered.await(2, TimeUnit.SECONDS), "writer did not start");
        for (int i = 0; i < 32; i++) overflow.enqueue(large, i, false, i);
        boolean rejected = false;
        try { overflow.enqueue(large, 33, false, 33); } catch (IllegalStateException e) { rejected = true; }
        require(rejected && overflow.isFailed(), "overflow was not reported");
        release.countDown();
        require(overflow.finish(), "overflow drain");
        VideoWriter failure = new VideoWriter(new VideoWriter.Output() {
            public void write(byte[] data, long pts, boolean key, int index) throws Exception { throw new Exception("injected"); }
            public void finish() { }
        });
        failure.enqueue(new byte[]{1}, 0, false, 0);
        for (int i = 0; i < 100 && !failure.isFailed(); i++) Thread.sleep(10);
        require(failure.isFailed() && failure.getError().contains("injected"), "failure not reported");
        require(failure.finish(), "failure finalize");
        System.out.println("VideoWriter tests passed");
    }
}
