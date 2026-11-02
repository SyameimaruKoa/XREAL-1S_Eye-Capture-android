package jp.xreal.recorder;

import java.nio.charset.StandardCharsets;

public final class GlassesStartupPlanTest {
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        String[] commands = GlassesStartupPlan.prepareCommands();
        require(commands.length > 1, "preparation has guarded stages");
        for (String command : commands) {
            require(command.getBytes(StandardCharsets.US_ASCII).length <= 251,
                    "command exceeds the tested device payload size");
            require(command.endsWith(" && echo " + GlassesStartupPlan.MARKER),
                    "success marker requires the previous operation to succeed");
            require(!command.contains("reboot"), "preparation must not reboot");
        }
        require(commands[0].contains("test ! -e " + GlassesStartupPlan.CONFIG),
                "existing settings are preserved");
        require(commands[0].contains("test ! -e " + GlassesStartupPlan.BACKUP),
                "existing backups are preserved");
        require(commands[commands.length - 1].contains(GlassesStartupPlan.CANDIDATE_HASH),
                "candidate is checked before installation");
        String commit = GlassesStartupPlan.commitCommand();
        require(commit.getBytes(StandardCharsets.US_ASCII).length <= 251,
                "commit exceeds the tested device payload size");
        require(commit.contains("cmp -s " + GlassesStartupPlan.ORIGINAL + " "
                + GlassesStartupPlan.BACKUP), "original must still match backup");
        require(commit.endsWith(" && echo " + GlassesStartupPlan.MARKER),
                "commit success marker requires successful replacement");
        System.out.println("GlassesStartupPlanTest passed");
    }
}
