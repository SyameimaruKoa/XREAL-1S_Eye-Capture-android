package jp.xreal.recorder;

import java.util.Locale;

/** Selects an identifiable XREAL input or a single unambiguous USB input. */
final class UsbMicrophoneSelector {
    static final int NONE = -1;
    static final int AMBIGUOUS = -2;

    static int select(String[] names) {
        int xreal = NONE;
        for (int i = 0; i < names.length; i++) {
            if (names[i] != null && names[i].toLowerCase(Locale.ROOT).contains("xreal")) {
                if (xreal != NONE) {
                    return AMBIGUOUS;
                }
                xreal = i;
            }
        }
        if (xreal != NONE) {
            return xreal;
        }
        if (names.length == 0) {
            return NONE;
        }
        return names.length == 1 ? 0 : AMBIGUOUS;
    }
}
