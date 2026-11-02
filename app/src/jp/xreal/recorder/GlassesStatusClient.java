package jp.xreal.recorder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Reads limited diagnostic state from the glasses over its USB network. */
final class GlassesStatusClient {
    private static final int COMMAND = 0x700b;
    private static final byte[] STATUS_COMMAND = (
            "sha256sum /etc/services/pilot/run; "
                    + "if test -e /tmp/sdk_global.json; then sha256sum /tmp/sdk_global.json; "
                    + "else echo config_absent; fi\0")
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PENDING_COMMAND = (
            "if test -e " + GlassesStartupPlan.BACKUP + " || test -e "
                    + GlassesStartupPlan.CANDIDATE + " || test -e " + GlassesStartupPlan.RESTORE
                    + "; then echo pending; else echo clear; fi\0")
            .getBytes(StandardCharsets.US_ASCII);
    private final Network network;
    private int sequence;

    GlassesStatusClient(Context context) throws IOException {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            throw new IOException("ネットワークを確認できません");
        }
        Network found = null;
        for (Network candidate : manager.getAllNetworks()) {
            LinkProperties properties = manager.getLinkProperties(candidate);
            if (properties == null || properties.getInterfaceName() == null
                    || !properties.getInterfaceName().startsWith("eth")) {
                continue;
            }
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address
                        && "169.254.2.10".equals(address.getAddress().getHostAddress())) {
                    if (found != null && !found.equals(candidate)) {
                        throw new IOException("接続先を一意に確認できません");
                    }
                    found = candidate;
                }
            }
        }
        if (found == null) {
            throw new IOException("XREAL の USB ネットワークが見つかりません");
        }
        network = found;
    }

    String readStatus() throws IOException {
        if (!"clear\n".equals(execute(PENDING_COMMAND))) {
            return "本体に未完了の準備があります。状態の確認が必要です";
        }
        String value = execute(STATUS_COMMAND);
        if (!value.startsWith(GlassesStartupPlan.ORIGINAL_HASH + "  /etc/services/pilot/run\n")) {
            return "本体の起動設定が既知の状態と異なります";
        }
        if (value.contains("\nconfig_absent\n")) {
            return "本体は通常設定です";
        }
        if (value.contains("\n" + GlassesStartupPlan.CONFIG_HASH + "  /tmp/sdk_global.json\n")) {
            return "本体は TCP 1600×1200 / 60 fps の試験設定です";
        }
        return "本体に未確認の一時設定があります";
    }

    interface OwnershipMarker {
        void markOwned() throws IOException;
    }

    void prepare60(OwnershipMarker ownership) throws IOException {
        if (!"本体は通常設定です".equals(readStatus())) {
            throw new IOException("本体が通常設定ではありません");
        }
        String[] commands = GlassesStartupPlan.prepareCommands();
        for (int i = 0; i < commands.length; i++) {
            if (i == 2) {
                ownership.markOwned();
            }
            executeStep(commands[i]);
        }
        executeStep(GlassesStartupPlan.commitCommand());
    }

    String recoverOwnedPreparation(boolean owned) throws IOException {
        String original = inspect(GlassesStartupPlan.ORIGINAL);
        String backup = inspect(GlassesStartupPlan.BACKUP);
        String candidate = inspect(GlassesStartupPlan.CANDIDATE);
        String restore = inspect(GlassesStartupPlan.RESTORE);
        String[] commands = GlassesRecoveryPlan.commands(owned, original, backup, candidate, restore);
        for (String command : commands) {
            executeStep(command);
        }
        return readStatus();
    }

    private String inspect(String path) throws IOException {
        String command = "p=" + path + "; if test -L \"$p\"; then echo unsafe; "
                + "elif test -f \"$p\"; then sha256sum \"$p\"; elif test -e \"$p\"; "
                + "then echo unsafe; else echo absent; fi";
        String result = execute((command + "\0").getBytes(StandardCharsets.US_ASCII));
        if ("absent\n".equals(result)) {
            return GlassesRecoveryPlan.ABSENT;
        }
        if ("unsafe\n".equals(result)) {
            return GlassesRecoveryPlan.UNSAFE;
        }
        String suffix = "  " + path + "\n";
        if (!result.endsWith(suffix) || result.length() != 64 + suffix.length()) {
            throw new IOException("復旧対象の情報を確認できません");
        }
        return result.substring(0, 64);
    }

    void rebootGlasses() throws IOException {
        drain();
        GlassesCommandProtocol.Reply reply = transact("reboot\0".getBytes(StandardCharsets.US_ASCII));
        if (reply.status != 0) {
            throw new IOException("本体の再起動要求を確認できません");
        }
    }

    private void executeStep(String command) throws IOException {
        String result = execute((command + "\0").getBytes(StandardCharsets.US_ASCII));
        if (!(GlassesStartupPlan.MARKER + "\n").equals(result)) {
            throw new IOException("準備を完了できません。状態を確認してください");
        }
    }

    private String execute(byte[] command) throws IOException {
        drain();
        GlassesCommandProtocol.Reply reply = transact(command);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < 8; i++) {
            output.write(reply.payload, 0, reply.payload.length);
            if (reply.status == 0) {
                drain();
                return new String(output.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (reply.status != 250) {
                throw new IOException("本体の状態確認に失敗しました: " + reply.status);
            }
            reply = transact(new byte[0]);
        }
        throw new IOException("本体の応答が長すぎます");
    }

    private void drain() throws IOException {
        for (int i = 0; i < 8; i++) {
            GlassesCommandProtocol.Reply reply = transact(new byte[0]);
            if (reply.status == 1) {
                return;
            }
            if (reply.status != 250 && reply.status != 0) {
                throw new IOException("本体の応答状態を解除できません: " + reply.status);
            }
        }
        throw new IOException("本体の応答状態を解除できません");
    }

    private GlassesCommandProtocol.Reply transact(byte[] payload) throws IOException {
        int current = ++sequence;
        try (Socket socket = new Socket()) {
            network.bindSocket(socket);
            socket.connect(new InetSocketAddress("169.254.2.1", 50180), 5000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(GlassesCommandProtocol.request(current, COMMAND, payload));
            socket.getOutputStream().flush();
            return GlassesCommandProtocol.readReply(socket.getInputStream(), current, COMMAND);
        }
    }
}
