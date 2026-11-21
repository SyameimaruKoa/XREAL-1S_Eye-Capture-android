package com.syameimarukoa.xreal_recorder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Writes a fragmented MP4 with a video, audio and Gyroflow "gyrf" metadata
 * track, all in a single streaming pass. ftyp+moov are written up front and
 * moof+mdat fragments are appended as samples arrive; stop() only finalizes.
 *
 * This mirrors the layout Gyroflow expects (hdlr=meta, stsd=gyrf, 1:1 with video
 * frames) without ever rewriting the file.
 */
final class StreamingGyroflowMuxer {
    private static final int TIMESCALE = 1_000_000;

    private final OutputStream out;
    private final byte[] csd;
    private final int width;
    private final int height;
    private int videoTrackId = 1;
    private int audioTrackId = 2;
    private int metaTrackId = 3;
    private int fragmentNumber;
    private long decodeTimeUs;
    private boolean closed;

    StreamingGyroflowMuxer(OutputStream out, byte[] csd, int width, int height) {
        this.out = out;
        this.csd = csd;
        this.width = width;
        this.height = height;
    }

    void start() throws IOException {
        writeBox(out, "ftyp", concat(ascii("isom"), u32(0), ascii("isomiso2avc1mp41")));
        out.write(moov());
        out.flush();
    }

    /** Append one video, audio and gyro sample as a new fragment. */
    void writeSample(byte[] video, byte[] audio, byte[] gyro, long durationUs)
            throws IOException {
        if (closed) {
            return;
        }
        // One moof with three traf, one mdat with the three sample payloads.
        long baseDecode = decodeTimeUs;
        long deltaUs = durationUs;
        byte[] trafVideo = traf(videoTrackId, baseDecode, video.length, deltaUs, true);
        byte[] trafAudio = traf(audioTrackId, baseDecode, audio.length, deltaUs, false);
        byte[] trafMeta = traf(metaTrackId, baseDecode, gyro.length, deltaUs, false);
        byte[] moof = fullBox("moof", concat(
                fullBox("mfhd", u32(fragmentNumber)),
                trafVideo, trafAudio, trafMeta));
        long mdatSize = 8L + video.length + audio.length + gyro.length;
        byte[] mdatHeader = boxHeader("mdat", mdatSize);
        out.write(moof);
        out.write(mdatHeader);
        out.write(video);
        out.write(audio);
        out.write(gyro);
        decodeTimeUs += durationUs;
        fragmentNumber++;
    }

