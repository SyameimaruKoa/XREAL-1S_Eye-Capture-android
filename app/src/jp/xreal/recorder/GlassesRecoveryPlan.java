package jp.xreal.recorder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Recovery decisions for files created by this app's interrupted preparation. */
final class GlassesRecoveryPlan {
    static final String ABSENT = "absent";
    static final String UNSAFE = "unsafe";

    private GlassesRecoveryPlan() { }

    static String[] commands(boolean owned, String original, String backup,
            String candidate, String restore) throws IOException {
        if (!owned || UNSAFE.equals(original) || UNSAFE.equals(backup)
                || UNSAFE.equals(candidate) || UNSAFE.equals(restore)) {
            throw new IOException("復旧対象の所有またはファイルの種類を確認できません");
        }
        if (!GlassesStartupPlan.ORIGINAL_HASH.equals(original)
                && !GlassesStartupPlan.CANDIDATE_HASH.equals(original)) {
            throw new IOException("本体の起動設定が既知の状態と異なります");
        }
        if (!ABSENT.equals(backup) && !GlassesStartupPlan.ORIGINAL_HASH.equals(backup)) {
            throw new IOException("バックアップが既知の原本と一致しません");
        }
        if (!ABSENT.equals(restore) && !GlassesStartupPlan.ORIGINAL_HASH.equals(restore)) {
            throw new IOException("復元途中のファイルが既知の原本と一致しません");
        }
        List<String> commands = new ArrayList<>();
        if (GlassesStartupPlan.CANDIDATE_HASH.equals(original)) {
            if (ABSENT.equals(backup)) {
                throw new IOException("原本のバックアップがありません");
            }
            if (!ABSENT.equals(restore)) {
                commands.add(removeRegular(GlassesStartupPlan.RESTORE));
            }
            commands.add("cp -p " + GlassesStartupPlan.BACKUP + " "
                    + GlassesStartupPlan.RESTORE + " && cmp -s " + GlassesStartupPlan.BACKUP
                    + " " + GlassesStartupPlan.RESTORE + success());
            commands.add("mv " + GlassesStartupPlan.RESTORE + " " + GlassesStartupPlan.ORIGINAL
                    + " && cmp -s " + GlassesStartupPlan.ORIGINAL + " "
                    + GlassesStartupPlan.BACKUP + success());
        } else if (!ABSENT.equals(restore)) {
            commands.add(removeRegular(GlassesStartupPlan.RESTORE));
        }
        if (!ABSENT.equals(candidate)) {
            commands.add(removeRegular(GlassesStartupPlan.CANDIDATE));
        }
        if (!ABSENT.equals(backup)) {
            commands.add("cmp -s " + GlassesStartupPlan.ORIGINAL + " " + GlassesStartupPlan.BACKUP
                    + " && " + removeRegular(GlassesStartupPlan.BACKUP));
        }
        return commands.toArray(new String[0]);
    }

    private static String removeRegular(String path) {
        return "test ! -L " + path + " && test -f " + path + " && rm " + path + success();
    }

    private static String success() {
        return " && echo " + GlassesStartupPlan.MARKER;
    }
}
