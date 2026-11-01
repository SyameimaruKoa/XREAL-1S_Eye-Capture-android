package jp.xreal.recorder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class GlassesCommandProtocolTest {
    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(byte[] data, int sequence, int command) throws Exception {
        try {
            GlassesCommandProtocol.readReply(new ByteArrayInputStream(data), sequence, command);
            throw new AssertionError("Invalid response accepted");
        } catch (IOException expected) {
            // Expected for malformed, truncated or unrelated frames.
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] request = GlassesCommandProtocol.request(1, 0x700b,
                new byte[]{'i', 'd', 0});
        require(Arrays.equals(request, hex("fd3e0b7800140001000000000000000b700000000000696400")),
                "request framing and checksum");

        byte[] response = hex("fdd11ecd8a1d0001000000b7f1e7ff0b70000000000000636f64657870726f62656e0000");
        InputStream fragmented = new ByteArrayInputStream(response) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 1));
            }
        };
        GlassesCommandProtocol.Reply reply = GlassesCommandProtocol.readReply(fragmented, 1, 0x700b);
        require(reply.status == 0, "response status");
        require(new String(reply.payload, StandardCharsets.US_ASCII).equals("codexproben"),
                "response payload");
        expectFailure(Arrays.copyOf(response, response.length - 1), 1, 0x700b);
        byte[] corrupted = response.clone();
        corrupted[24] ^= 1;
        expectFailure(corrupted, 1, 0x700b);
        expectFailure(response, 2, 0x700b);
        expectFailure(response, 1, 0x700a);
        System.out.println("GlassesCommandProtocolTest passed");
    }
}
