package jp.xreal.recorder;

/** Detects sustained near-silence in the mono signed 16-bit PCM being saved. */
final class AudioSilenceDetector {
    private final long thresholdBytes;
    private long zeroBytes;
    private long exactZeroBytes;
    private boolean warning;

    AudioSilenceDetector(long thresholdBytes) {
        if (thresholdBytes <= 0) {
            throw new IllegalArgumentException("thresholdBytes must be positive");
        }
        this.thresholdBytes = thresholdBytes;
    }

    boolean update(byte[] pcm) {
        if ((pcm.length & 1) != 0) {
            throw new IllegalArgumentException("PCM must contain whole 16-bit samples");
        }
        for (int i = 0; i < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            exactZeroBytes = sample == 0 ? Math.min(thresholdBytes, exactZeroBytes + 2) : 0;
            if (Math.abs(sample) > 32) {
                zeroBytes = 0;
                warning = false;
            } else {
                zeroBytes = Math.min(thresholdBytes, zeroBytes + 2);
                warning = zeroBytes >= thresholdBytes;
            }
        }
        return warning;
    }

    boolean isExactZero() {
        return exactZeroBytes >= thresholdBytes;
    }
}
