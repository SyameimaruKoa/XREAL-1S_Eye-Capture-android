package jp.xreal.recorder;

/** Estimates a bounded timestamp only when the camera repeats its previous timestamp. */
final class TcpTimestampRecovery {
    private long typicalStepNs;

    void observeStep(long stepNs) {
        if (stepNs < 5_000_000L || stepNs > 40_000_000L) {
            return;
        }
        if (typicalStepNs == 0) {
            typicalStepNs = stepNs;
        } else if (stepNs >= typicalStepNs / 2 && stepNs <= typicalStepNs * 3 / 2) {
            typicalStepNs = (typicalStepNs * 7 + stepNs) / 8;
        }
    }

    boolean canEstimate() {
        return typicalStepNs > 0;
    }

    long estimateDuplicate(long previousPtsNs) {
        return previousPtsNs + typicalStepNs / 2;
    }
}
