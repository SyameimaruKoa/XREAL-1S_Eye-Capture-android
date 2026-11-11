package jp.xreal.recorder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Exercises generated staging commands using harmless scripts in a local shell. */
public final class GlassesLiveStagingIntegrationTest {
    private static String shell(String command) throws Exception {
        Process process = new ProcessBuilder("bash", "-s").redirectErrorStream(true).start();
        process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("shell timed out");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("shell failed: " + output.toString("UTF-8"));
        }
        return output.toString("UTF-8");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        String token = UUID.randomUUID().toString().replace("-", "");
        String path = "/tmp/xreal-live-" + token;
        String script = "printf 'execution-marker\\n'";
        String stages = String.join(" && ", GlassesLivePlan.stageScriptCommands(script, token));
        String run = GlassesLivePlan.executeScriptCommand(script, token);
        try {
            String normal = shell(stages + " && " + run + " && test ! -e " + path);
            require(normal.contains("execution-marker"), "verified script did not run");

            String tampered = shell(stages + " && printf x >> " + path + " && " + run
                    + " && test ! -e " + path);
            require(!tampered.contains("execution-marker"), "tampered script ran");

            String longScript = script + " # " + new String(new char[600]).replace('\0', 'x');
            String[] parts = GlassesLivePlan.stageScriptCommands(longScript, token);
            require(parts.length > 2, "interrupted script must have remaining stages");
            shell(parts[0] + " && " + parts[1] + " && " + GlassesLivePlan.cleanupCommand(token)
                    + " && test ! -e " + path);

            String collision = shell("printf foreign > " + path + "; if "
                    + GlassesLivePlan.stageScriptCommands(script, token)[0]
                    + "; then echo overwritten; else cat " + path + "; fi");
            require(collision.contains("foreign") && !collision.contains("overwritten"),
                    "existing file was overwritten");
            String cleanup = shell("if ( " + GlassesLivePlan.cleanupCommand(token)
                    + " ); then echo removed; else cat " + path + "; fi");
            require(cleanup.contains("foreign") && !cleanup.contains("removed"),
                    "unowned file was removed");
            shell("rm -f " + path + "; printf protected > " + path + ".target; ln -s "
                    + path + ".target " + path);
            String symlink = shell("if ( " + GlassesLivePlan.cleanupCommand(token)
                    + " ); then echo removed; else cat " + path + "; fi");
            require(symlink.contains("protected") && !symlink.contains("removed"),
                    "symlink or its target was removed");
        } finally {
            shell("rm -f " + path + " " + path + ".target");
        }
    }
}
