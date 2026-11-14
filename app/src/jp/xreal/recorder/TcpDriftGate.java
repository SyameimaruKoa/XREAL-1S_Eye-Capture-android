package jp.xreal.recorder;

/** Delays clock corrections until a receive/camera offset persists. */
final class TcpDriftGate {
    private static final long DRIFT_LIMIT_NS = 100_000_000L;
    private static final long PERSISTENCE_NS = 3_000_000_000L;
    private long sinceNs = -1;
    private int direction;

    boolean shouldReanchor(long driftNs, long arrivalNs) {
        int currentDirection = driftNs > DRIFT_LIMIT_NS ? 1
                : driftNs < -DRIFT_LIMIT_NS ? -1 : 0;
        if (currentDirection == 0) {
            sinceNs = -1;
            direction = 0;
            return false;
        }
        if (sinceNs < 0 || currentDirection != direction || arrivalNs < sinceNs) {
            sinceNs = arrivalNs;
            direction = currentDirection;
            return false;
        }
        if (arrivalNs - sinceNs < PERSISTENCE_NS) {
            return false;
        }
        sinceNs = -1;
        direction = 0;
        return true;
    }

    void reset() {
        sinceNs = -1;
        direction = 0;
    }
}
