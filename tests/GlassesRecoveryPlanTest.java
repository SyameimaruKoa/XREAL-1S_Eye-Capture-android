package jp.xreal.recorder;

import java.io.IOException;

public final class GlassesRecoveryPlanTest {
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void reject(boolean owned, String original, String backup,
            String candidate, String restore) throws Exception {
        try {
            GlassesRecoveryPlan.commands(owned, original, backup, candidate, restore);
            throw new AssertionError("Unsafe recovery accepted");
        } catch (IOException expected) {
            // Unknown or unowned files must not be changed.
        }
    }

    public static void main(String[] args) throws Exception {
        String orig = GlassesStartupPlan.ORIGINAL_HASH;
        String trial = GlassesStartupPlan.CANDIDATE_HASH;
        String absent = GlassesRecoveryPlan.ABSENT;
        String[] partial = GlassesRecoveryPlan.commands(true, orig, orig, "partial", absent);
        require(partial.length == 2, "owned partial candidate and backup are cleaned");
        require(partial[0].contains(GlassesStartupPlan.CANDIDATE), "candidate cleanup first");
        require(partial[1].contains("cmp -s " + GlassesStartupPlan.ORIGINAL),
                "backup cleanup rechecks the original");
        String[] committed = GlassesRecoveryPlan.commands(true, trial, orig, absent, absent);
        require(committed.length == 3, "committed trial is restored before backup cleanup");
        require(committed[1].startsWith("mv "), "atomic restore after copy verification");
        for (String command : committed) {
            require(command.length() <= 251, "command exceeds tested device payload size");
        }
        reject(false, orig, orig, "partial", absent);
        reject(true, "unknown", orig, absent, absent);
        reject(true, trial, absent, absent, absent);
        reject(true, orig, "unknown", absent, absent);
        reject(true, orig, orig, GlassesRecoveryPlan.UNSAFE, absent);
        reject(true, orig, orig, absent, "unknown");
        System.out.println("GlassesRecoveryPlanTest passed");
    }
}
