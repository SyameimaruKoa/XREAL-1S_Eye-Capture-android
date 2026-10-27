package jp.xreal.recorder;

/** Detects consecutive all-zero PCM buffers, not merely quiet audio. */
final class AudioSilenceDetector {
    private final long thresholdBytes;
    private long zeroBytes;
    private boolean warning;

    AudioSilenceDetector(long thresholdBytes) {
        if (thresholdBytes <= 0) {
            throw new IllegalArgumentException("thresholdBytes must be positive");
        }
        this.thresholdBytes = thresholdBytes;
    }

    boolean update(byte[] pcm) {
        for (byte value : pcm) {
            if (value != 0) {
                zeroBytes = 0;
                warning = false;
                return false;
            }
        }
        zeroBytes = Math.min(thresholdBytes, zeroBytes + pcm.length);
        warning = zeroBytes >= thresholdBytes;
        return warning;
    }
}
