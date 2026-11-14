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
        final CountDownLatch slowEntered = new CountDownLatch(1);
        final CountDownLatch slowRelease = new CountDownLatch(1);
        final AtomicInteger delayReports = new AtomicInteger();
        final AtomicInteger slowFinalized = new AtomicInteger();
        VideoWriter slow = new VideoWriter(new VideoWriter.Output() {
            public void write(byte[] data, long pts, boolean key, int index) throws Exception {
                slowEntered.countDown();
                slowRelease.await();
            }
            public void finish() { slowFinalized.incrementAndGet(); }
        });
        slow.enqueue(new byte[]{1}, 0, false, 0);
        require(slowEntered.await(2, TimeUnit.SECONDS), "slow writer did not start");
        require(slow.finish(new Runnable() {
            public void run() {
                delayReports.incrementAndGet();
                slowRelease.countDown();
            }
        }), "slow finish");
        require(delayReports.get() == 1 && slowFinalized.get() == 1 && !slow.isFailed(),
                "slow output should finish without a false error");
        final CountDownLatch interruptedEntered = new CountDownLatch(1);
        final CountDownLatch interruptedRelease = new CountDownLatch(1);
        final CountDownLatch finishEntered = new CountDownLatch(1);
        final AtomicInteger interruptedFinalized = new AtomicInteger();
        final AtomicInteger interruptRestored = new AtomicInteger();
        VideoWriter interruptedWriter = new VideoWriter(new VideoWriter.Output() {
            public void write(byte[] data, long pts, boolean key, int index) throws Exception {
                interruptedEntered.countDown();
                interruptedRelease.await();
            }
            public void finish() { interruptedFinalized.incrementAndGet(); }
        });
        interruptedWriter.enqueue(new byte[]{1}, 0, false, 0);
        require(interruptedEntered.await(2, TimeUnit.SECONDS), "interrupted writer did not start");
        Thread finisher = new Thread(new Runnable() {
            public void run() {
                finishEntered.countDown();
                interruptedWriter.finish();
                if (Thread.currentThread().isInterrupted()) {
                    interruptRestored.incrementAndGet();
                }
            }
        });
        finisher.start();
        require(finishEntered.await(2, TimeUnit.SECONDS), "finisher did not start");
        finisher.interrupt();
        finisher.join(200);
        require(finisher.isAlive() && interruptedFinalized.get() == 0,
                "interrupt ended waiting before output finalized");
        interruptedRelease.countDown();
        finisher.join(2000);
        require(!finisher.isAlive() && interruptedFinalized.get() == 1
                && interruptRestored.get() == 1 && !interruptedWriter.isFailed(),
                "interrupt was not restored after output finalized");
        System.out.println("VideoWriter tests passed");
    }
}
