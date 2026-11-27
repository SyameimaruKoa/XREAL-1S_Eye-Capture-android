package com.syameimarukoa.xreal_recorder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class SessionRegistryTest {
    public static void main(String[] args) throws Exception {
        SessionRegistry registry = new SessionRegistry();

        // Normal flow: install then retire returns the exact session.
        long generation = registry.generation();
        Object first = new Object();
        require(registry.install(first, generation), "first install");
        require(registry.current().active, "active after install");
        require(registry.current().session == first, "session visible");
        require(registry.retire() == first, "retire returns installed session");
        require(!registry.current().active, "not active after retire");
        require(registry.current().session == null, "no session after retire");

        // The race: a stall retires while a new session is still being built.
        long staleGeneration = registry.generation();
        Object halfBuilt = new Object();
        require(registry.retire() == null, "no active session to retire");
        require(!registry.install(halfBuilt, staleGeneration),
                "install must reject a generation invalidated by a stall");
        require(registry.current().session == null, "stale session must not survive");
        require(!registry.current().active, "stale session must stay inactive");

        // A later reconnect must still install and run normally.
        long nextGeneration = registry.generation();
        Object third = new Object();
        require(registry.install(third, nextGeneration), "install after invalidation");
        require(registry.current().session == third, "fresh session after invalidation");
        require(registry.retire() == third, "retire fresh session");

        // Concurrent stalls must retire the session exactly once.
        Object second = new Object();
        require(registry.install(second, registry.generation()), "second install");
        AtomicReference<Object> retired = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    Object r = registry.retire();
                    if (r != null) {
                        retired.compareAndSet(null, r);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        require(retired.get() == second, "exactly one thread retires the session");
        require(registry.current().session == null, "registry empty after concurrent retire");

        // Integration-style: a mocked video listener drives stall -> reconnect -> install.
        MockVideoListener listener = new MockVideoListener();
        require(listener.onResumed(), "first reconnect installs a session");
        require(listener.published != null, "first session published");
        listener.onStalled();
        require(listener.published == null, "stall retires the published session");
        require(listener.onResumed(), "second reconnect installs a session");
        require(listener.connects == 2, "two successful connects");
        listener.onStalled();

        // Interleave a second stall while the next session is being built.
        long buildGeneration = listener.registry.generation();
        listener.onStalled();
        require(!listener.installCandidate(buildGeneration), "candidate invalidated by stall");
        require(listener.discarded != null, "invalidated candidate discarded, not published");
        require(listener.published == null, "no stray published session");
        require(listener.onResumed(), "later reconnect still works");
        require(listener.connects == 3, "three successful connects");

        System.out.println("SessionRegistryTest passed");
    }

    static final class MockVideoListener {
        final SessionRegistry registry = new SessionRegistry();
        Object published;
        Object discarded;
        int connects;

        void onStalled() {
            published = null;
            registry.retire();
        }

        boolean onResumed() {
            return installCandidate(registry.generation());
        }

        boolean installCandidate(long generation) {
            Object candidate = new Object();
            if (!registry.install(candidate, generation)) {
                discarded = candidate;
                return false;
            }
            published = candidate;
            connects++;
            return true;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
