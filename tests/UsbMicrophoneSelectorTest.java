package jp.xreal.recorder;

public final class UsbMicrophoneSelectorTest {
    public static void main(String[] args) {
        require(UsbMicrophoneSelector.select(new String[]{}) == UsbMicrophoneSelector.NONE,
                "no input");
        require(UsbMicrophoneSelector.select(new String[]{null}) == 0,
                "single unnamed input");
        require(UsbMicrophoneSelector.select(new String[]{"USB mic", "USB-Audio - XREAL 1S"}) == 1,
                "XREAL input wins over another USB mic");
        require(UsbMicrophoneSelector.select(new String[]{"xReAl 1S", "USB mic"}) == 0,
                "name matching ignores case");
        require(UsbMicrophoneSelector.select(new String[]{null, "USB mic"})
                == UsbMicrophoneSelector.AMBIGUOUS, "unidentified multiple inputs");
        require(UsbMicrophoneSelector.select(new String[]{"XREAL one", "XREAL two"})
                == UsbMicrophoneSelector.AMBIGUOUS, "multiple XREAL inputs");
        System.out.println("UsbMicrophoneSelectorTest passed");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
