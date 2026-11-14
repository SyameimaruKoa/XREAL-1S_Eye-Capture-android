package jp.xreal.recorder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Builds the guarded, firmware-specific live camera mode command. */
final class GlassesLivePlan {
    static final String DRIVER_HASH =
            "b3a27a8de7776d294cedd8f8b101be105691c0fe84c5f8ecd033a019a94c8386";
    static final String DRIVER = "/usrdata/pilot/bin/libnr_driver.so";
    static final String SUCCESS = "xreal_live_mode_ok";
    static final String ROLLED_BACK = "xreal_live_mode_rolled_back";
    static final String UNCERTAIN = "xreal_live_mode_uncertain";

    private GlassesLivePlan() { }

    static String[] stageCommands(boolean sixtyFps, String token) {
        return stageScriptCommands(command(sixtyFps), token);
    }

    static String[] stageScriptCommands(String script, String token) {
        String path = temporaryPath(token);
        List<String> commands = new ArrayList<>();
        commands.add("umask 077; set -C; printf '%s\\n' " + quote(ownerLine(token))
                + " > " + path + " && echo xreal_live_stage_ok");
        for (int start = 0; start < script.length(); start += 250) {
            String chunk = script.substring(start, Math.min(start + 250, script.length()));
            commands.add("printf '%s' " + quote(chunk) + " >> " + path
                    + " && echo xreal_live_stage_ok");
        }
        return commands.toArray(new String[0]);
    }

    static String executeCommand(boolean sixtyFps, String token) {
        return executeScriptCommand(command(sixtyFps), token);
    }

    static String executeScriptCommand(String script, String token) {
        String path = temporaryPath(token);
        return "echo '" + sha256(ownedScript(script, token)) + "  " + path
                + "' | sha256sum -c >/dev/null && sh " + path + "; " + cleanupCommand(token);
    }

    static String readCommand() {
        String change = command(true);
        int index = change.indexOf("current=$(dd");
        if (index < 0) {
            throw new IllegalStateException("Live mode guard missing");
        }
        return change.substring(0, index)
                + "dd if=/proc/$p/mem bs=4 skip=$index count=3 2>/dev/null | od -An -tu4 | xargs";
    }

    static String cleanupCommand(String token) {
        String path = temporaryPath(token);
        return "if [ ! -e " + path + " ] && [ ! -L " + path + " ]; then :; "
                + "elif [ ! -L " + path + " ] && [ \"$(head -n 1 " + path + ")\" = "
                + quote(ownerLine(token)) + " ]; then rm -f " + path + "; else exit 1; fi";
    }

    private static String ownerLine(String token) {
        temporaryPath(token);
        return "# xreal-live:" + token;
    }

    private static String ownedScript(String script, String token) {
        return ownerLine(token) + "\n" + script;
    }

    private static String temporaryPath(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid temporary token");
        }
        return "/tmp/xreal-live-" + token;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String command(boolean sixtyFps) {
        return command(sixtyFps ? "1280 720 30" : "1600 1200 60",
                sixtyFps ? "1600 1200 60" : "1280 720 30");
    }

    private static String modeBytes(String mode) {
        if ("1280 720 30".equals(mode)) {
            return "\\000\\005\\000\\000\\320\\002\\000\\000\\036\\000\\000\\000";
        }
        if ("1600 1200 30".equals(mode)) {
            return "\\100\\006\\000\\000\\260\\004\\000\\000\\036\\000\\000\\000";
        }
        if ("1600 1200 60".equals(mode)) {
            return "\\100\\006\\000\\000\\260\\004\\000\\000\\074\\000\\000\\000";
        }
        throw new IllegalArgumentException("Unsupported live mode");
    }

    static String command(String expected, String desired) {
        String bytes = modeBytes(desired);
        String originalBytes = modeBytes(expected);
        return "set -- $(pidof pilot); [ $# -eq 1 ] || exit 1; p=$1; "
                + "[ \"$(readlink /proc/$p/exe)\" = /usrdata/pilot/bin/pilot ] || exit 1; "
                + "started=$(awk '{print $22}' /proc/$p/stat); [ -n \"$started\" ] || exit 1; "
                + "echo '" + DRIVER_HASH + "  " + DRIVER + "' | sha256sum -c >/dev/null || exit 1; "
                + "base=$(awk '$2 == \"r-xp\" && $3 == \"00000000\" && $6 == \""
                + DRIVER + "\" {print $1}' /proc/$p/maps); "
                + "case \"$base\" in *' '*|'') exit 1;; esac; base=${base%%-*}; "
                + "object=$((0x$base+0x1ca5090)); pointer=$((0x$base+0x1b471c8)); "
                + "actual=$(dd if=/proc/$p/mem bs=8 skip=$((object/8)) count=1 2>/dev/null "
                + "| od -An -tx8 | xargs); "
                + "[ \"$actual\" = \"$(printf '%016x' $pointer)\" ] || exit 1; "
                + "index=$(((object+0x1ac)/4)); "
                + "current=$(dd if=/proc/$p/mem bs=4 skip=$index count=3 2>/dev/null "
                + "| od -An -tu4 | xargs); "
                + "[ \"$current\" = '" + expected + "' ] || exit 1; "
                + "[ \"$(readlink /proc/$p/exe)\" = /usrdata/pilot/bin/pilot ] || exit 1; "
                + "[ \"$(awk '{print $22}' /proc/$p/stat)\" = \"$started\" ] || exit 1; "
                + "printf '" + bytes + "' | dd of=/proc/$p/mem bs=4 seek=$index count=3 "
                + "conv=notrunc 2>/dev/null; "
                + "current=$(dd if=/proc/$p/mem bs=4 skip=$index count=3 2>/dev/null "
                + "| od -An -tu4 | xargs); "
                + "if [ \"$current\" = '" + desired + "' ]; then echo " + SUCCESS + "; exit 0; fi; "
                + "[ \"$(readlink /proc/$p/exe)\" = /usrdata/pilot/bin/pilot ] "
                + "&& [ \"$(awk '{print $22}' /proc/$p/stat)\" = \"$started\" ] "
                + "&& [ \"$(dd if=/proc/$p/mem bs=8 skip=$((object/8)) count=1 2>/dev/null "
                + "| od -An -tx8 | xargs)\" = \"$(printf '%016x' $pointer)\" ] "
                + "|| { echo " + UNCERTAIN + "; exit 1; }; "
                + "printf '" + originalBytes + "' | dd of=/proc/$p/mem bs=4 seek=$index count=3 "
                + "conv=notrunc 2>/dev/null; "
                + "current=$(dd if=/proc/$p/mem bs=4 skip=$index count=3 2>/dev/null "
                + "| od -An -tu4 | xargs); "
                + "if [ \"$current\" = '" + expected + "' ]; then echo " + ROLLED_BACK
                + "; else echo " + UNCERTAIN + "; fi";
    }
}
