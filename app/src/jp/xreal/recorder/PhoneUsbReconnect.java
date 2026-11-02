package jp.xreal.recorder;

import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Device-specific, root-only USB-C reconnect verified on the SC-52B. */
final class PhoneUsbReconnect {
    private static final String PORT = "/sys/class/typec/port0/port_type";
    private static final String CHECK = "test \"$(id -u)\" = 0 && test -w " + PORT
            + " && grep -q '\\[dual\\]' " + PORT + " && echo xreal_usb_ready";
    private static final String RECONNECT = "trap 'echo dual > " + PORT + "' EXIT; "
            + "echo sink > " + PORT + "; sleep 5; echo source > " + PORT
            + "; sleep 5; echo dual > " + PORT + "; cat " + PORT;

    private PhoneUsbReconnect() { }

    static boolean isSupported() {
        return "SC-52B".equals(Build.MODEL);
    }

    static void checkAvailable() throws IOException, InterruptedException {
        if (!isSupported()) {
            throw new IOException("このスマートフォンの USB 再接続には対応していません");
        }
        String result = runRoot(CHECK, 60);
        if (!result.contains("xreal_usb_ready")) {
            throw new IOException("root 権限または USB ポートの状態を確認できません");
        }
    }

    static void reconnect() throws IOException, InterruptedException {
        String result = runRoot(RECONNECT, 70);
        if (!result.contains("[dual]")) {
            throw new IOException("USB ポートを通常状態に戻せたか確認できません");
        }
    }

    private static String runRoot(String command, int timeoutSeconds)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            // Do not kill a reconnect operation: its shell must finish restoring the port.
            throw new IOException("USB 操作の完了を確認できません。接続状態を確認してください");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1 && output.size() < 8192) {
                output.write(buffer, 0, read);
            }
        }
        if (process.exitValue() != 0) {
            throw new IOException("root 権限または USB ポートの操作に失敗しました");
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
