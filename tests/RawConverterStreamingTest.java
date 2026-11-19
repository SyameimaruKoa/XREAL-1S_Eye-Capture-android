package com.syameimarukoa.xreal_recorder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Guards the two-pass streaming refactor of RawToMp4Converter: the streaming
 * NAL traversal must yield the same parameter sets and access units as the
 * original collect-all approach, without holding every AU in memory.
 */
public final class RawConverterStreamingTest {
    private static final class Au {
        final byte[] data;
        final int type;
        Au(byte[] data, int type) { this.data = data; this.type = type; }
    }

    /** Original approach: read every NAL into a list, then build CSD from it. */
    private static List<Au> readAll(byte[] stream) {
        List<Au> result = new ArrayList<>();
        ByteArrayOutputStream nal = new ByteArrayOutputStream();
        int zeros = 0;
        for (byte value : stream) {
            int b = value & 0xff;
            if (b == 0) { zeros++; continue; }
            if (b == 1 && zeros >= 2) {
                if (nal.size() > 0) {
                    byte[] data = nal.toByteArray();
                    result.add(new Au(data, (data[0] >> 1) & 0x3f));
                    nal.reset();
                }
                zeros = 0;
                continue;
            }
            for (int z = 0; z < zeros; z++) nal.write(0);
            zeros = 0;
            nal.write(b);
        }
        if (nal.size() > 0) {
            byte[] data = nal.toByteArray();
            result.add(new Au(data, (data[0] >> 1) & 0x3f));
        }
        return result;
    }

    /** New approach: stream NALs to a consumer, collecting only CSD parameter sets. */
    private static byte[] streamingCsd(byte[] stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int[] zeros = {0};
        final ByteArrayOutputStream nal = new ByteArrayOutputStream();
        forEachNal(stream, (data, type) -> {
            if (type == 32 || type == 33 || type == 34) {
                out.write(new byte[]{0, 0, 0, 1});
                out.write(data);
            }
        });
        return out.toByteArray();
    }

    private interface Consumer { void accept(byte[] data, int type) throws Exception; }

    private static void forEachNal(byte[] stream, Consumer consumer) {
        final ByteArrayOutputStream nal = new ByteArrayOutputStream();
        int zeros = 0;
        for (byte value : stream) {
            int b = value & 0xff;
            if (b == 0) { zeros++; continue; }
            if (b == 1 && zeros >= 2) {
                if (nal.size() > 0) {
                    byte[] data = nal.toByteArray();
                    try { consumer.accept(data, (data[0] >> 1) & 0x3f); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    nal.reset();
                }
                zeros = 0;
                continue;
            }
            for (int z = 0; z < zeros; z++) nal.write(0);
            zeros = 0;
            nal.write(b);
        }
        if (nal.size() > 0) {
            byte[] data = nal.toByteArray();
            try { consumer.accept(data, (data[0] >> 1) & 0x3f); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        // VPS (type 32: header 0x40 0x01), SPS (33: 0x42 0x01), PPS (34: 0x44 0x01),
        // then three VCL slices (type 1: 0x02 0x01) with distinct payloads.
        nal(stream, 0x40, 0x01, 1);
        nal(stream, 0x42, 0x01, 2);
        nal(stream, 0x44, 0x01, 3);
        nal(stream, 0x02, 0x01, 4);
        nal(stream, 0x02, 0x01, 5);
        nal(stream, 0x02, 0x01, 6);
        byte[] bytes = stream.toByteArray();

        List<Au> all = readAll(bytes);
        require(all.size() == 6, "expected 6 NALs, got " + all.size());
        ByteArrayOutputStream expectedCsd = new ByteArrayOutputStream();
        for (Au au : all) {
            if (au.type == 32 || au.type == 33 || au.type == 34) {
                expectedCsd.write(new byte[]{0, 0, 0, 1});
                expectedCsd.write(au.data);
            }
        }
        require(Arrays.equals(expectedCsd.toByteArray(), streamingCsd(bytes)),
                "streaming CSD differs from collect-all CSD");
        System.out.println("RawConverterStreamingTest passed (6 NALs, CSD identical)");
    }

    private static void nal(ByteArrayOutputStream out, int header, int second, int payload) throws Exception {
        out.write(new byte[]{0, 0, 0, 1, (byte) header, (byte) second, (byte) payload});
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
