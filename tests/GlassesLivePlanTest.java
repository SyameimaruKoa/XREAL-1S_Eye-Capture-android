package jp.xreal.recorder;

import java.nio.charset.StandardCharsets;

public final class GlassesLivePlanTest {
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        String token = "0123456789abcdef0123456789abcdef";
        for (boolean mode : new boolean[] {false, true}) {
            String[] stages = GlassesLivePlan.stageCommands(mode, token);
            require(stages.length > 2, "command is split into stages");
            for (String stage : stages) {
                require(stage.getBytes(StandardCharsets.US_ASCII).length < 500,
                        "stage exceeds the verified command length");
            }
            require(stages[0].contains("set -C"), "creation must not overwrite a file");
            String run = GlassesLivePlan.executeCommand(mode, token);
            require(run.contains("sha256sum -c") && run.contains("&& sh "),
                    "script must be verified before execution");
            require(run.endsWith(GlassesLivePlan.cleanupCommand(token)),
                    "script must be removed after execution");
        }
        require(!GlassesLivePlan.executeCommand(true, token)
                .equals(GlassesLivePlan.executeCommand(false, token)), "mode hashes differ");
        try {
            GlassesLivePlan.stageCommands(true, "../invalid");
            throw new AssertionError("invalid path token accepted");
        } catch (IllegalArgumentException expected) {
            // A token is always generated locally and cannot include path syntax.
        }
    }
}
