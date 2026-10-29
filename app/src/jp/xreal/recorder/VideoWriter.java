package jp.xreal.recorder;

import java.util.ArrayDeque;

/** Moves video output off the UVC receive thread without silently dropping data. */
final class VideoWriter {
    interface Output {
        void write(byte[] data, long ptsUs, boolean keyFrame, int auIndex) throws Exception;
        void finish() throws Exception;
    }

    private static final int MAX_SAMPLES = 1200;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final long JOIN_TIMEOUT_MS = 10000;

    private static final class Sample {
        final byte[] data;
        final long ptsUs;
        final boolean keyFrame;
        final int auIndex;

        Sample(byte[] data, long ptsUs, boolean keyFrame, int auIndex) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.keyFrame = keyFrame;
            this.auIndex = auIndex;
        }
    }

    private final Object lock = new Object();
    private final ArrayDeque<Sample> queue = new ArrayDeque<>();
    private final Output output;
    private final Thread thread;
    private long queuedBytes;
    private boolean closing;
    private volatile String error = "";

    VideoWriter(Output output) {
        this.output = output;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runWriter();
            }
        }, "xreal-video-writer");
        thread.start();
    }

    void enqueue(byte[] data, long ptsUs, boolean keyFrame, int auIndex) {
        synchronized (lock) {
            if (closing || !error.isEmpty()) {
                throw new IllegalStateException("Video writer unavailable: " + error);
            }
            if (queue.size() >= MAX_SAMPLES || data.length > MAX_BYTES - queuedBytes) {
                error = "Video output queue limit reached; recording stopped";
                lock.notifyAll();
                throw new IllegalStateException(error);
            }
            queue.addLast(new Sample(data, ptsUs, keyFrame, auIndex));
            queuedBytes += data.length;
            lock.notifyAll();
        }
    }

    private void runWriter() {
        try {
            while (true) {
                Sample sample;
                synchronized (lock) {
                    while (queue.isEmpty() && !closing) {
                        lock.wait();
                    }
                    if (queue.isEmpty() && closing) {
                        break;
                    }
                    sample = queue.removeFirst();
                    queuedBytes -= sample.data.length;
                }
                output.write(sample.data, sample.ptsUs, sample.keyFrame, sample.auIndex);
            }
        } catch (Throwable t) {
            if (error.isEmpty()) {
                error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            }
        } finally {
            synchronized (lock) {
                while (!closing) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        closing = true;
                    }
                }
            }
            try {
                output.finish();
            } catch (Throwable t) {
                if (error.isEmpty()) {
                    error = "Video output finalization failed: " + t.getClass().getSimpleName()
                            + ": " + String.valueOf(t.getMessage());
                }
            }
        }
    }

    boolean finish() {
        synchronized (lock) {
            closing = true;
            lock.notifyAll();
        }
        try {
            thread.join(JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (error.isEmpty()) {
                error = "Interrupted while waiting for video output";
            }
        }
        if (thread.isAlive()) {
            if (error.isEmpty()) {
                error = "Video output did not stop within 10 seconds";
            }
            return false;
        }
        return true;
    }

    boolean isFailed() {
        return !error.isEmpty();
    }

    String getError() {
        return error;
    }
}
