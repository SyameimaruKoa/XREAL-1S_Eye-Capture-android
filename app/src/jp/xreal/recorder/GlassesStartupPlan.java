package jp.xreal.recorder;

import java.util.ArrayList;
import java.util.List;

/** Fixed one-boot configuration plan for the verified firmware; does not execute it. */
final class GlassesStartupPlan {
    static final String ORIGINAL_HASH =
            "fb392f7b4c32bf37230e0e4e0f9ae2ce8a40af543908896d19ea18d4a27372cc";
    static final String CANDIDATE_HASH =
            "fc24d3b49b2e157249139a4172ebce78f2ffea66dc50c152ad7df54c417dedbd";
    static final String CONFIG_HASH =
            "57a1e0727a567459b8f7cca64edd5eafc0389b7579bdbe33451cefad4e5d0b30";
    static final String ORIGINAL = "/etc/services/pilot/run";
    static final String BACKUP = "/etc/services/pilot/run.codex-once-backup";
    static final String CANDIDATE = "/etc/services/pilot/run.codex-once-test";
    static final String RESTORE = "/etc/services/pilot/run.codex-once-restore";
    static final String CONFIG = "/tmp/sdk_global.json";
    static final String MARKER = "codex_step_ok";

    private static final String[] BOOT_LINES = {
            "if cp -p " + BACKUP + " " + RESTORE + " &&",
            "    cmp -s " + BACKUP + " " + RESTORE + " &&",
            "    mv " + RESTORE + " " + ORIGINAL + " &&",
            "    cmp -s " + BACKUP + " " + ORIGINAL + "; then",
            "    rm " + BACKUP,
            "    echo '{\"driver\":{\"video_bit_rate\":33554432,\"video_fps\":60,"
                    + "\"video_width\":1600,\"video_height\":1200}}' > " + CONFIG,
            "fi",
            "exec /usrdata/pilot/bin/pilot"
    };

    private GlassesStartupPlan() { }

    static String[] prepareCommands() {
        List<String> commands = new ArrayList<>();
        commands.add("test ! -e " + BACKUP + " && test ! -e " + CANDIDATE
                + " && test ! -e " + RESTORE + " && test ! -e " + CONFIG + success());
        commands.add(hashCheck(ORIGINAL_HASH, ORIGINAL) + success());
        commands.add("cp -p " + ORIGINAL + " " + BACKUP + " && "
                + hashCheck(ORIGINAL_HASH, BACKUP) + success());
        commands.add("head -n -1 " + BACKUP + " > " + CANDIDATE + success());
        for (String line : BOOT_LINES) {
            commands.add("printf '%s\\n' " + quote(line) + " >> " + CANDIDATE + success());
        }
        commands.add("sh -n " + CANDIDATE + " && " + hashCheck(CANDIDATE_HASH, CANDIDATE)
                + success());
        return commands.toArray(new String[0]);
    }

    static String commitCommand() {
        return "cmp -s " + ORIGINAL + " " + BACKUP + " && test ! -e " + CONFIG
                + " && chmod 755 " + CANDIDATE
                + " && mv " + CANDIDATE + " " + ORIGINAL + success();
    }

    private static String hashCheck(String hash, String path) {
        return "echo '" + hash + "  " + path + "' | sha256sum -c >/dev/null";
    }

    private static String success() {
        return " && echo " + MARKER;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
