package jp.xreal.recorder;

public final class TcpTimestampRecoveryTest {
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        TcpTimestampRecovery clock60 = new TcpTimestampRecovery();
        require(!clock60.canEstimate(), "no estimate before observing cadence");
        for (int i = 0; i < 8; i++) {
            clock60.observeStep(16_840_000L);
        }
        clock60.observeStep(50_500_000L);
        clock60.observeStep(0);
        long estimated60 = clock60.estimateDuplicate(1_000_000_000L);
        require(estimated60 == 1_008_420_000L, "60 fps duplicate estimate");
        long following60 = 1_033_680_000L;
        require(estimated60 > 1_000_000_000L && estimated60 < following60,
                "estimate remains between observed timestamps");

        TcpTimestampRecovery clock30 = new TcpTimestampRecovery();
        for (int i = 0; i < 8; i++) {
            clock30.observeStep(33_500_000L);
        }
        clock30.observeStep(16_840_000L);
        long estimated30 = clock30.estimateDuplicate(2_000_000_000L);
        require(estimated30 > 2_014_000_000L && estimated30 < 2_017_000_000L,
                "30 fps estimate remains bounded after a short step");
        System.out.println("TcpTimestampRecoveryTest passed");
    }
}
