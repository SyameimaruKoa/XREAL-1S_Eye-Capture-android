package jp.xreal.recorder;

public final class TcpDriftGateTest {
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        TcpDriftGate gate = new TcpDriftGate();
        require(!gate.shouldReanchor(-6_500_000_000L, 10_000_000_000L),
                "first delayed frame must not reset the camera clock");
        require(!gate.shouldReanchor(-1_000_000_000L, 10_100_000_000L),
                "a burst must retain its camera times");
        require(!gate.shouldReanchor(0, 10_200_000_000L), "recovered offset");
        require(!gate.shouldReanchor(200_000_000L, 11_000_000_000L), "new drift");
        require(!gate.shouldReanchor(200_000_000L, 13_999_999_999L), "early drift");
        require(gate.shouldReanchor(200_000_000L, 14_000_000_000L), "persistent drift");
        require(!gate.shouldReanchor(-200_000_000L, 15_000_000_000L), "new direction");
        require(!gate.shouldReanchor(200_000_000L, 18_000_000_000L), "direction changed");
        gate.reset();
        require(!gate.shouldReanchor(200_000_000L, 22_000_000_000L), "explicit reset");
        System.out.println("TcpDriftGateTest passed");
    }
}