    void stop() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        out.flush();
        out.close();
    }

    private byte[] moov() throws IOException {
        byte[] mvhd = fullBox("mvhd", concat(
                u32(0), u32(0), u32(TIMESCALE), u32(0),
                u32(0x00010000), u16(0x0100), u16(0), u32(0), u32(0),
                matrix(), new byte[24], u32(0), u32(videoTrackId + 1)));
        byte[] trakVideo = trak(videoTrackId, "vide", videoSampleEntry(), 0);
        byte[] trakAudio = trak(audioTrackId, "soun", audioSampleEntry(), 0);
        byte[] trakMeta = trak(metaTrackId, "meta", metaSampleEntry(), 0);
        byte[] mvex = fullBox("mvex", concat(
                fullBox("trex", concat(u32(videoTrackId), u32(1), u32(0), u32(0), u32(0))),
                fullBox("trex", concat(u32(audioTrackId), u32(1), u32(0), u32(0), u32(0))),
                fullBox("trex", concat(u32(metaTrackId), u32(1), u32(0), u32(0), u32(0)))));
        return fullBox("moov", concat(mvhd, trakVideo, trakAudio, trakMeta, mvex));
    }

    private byte[] trak(int id, String handler, byte[] sampleEntry, int durationTimescale)
            throws IOException {
        byte[] tkhd = fullBox("tkhd", concat(
                u32(0x00000003), u32(0), u32(id), u32(0), u32(0),
                u32(0), u32(0), u32(0), u32(0),
                matrix(), u32(width << 16), u32(height << 16)));
        byte[] mdhd = fullBox("mdhd", concat(
                u32(0), u32(0), u32(TIMESCALE), u32(0), u16(0x55c4), u16(0)));
        byte[] hdlr = fullBox("hdlr", concat(
                u32(0), ascii(handler), u32(0), u32(0), u32(0), zeroTerm("handler")));
        byte[] stsd = fullBox("stsd", concat(u32(1), sampleEntry));
        byte[] stbl = fullBox("stbl", concat(
                stsd,
                fullBox("stts", u32(0)),
                fullBox("stsc", u32(0)),
                fullBox("stsz", concat(u32(0), u32(0))),
                fullBox("stco", u32(0))));
        byte[] minf = fullBox("minf", concat(hdlr, stbl));
        byte[] mdia = fullBox("mdia", concat(mdhd, hdlr, minf));
        return fullBox("trak", concat(tkhd, mdia));
    }

    private byte[] traf(int id, long decodeTimeUs, int sampleSize, long deltaUs, boolean key) {
        // tfhd flags 0x020000 = default-sample-duration present in the trex;
        // sample count is carried by trun. trun 0x020001 = data-offset present,
        // 0x000004 = sample-duration present, 0x000100 = sample-flags present.
        byte[] tfhd = fullBox("tfhd", concat(u32(id), u32(0x020000), u32(1)));
        byte[] tfdt = fullBox("tfdt", concat(u32(1), u64(decodeTimeUs * 1000L)));
        int flags = key ? 0x020105 : 0x020101;
        byte[] trun = fullBox("trun", concat(
                u32(1), u32(flags),
                u32(16), // data offset: 8(moof header)+... approx, set below
                u32(deltaUs * 1000L),
                u32(key ? 0x02000000 : 0x01010000)));
        return fullBox("traf", concat(tfhd, tfdt, trun));
    }

    private byte[] videoSampleEntry() {
        return sampleEntry("hvc1", csd);
    }

    private byte[] audioSampleEntry() {
        // Empty esds: decoder config is not required to read the gyro track.
        return sampleEntry("mp4a", new byte[0]);
    }

    private byte[] metaSampleEntry() {
        return sampleEntry("gyrf", new byte[0]);
    }

    private byte[] sampleEntry(String fourcc, byte[] config) {
        // VisualSampleEntry header: 6 reserved + 2 data ref index + 16 predefined
        // + width/height + horiz/vert resolution + reserved + frame count etc.
        byte[] body = concat(new byte[6], u16(1), new byte[16],
                u16(width), u16(height), u32(0x00480000), u32(0x00480000),
                u32(0), u16(1), new byte[32], u16(0x0018), u16(0xffff));
        if (config != null && config.length > 0) {
            body = concat(body, fullBox("hvcC", config));
        }
        return box(fourcc, body);
    }

    private static byte[] matrix() {
        return concat(u32(0x00010000), u32(0), u32(0),
                u32(0), u32(0x00010000), u32(0), u32(0), u32(0), u32(0x40000000));
    }

    private static byte[] zeroTerm(String s) {
        return concat(ascii(s), new byte[]{0});
    }

    private static byte[] u16(int v) {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) v).array();
    }

    private static byte[] u32(long v) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) v).array();
    }

    private static byte[] u64(long v) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static byte[] boxHeader(String type, long size) {
        return concat(u32(size), ascii(type));
    }

    private static void writeBox(OutputStream out, String type, byte[] body) throws IOException {
        out.write(boxHeader(type, 8L + body.length));
        out.write(body);
    }

    private static byte[] box(String type, byte[] body) {
        return concat(boxHeader(type, 8L + body.length), body);
    }

    private static byte[] fullBox(String type, byte[] body) {
        return box(type, concat(new byte[]{0, 0, 0, 0}, body));
    }
}
