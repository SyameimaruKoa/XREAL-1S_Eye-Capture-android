package jp.xreal.recorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Streaming Matroska writer for JPEG frames and optional AAC; it does not encode video. */
final class MatroskaWriter {
    private final OutputStream out;
    private boolean finished;

    MatroskaWriter(OutputStream out, int width, int height, int fps, byte[] aacConfig)
            throws IOException {
        if (out == null || width <= 0 || height <= 0 || fps <= 0) {
            throw new IllegalArgumentException("Invalid Matroska output or video dimensions");
        }
        this.out = out;
        ByteArrayOutputStream ebml = new ByteArrayOutputStream();
        integer(ebml, 0x4286, 1);
        integer(ebml, 0x42f7, 1);
        integer(ebml, 0x42f2, 4);
        integer(ebml, 0x42f3, 8);
        string(ebml, 0x4282, "matroska");
        integer(ebml, 0x4287, 4);
        integer(ebml, 0x4285, 2);
        element(out, 0x1a45dfa3, ebml.toByteArray());
        id(out, 0x18538067);
        out.write(new byte[]{1, -1, -1, -1, -1, -1, -1, -1});

        ByteArrayOutputStream info = new ByteArrayOutputStream();
        integer(info, 0x2ad7b1, 1_000_000);
        string(info, 0x4d80, "XREAL Recorder");
        string(info, 0x5741, "XREAL Recorder");
        element(out, 0x1549a966, info.toByteArray());

        ByteArrayOutputStream tracks = new ByteArrayOutputStream();
        ByteArrayOutputStream video = new ByteArrayOutputStream();
        integer(video, 0xd7, 1);
        integer(video, 0x73c5, 1);
        integer(video, 0x83, 1);
        string(video, 0x86, "V_MJPEG");
        integer(video, 0x23e383, 1_000_000_000L / fps);
        ByteArrayOutputStream dimensions = new ByteArrayOutputStream();
        integer(dimensions, 0xb0, width);
        integer(dimensions, 0xba, height);
        element(video, 0xe0, dimensions.toByteArray());
        element(tracks, 0xae, video.toByteArray());
        if (aacConfig != null) {
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            integer(audio, 0xd7, 2);
            integer(audio, 0x73c5, 2);
            integer(audio, 0x83, 2);
            string(audio, 0x86, "A_AAC");
            element(audio, 0x63a2, aacConfig);
            ByteArrayOutputStream properties = new ByteArrayOutputStream();
            element(properties, 0xb5, ByteBuffer.allocate(8).putDouble(48000.0).array());
            integer(properties, 0x9f, 1);
            element(audio, 0xe1, properties.toByteArray());
            element(tracks, 0xae, audio.toByteArray());
        }
        element(out, 0x1654ae6b, tracks.toByteArray());
    }

    synchronized void writeSample(int track, byte[] data, long ptsUs, boolean key) throws IOException {
        if (finished || data == null || (track != 1 && track != 2)) {
            throw new IllegalStateException("Invalid Matroska sample");
        }
        ByteArrayOutputStream time = new ByteArrayOutputStream();
        integer(time, 0xe7, Math.max(0, ptsUs / 1000));
        long blockSize = data.length + 4L;
        long clusterSize = time.size() + 1 + sizeLength(blockSize) + blockSize;
        id(out, 0x1f43b675);
        size(out, clusterSize);
        time.writeTo(out);
        id(out, 0xa3);
        size(out, blockSize);
        out.write(0x80 | track);
        out.write(0);
        out.write(0);
        out.write(key ? 0x80 : 0);
        out.write(data);
    }

    synchronized void finish() throws IOException {
        if (!finished) {
            finished = true;
            out.flush();
            out.close();
        }
    }

    private static void string(OutputStream out, int id, String text) throws IOException {
        element(out, id, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void integer(OutputStream out, int id, long value) throws IOException {
        int length = 1;
        while (length < 8 && (value >>> (length * 8)) != 0) {
            length++;
        }
        id(out, id);
        size(out, length);
        for (int shift = (length - 1) * 8; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift) & 0xff);
        }
    }

    private static void element(OutputStream out, int id, byte[] data) throws IOException {
        id(out, id);
        size(out, data.length);
        out.write(data);
    }

    private static void id(OutputStream out, int id) throws IOException {
        int shift = 24;
        while (shift > 0 && ((id >>> shift) & 0xff) == 0) {
            shift -= 8;
        }
        for (; shift >= 0; shift -= 8) {
            out.write((id >>> shift) & 0xff);
        }
    }

    private static int sizeLength(long value) {
        for (int length = 1; length <= 8; length++) {
            if (value < ((1L << (7 * length)) - 1)) {
                return length;
            }
        }
        throw new IllegalArgumentException("EBML element too large");
    }

    private static void size(OutputStream out, long value) throws IOException {
        int length = sizeLength(value);
        long encoded = value | (1L << (7 * length));
        for (int shift = (length - 1) * 8; shift >= 0; shift -= 8) {
            out.write((int) (encoded >>> shift) & 0xff);
        }
    }
}
