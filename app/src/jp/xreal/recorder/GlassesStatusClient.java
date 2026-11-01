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
    private static final String EXPECTED_SCRIPT_HASH =
            "fb392f7b4c32bf37230e0e4e0f9ae2ce8a40af543908896d19ea18d4a27372cc";
    private static final byte[] STATUS_COMMAND = (
            "sha256sum /etc/services/pilot/run; "
                    + "test -e /tmp/sdk_global.json && echo config_present || echo config_absent\0")
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
        drain();
        GlassesCommandProtocol.Reply reply = transact(STATUS_COMMAND);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < 8; i++) {
            output.write(reply.payload, 0, reply.payload.length);
            if (reply.status == 0) {
                drain();
                String value = new String(output.toByteArray(), StandardCharsets.US_ASCII);
                if (!value.startsWith(EXPECTED_SCRIPT_HASH + "  /etc/services/pilot/run\n")) {
                    return "本体の起動設定が既知の状態と異なります";
                }
                if (value.contains("\nconfig_absent\n")) {
                    return "本体は通常設定です";
                }
                if (value.contains("\nconfig_present\n")) {
                    return "本体に一時設定があります。内容は未確認です";
                }
                throw new IOException("本体の設定状態を確認できません");
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
