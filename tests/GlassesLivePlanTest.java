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
            require(stages[0].contains("# xreal-live:" + token), "temporary file has an ownership marker");
            String run = GlassesLivePlan.executeCommand(mode, token);
            require(run.contains("sha256sum -c") && run.contains("&& sh "),
                    "script must be verified before execution");
            require(run.endsWith(GlassesLivePlan.cleanupCommand(token)),
                    "script must be removed after execution");
            require(GlassesLivePlan.cleanupCommand(token).contains("head -n 1")
                    && GlassesLivePlan.cleanupCommand(token).contains("! -L"),
                    "cleanup must verify the marker and reject symlinks");
        }
        require(!GlassesLivePlan.executeCommand(true, token)
                .equals(GlassesLivePlan.executeCommand(false, token)), "mode hashes differ");
        String read = GlassesLivePlan.readCommand();
        require(read.contains(GlassesLivePlan.DRIVER_HASH) && read.contains("od -An -tu4"),
                "mode query includes the firmware guard and reads values");
        require(!read.contains("of=/proc/") && !read.contains("conv=notrunc"),
                "mode query must not write process memory");
        String[] modes = {"1280 720 30", "1600 1200 30", "1600 1200 60"};
        for (String from : modes) {
            for (String to : modes) {
                String script = GlassesLivePlan.command(from, to);
                require(script.contains("[ \"$current\" = '" + from + "' ]"),
                        "source mode must be guarded");
                require(script.contains("[ \"$current\" = '" + to + "' ]"),
                        "destination mode must be verified");
                for (String stage : GlassesLivePlan.stageScriptCommands(script, token)) {
                    require(stage.getBytes(StandardCharsets.US_ASCII).length < 500,
                            "mode stage exceeds verified command length");
                }
            }
        }
        try {
            GlassesLivePlan.command("1280 720 30", "1600 1200 30; reboot");
            throw new AssertionError("unsupported mode accepted");
        } catch (IllegalArgumentException expected) {
            // Only explicitly supported modes may enter the shell command.
        }
        try {
            GlassesLivePlan.stageCommands(true, "../invalid");
            throw new AssertionError("invalid path token accepted");
        } catch (IllegalArgumentException expected) {
            // A token is always generated locally and cannot include path syntax.
        }
    }
}
