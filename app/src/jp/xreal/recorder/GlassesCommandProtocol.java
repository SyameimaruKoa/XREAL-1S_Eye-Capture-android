package jp.xreal.recorder;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/** Framing for the glasses command service; this class does not execute commands. */
final class GlassesCommandProtocol {
    private static final int REQUEST_HEADER_SIZE = 17;
    private static final int RESPONSE_HEADER_SIZE = 18;
    private static final int MAX_BODY_SIZE = 4096;

    private GlassesCommandProtocol() { }

    static byte[] request(int sequence, int command, byte[] payload) throws IOException {
        if (command < 0 || command > 0xffff || payload.length > MAX_BODY_SIZE - REQUEST_HEADER_SIZE) {
            throw new IOException("Command request is out of range");
        }
        ByteBuffer body = ByteBuffer.allocate(REQUEST_HEADER_SIZE + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.putShort((short) body.capacity());
        body.putInt(sequence);
        body.putInt(0);
        body.putShort((short) command);
        body.put(new byte[5]);
        body.put(payload);
        CRC32 crc = new CRC32();
        crc.update(body.array());
        ByteBuffer packet = ByteBuffer.allocate(5 + body.capacity()).order(ByteOrder.LITTLE_ENDIAN);
        packet.put((byte) 0xfd);
        packet.putInt((int) crc.getValue());
        packet.put(body.array());
        return packet.array();
    }

    static Reply readReply(InputStream input, int expectedSequence, int expectedCommand)
            throws IOException {
        byte[] prefix = readExact(input, 7);
        if ((prefix[0] & 0xff) != 0xfd) {
            throw new IOException("Invalid command response marker");
        }
        int length = (prefix[5] & 0xff) | ((prefix[6] & 0xff) << 8);
        if (length < RESPONSE_HEADER_SIZE || length > MAX_BODY_SIZE) {
            throw new IOException("Invalid command response length");
        }
        byte[] rest = readExact(input, length);
        if (rest[length - 2] != 0 || rest[length - 1] != 0) {
            throw new IOException("Invalid command response trailer");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(length);
        body.write(prefix, 5, 2);
        body.write(rest, 0, length - 2);
        byte[] content = body.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(content);
        long expectedCrc = Integer.toUnsignedLong(ByteBuffer.wrap(prefix, 1, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt());
        if (crc.getValue() != expectedCrc) {
            throw new IOException("Command response checksum mismatch");
        }
        ByteBuffer fields = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
        int sequence = fields.getInt(2);
        int command = Short.toUnsignedInt(fields.getShort(10));
        if (sequence != expectedSequence || command != expectedCommand) {
            throw new IOException("Unexpected command response");
        }
        byte[] payload = new byte[length - RESPONSE_HEADER_SIZE];
        System.arraycopy(content, RESPONSE_HEADER_SIZE, payload, 0, payload.length);
        return new Reply(content[17] & 0xff, payload);
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int position = 0;
        while (position < length) {
            int count = input.read(result, position, length - position);
            if (count < 0) {
                throw new EOFException("Incomplete command response");
            }
            if (count == 0) {
                throw new IOException("Command response made no progress");
            }
            position += count;
        }
        return result;
    }

    static final class Reply {
        final int status;
        final byte[] payload;

        Reply(int status, byte[] payload) {
            this.status = status;
            this.payload = payload;
        }
    }
}
