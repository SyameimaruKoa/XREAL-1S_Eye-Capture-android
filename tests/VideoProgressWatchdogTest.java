package jp.xreal.recorder;

public final class VideoProgressWatchdogTest {
    public static void main(String[] args) {
        require(!VideoProgressWatchdog.isStalled(false, false, 0, 0, 1000, 11000, 0),
                "idle must not warn");
        require(!VideoProgressWatchdog.isStalled(true, true, 1, 100, 0, 20000, 5000000000L),
                "stopping must not warn");
        require(!VideoProgressWatchdog.isStalled(true, false, 0, 0, 1000, 10999, 0),
                "initial warning must wait ten seconds");
        require(VideoProgressWatchdog.isStalled(true, false, 0, 0, 1000, 11000, 0),
                "initial warning at ten seconds");
        require(!VideoProgressWatchdog.isStalled(true, false, 1, 1000000000L, 0, 0, 3999999999L),
                "received frame must remain valid for three seconds");
        require(VideoProgressWatchdog.isStalled(true, false, 1, 1000000000L, 0, 0, 4000000000L),
                "missing frame warning at three seconds");
        require(!VideoProgressWatchdog.isStalled(true, false, 2, 4000000000L, 0, 0, 4000000000L),
                "new frame clears warning");
        System.out.println("VideoProgressWatchdogTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
