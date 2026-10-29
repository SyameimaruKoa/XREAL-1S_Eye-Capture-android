package jp.xreal.recorder;

public final class AudioSilenceDetectorTest {
    public static void main(String[] args) {
        AudioSilenceDetector detector = new AudioSilenceDetector(8);
        require(!detector.update(new byte[]{32, 0, -32, -1}), "short quiet input");
        require(detector.update(new byte[]{0, 0, 0, 0}), "sustained quiet input");
        require(!detector.isExactZero(), "quiet nonzero is distinct from zero");
        require(!detector.update(new byte[]{33, 0}), "audible input clears warning");
        require(!detector.update(new byte[]{0, 0, 0, 0, -33, -1}), "negative peak clears warning");
        require(detector.update(new byte[]{0, 0, 0, 0, 0, 0, 0, 0}), "warning returns");
        require(detector.isExactZero(), "sustained exact zero");
        detector.update(new byte[]{1, 0});
        require(!detector.isExactZero(), "nonzero clears exact zero");
        try {
            detector.update(new byte[]{0});
            throw new AssertionError("partial sample accepted");
        } catch (IllegalArgumentException expected) {
            // A partial PCM sample must not corrupt the detector state.
        }
        System.out.println("AudioSilenceDetectorTest passed");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
