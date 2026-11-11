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

            String state = path + ".state";
            String phase = path + ".phase";
            String mock = "printf '\\000\\005\\000\\000\\320\\002\\000\\000\\036\\000\\000\\000' > "
                    + state + "; "
                    + "pidof() { echo 123; }; "
                    + "readlink() { echo /usrdata/pilot/bin/pilot; }; "
                    + "sha256sum() { cat >/dev/null; }; "
                    + "awk() { case \"$*\" in *maps*) echo 10000000-10001000;; *) echo 42;; esac; }; "
                    + "dd() { case \"$*\" in "
                    + "*of=*) if [ ! -e " + phase + " ]; then "
                    + "printf '\\100\\006\\000\\000\\320\\002\\000\\000\\036\\000\\000\\000' > "
                    + state + "; : > " + phase + "; cat >/dev/null; else cat > " + state + "; fi;; "
                    + "*bs=8*) printf '\\310\\161\\264\\021\\000\\000\\000\\000';; "
                    + "*) cat " + state + ";; esac; }; ";
            String rollback = shell(mock + "( " + GlassesLivePlan.command(true) + " ); "
                    + "od -An -tu4 " + state);
            require(rollback.contains(GlassesLivePlan.ROLLED_BACK), "partial write was not rolled back");
            require(rollback.matches("(?s).*1280\\s+720\\s+30\\s*$"), "original values not restored");

            shell("rm -f " + phase);
            String changedProcess = mock.replace(
                    "readlink() { echo /usrdata/pilot/bin/pilot; }; ",
                    "readlink() { if [ -e " + phase + " ]; then echo /other; "
                            + "else echo /usrdata/pilot/bin/pilot; fi; }; ");
            String uncertain = shell(changedProcess + "( " + GlassesLivePlan.command(true) + " ); "
                    + "od -An -tu4 " + state);
            require(uncertain.contains(GlassesLivePlan.UNCERTAIN), "changed process was not rejected");
            require(uncertain.matches("(?s).*1600\\s+720\\s+30\\s*$"),
                    "rollback wrote to a process after identity changed");

            shell("rm -f " + phase);
            String rollbackFails = mock.replace("else cat > " + state + "; fi;;",
                    "else cat >/dev/null; fi;;");
            String failedRecovery = shell(rollbackFails + "( " + GlassesLivePlan.command(true)
                    + " ); od -An -tu4 " + state);
            require(failedRecovery.contains(GlassesLivePlan.UNCERTAIN),
                    "failed rollback must leave the result uncertain");
            require(failedRecovery.matches("(?s).*1600\\s+720\\s+30\\s*$"),
                    "test did not preserve the injected partial value");

            shell("rm -f " + phase);
            String unknownMode = mock.replace("*) cat " + state + ";; esac; }; ",
                    "*) printf '\\347\\003\\000\\000\\320\\002\\000\\000\\036\\000\\000\\000';; esac; }; ");
            String rejected = shell(unknownMode + "( " + GlassesLivePlan.command(true)
                    + " ); if [ -e " + phase + " ]; then echo wrote; else echo no-write; fi");
            require(rejected.contains("no-write") && !rejected.contains("xreal_live_mode_ok"),
                    "unknown starting mode was not rejected");
        } finally {
            shell("rm -f " + path + " " + path + ".target " + path + ".state " + path + ".phase");
        }
    }
}
