package com.syameimarukoa.xreal_recorder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads telemetry from the glasses over the XrealLink RPC service on TCP 52999.
 *
 * <p>Wire format (verified on the real 1S with xrctl): a 6-byte header of a
 * big-endian message id and big-endian body length, followed by a 4-byte
 * transaction id and a protobuf-lite body. Responses echo the same header.</p>
 */
final class GlassesTelemetryClient {
    private static final String HOST = "169.254.2.1";
    private static final int PORT = 52999;
    private static final int MSG_TEMPERATURE = 0x2789;      // 10121 NRTemperatureGetValue
    private static final int MSG_WEARING = 0x273c;          // 10044 NRProximityGetWearingState
    private static final int MSG_BOOT_COUNT = 0x27ee;       // 10222 NRGlassesGetBootCount
    private static final int MSG_SW_VERSION = 0x271d;       // 10013 NRGlassesGetSWVersion
    private static final int TXID = 0x80000001;
    private static final int TIMEOUT_MS = 1500;

    private final Network network;
    private int sequence;

    GlassesTelemetryClient(Context context) throws IOException {
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

    /** Snapshot of readable glasses telemetry. Values are null when unavailable. */
    static final class Snapshot {
        Float temperatureC;
        Integer wearingState;
        Integer bootCount;
        String firmwareVersion;
    }

    Snapshot read() throws IOException {
        Snapshot s = new Snapshot();
        Socket socket = new Socket();
        try {
            network.bindSocket(socket);
            socket.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            s.temperatureC = readFloatField4(roundTrip(input, output, MSG_TEMPERATURE, new byte[]{0x08, 0x00}));
            s.wearingState = readVarintField2(roundTrip(input, output, MSG_WEARING, new byte[0]));
            s.bootCount = readVarintField2(roundTrip(input, output, MSG_BOOT_COUNT, new byte[0]));
            s.firmwareVersion = readStringField2(roundTrip(input, output, MSG_SW_VERSION, new byte[0]));
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
        return s;
    }

    /** One request/response; returns the protobuf body after the 6-byte header. */
    private byte[] roundTrip(InputStream input, OutputStream output, int messageId, byte[] body)
            throws IOException {
        byte[] packet = new byte[10 + body.length];
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort((short) messageId);
        // The length field counts the transaction id plus the body.
        buffer.putInt(4 + body.length);
        buffer.putInt(TXID + sequence);
        buffer.put(body);
        output.write(packet);
        output.flush();
        sequence = (sequence + 1) & 0x7fff;

        byte[] header = readExact(input, 6);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int responseId = Short.toUnsignedInt(h.getShort());
        int length = h.getInt();
        if (responseId != messageId || length < 4 || length > 4096) {
            throw new IOException("予期しないテレメトリ応答: id=" + responseId + " len=" + length);
        }
        byte[] payloadWithTxid = readExact(input, length);
        // Skip the echoed 4-byte transaction id; the rest is the protobuf body.
        byte[] responseBody = new byte[payloadWithTxid.length - 4];
        System.arraycopy(payloadWithTxid, 4, responseBody, 0, responseBody.length);
        return responseBody;
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int position = 0;
        while (position < length) {
            int count = input.read(result, position, length - position);
            if (count < 0) {
                throw new EOFException("Incomplete telemetry response");
            }
            if (count == 0) {
                throw new IOException("Telemetry response made no progress");
            }
            position += count;
        }
        return result;
    }

    /** Parses field 4 (nested) -> field 2 (fixed32 float): temperature. */
    private static Float readFloatField4(byte[] body) {
        Nested n = nestedField4(body);
        if (n == null) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(n.data).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int key = b.get() & 0xff;
            int field = key >> 3;
            int wire = key & 7;
            if (wire == 5) {
                float value = b.getFloat();
                if (field == 2) {
                    return value;
                }
            } else {
                return null;
            }
        }
        return null;
    }

    private static Integer readVarintField2(byte[] body) {
        Nested n = nestedField4(body);
        return n == null ? null : firstVarint(n.data);
    }

    private static String readStringField2(byte[] body) {
        Nested n = nestedField4(body);
        if (n == null) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(n.data).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int key = b.get() & 0xff;
            int field = key >> 3;
            int wire = key & 7;
            if (wire == 2) {
                int length = b.get() & 0xff;
                byte[] bytes = new byte[length];
                b.get(bytes);
                if (field == 2) {
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            } else if (wire == 0) {
                readVarint(b);
            } else {
                return null;
            }
        }
        return null;
    }

    private static final class Nested {
        final byte[] data;
        Nested(byte[] data) { this.data = data; }
    }

    private static Nested nestedField4(byte[] body) {
        ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int key = b.get() & 0xff;
            int field = key >> 3;
            int wire = key & 7;
            if (wire == 2) {
                int length = b.get() & 0xff;
                byte[] bytes = new byte[length];
                b.get(bytes);
                if (field == 4) {
                    return new Nested(bytes);
                }
            } else if (wire == 0) {
                readVarint(b);
            } else {
                return null;
            }
        }
        return null;
    }

    private static long readVarint(ByteBuffer b) {
        long value = 0;
        int shift = 0;
        while (b.hasRemaining()) {
            int c = b.get() & 0xff;
            value |= (long) (c & 0x7f) << shift;
            shift += 7;
            if ((c & 0x80) == 0) {
                break;
            }
        }
        return value;
    }

    private static Integer firstVarint(byte[] data) {
        Long v = firstVarintLong(data);
        return v == null ? null : (int) v.longValue();
    }

    private static Long firstVarintLong(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int key = b.get() & 0xff;
            int field = key >> 3;
            int wire = key & 7;
            if (wire == 0) {
                long value = readVarint(b);
                if (field == 2) {
                    return value;
                }
            } else {
                return null;
            }
        }
        return null;
    }
}
