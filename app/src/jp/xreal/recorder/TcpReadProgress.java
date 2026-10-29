package jp.xreal.recorder;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.function.BooleanSupplier;

/** Reads a complete message and detects prolonged video inactivity. */
final class TcpReadProgress {
    static byte[] read(InputStream input, int length, long timeoutMs, BooleanSupplier running)
            throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        long lastProgressNs = System.nanoTime();
        while (offset < length && running.getAsBoolean()) {
            try {
                int count = input.read(data, offset, length - offset);
                if (count < 0) {
                    throw new EOFException("TCP stream closed");
                }
                offset += count;
                if (count > 0) {
                    lastProgressNs = System.nanoTime();
                }
            } catch (SocketTimeoutException ignored) {
                if (timeoutMs > 0 && (System.nanoTime() - lastProgressNs) / 1_000_000 >= timeoutMs) {
                    throw new IOException("TCP video has received no data for " + timeoutMs + " ms");
                }
            }
        }
        if (offset != length) {
            throw new EOFException("TCP recording stopped");
        }
        return data;
    }
}
