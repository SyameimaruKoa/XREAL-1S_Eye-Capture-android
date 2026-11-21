package com.syameimarukoa.xreal_recorder;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the active recording session with atomic swap and generation checks.
 * A stall that arrives while a new session is being built invalidates the build,
 * so a half-installed session can never survive.
 */
final class SessionRegistry {
    static final class State {
        final Object session;
        final boolean active;
        final long generation;

        State(Object session, boolean active, long generation) {
            this.session = session;
            this.active = active;
            this.generation = generation;
        }
    }

    private final AtomicReference<State> state =
            new AtomicReference<>(new State(null, false, 0L));

    long generation() {
        return state.get().generation;
    }

    /** Install a built session only when no stall retired it during construction. */
    boolean install(Object session, long expectedGeneration) {
        while (true) {
            State current = state.get();
            if (current.generation != expectedGeneration) {
                return false;
            }
            State next = new State(session, true, current.generation + 1);
            if (state.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /** Atomically retire the active session and return it for finalization. */
    Object retire() {
        while (true) {
            State current = state.get();
            State next = new State(null, false, current.generation + 1);
            if (state.compareAndSet(current, next)) {
                return current.session;
            }
        }
    }

    State current() {
        return state.get();
    }
}
