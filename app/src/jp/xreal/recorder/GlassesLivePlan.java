package jp.xreal.recorder;

/** Builds the guarded, firmware-specific live camera mode command. */
final class GlassesLivePlan {
    static final String DRIVER_HASH =
            "b3a27a8de7776d294cedd8f8b101be105691c0fe84c5f8ecd033a019a94c8386";
    static final String DRIVER = "/usrdata/pilot/bin/libnr_driver.so";
    static final String SUCCESS = "xreal_live_mode_ok";

    private GlassesLivePlan() { }

    static String command(boolean sixtyFps) {
        String expected = sixtyFps ? "1280 720 30" : "1600 1200 60";
        String desired = sixtyFps ? "1600 1200 60" : "1280 720 30";
        String bytes = sixtyFps
                ? "\\100\\006\\000\\000\\260\\004\\000\\000\\074\\000\\000\\000"
                : "\\000\\005\\000\\000\\320\\002\\000\\000\\036\\000\\000\\000";
        return "set -- $(pidof pilot); [ $# -eq 1 ] || exit 1; p=$1; "
                + "[ \"$(readlink /proc/$p/exe)\" = /usrdata/pilot/bin/pilot ] || exit 1; "
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
                + "printf '" + bytes + "' | dd of=/proc/$p/mem bs=4 seek=$index count=3 "
                + "conv=notrunc 2>/dev/null || exit 1; "
                + "current=$(dd if=/proc/$p/mem bs=4 skip=$index count=3 2>/dev/null "
                + "| od -An -tu4 | xargs); "
                + "[ \"$current\" = '" + desired + "' ] && echo " + SUCCESS;
    }
}
