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
    private byte[] csd;
    private int width;
    private int height;
    private int videoTrackId = 1;
    private int audioTrackId = 2;
    private int metaTrackId = 3;
    private int fragmentNumber;
    private long decodeTimeUs;
    private boolean closed;
    private final java.util.List<Long> fragmentOffsets = new java.util.ArrayList<>();
    private final java.util.List<Long> fragmentTimes = new java.util.ArrayList<>();
    private long writeOffset;

    StreamingGyroflowMuxer(OutputStream out, byte[] csd, int width, int height) {
        this.out = out;
        this.csd = csd;
        this.width = width;
        this.height = height;
    }

    void start() throws IOException {
        writeBox(out, "ftyp", concat(ascii("isom"), u32(0), ascii("isomiso2avc1mp41")));
        byte[] moov = moov();
        out.write(moov);
        writeOffset = 8L + moov.length;
        out.flush();
    }

    void setCsd(byte[] csd, int w, int h) {
        this.csd = csd;
        this.width = w;
        this.height = h;
    }

    /** Append one video, audio and gyro sample as a new fragment. */
    void writeSample(byte[] video, byte[] audio, byte[] gyro, long durationUs)
            throws IOException {
        if (closed) {
            return;
        }
        // One moof with three traf, one mdat holding the three payloads in order.
        // With default-base-is-moof, each trun data_offset is relative to the
        // start of the moof box, NOT to the trun itself.
        long baseDecode = decodeTimeUs;
        // Actual sizes: tfhd=16, tfdt=20 (version 1, zero decode time), trun=32,
        // traf=76, mfhd=16. (tfdt grows to 28 once decode time exceeds 32 bits.)
        int moofSize = 8 + 16 + 3 * 76; // 252
        int dataStart = moofSize + 8; // skip mdat header
        byte[] trafVideo = traf(videoTrackId, baseDecode, video.length, durationUs,
                dataStart, true);
        byte[] trafAudio = traf(audioTrackId, baseDecode, audio.length, durationUs,
                dataStart + video.length, false);
        byte[] trafMeta = traf(metaTrackId, baseDecode, gyro.length, durationUs,
                dataStart + video.length + audio.length, false);
        byte[] moof = box("moof", concat(
                fullBox("mfhd", u32(fragmentNumber)),
                trafVideo, trafAudio, trafMeta));
        long mdatSize = 8L + video.length + audio.length + gyro.length;
        byte[] mdatHeader = boxHeader("mdat", mdatSize);
        fragmentOffsets.add(writeOffset);
        fragmentTimes.add(decodeTimeUs);
        out.write(moof);
        out.write(mdatHeader);
        out.write(video);
        out.write(audio);
        out.write(gyro);
        writeOffset += moof.length + mdatSize;
        decodeTimeUs += durationUs;
        fragmentNumber++;
    }

    void stop() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        java.io.ByteArrayOutputStream entries = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < fragmentOffsets.size(); i++) {
            // tfho: track-ID(4) + fragment-offset(8) + decode-time(8) + duration(8)
            // (duration is set to 0 here; readers use trun for sample timing.)
            entries.write(concat(u32(videoTrackId), u64(fragmentOffsets.get(i)),
                    u64(fragmentTimes.get(i)), u64(0)));
        }
        byte[] tfra = fullBox("tfra", concat(
                u32(videoTrackId), u32(0), u32(0), u32(0), u32(fragmentOffsets.size()),
                entries.toByteArray()));
        byte[] mfro = fullBox("mfro", u32(mfraSize(tfra.length)));
        byte[] mfra = box("mfra", concat(tfra, mfro));
        out.write(mfra);
        out.flush();
        out.close();
    }

    private static long mfraSize(int tfraLength) {
        return 8L + tfraLength + 16L;  // mfra header + tfra + mfro
    }

    private byte[] moov() throws IOException {
        byte[] mvhd = fullBox("mvhd", concat(
                u32(0), u32(0), u32(TIMESCALE), u32(0),
                u32(0x00010000), u16(0x0100), u16(0), u32(0), u32(0),
                matrix(), new byte[24], u32(0), u32(metaTrackId + 1)));
        byte[] trakVideo = trak(videoTrackId, "vide", videoSampleEntry(), 0);
        byte[] trakAudio = trak(audioTrackId, "soun", audioSampleEntry(), 0);
        byte[] trakMeta = trak(metaTrackId, "meta", metaSampleEntry(), 0);
        byte[] mvex = box("mvex", concat(
                fullBox("trex", concat(u32(videoTrackId), u32(1), u32(0), u32(0), u32(0))),
                fullBox("trex", concat(u32(audioTrackId), u32(1), u32(0), u32(0), u32(0))),
                fullBox("trex", concat(u32(metaTrackId), u32(1), u32(0), u32(0), u32(0)))));
        return box("moov", concat(mvhd, trakVideo, trakAudio, trakMeta, mvex));
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
        byte[] stbl = box("stbl", concat(
                stsd,
                fullBox("stts", u32(0)),
                fullBox("stsc", u32(0)),
                fullBox("stsz", concat(u32(0), u32(0))),
                fullBox("stco", u32(0))));
        byte[] header = "vide".equals(handler) ? fullBox("vmhd", concat(u16(0), u16(0), u16(0), u16(0)))
                : "soun".equals(handler) ? fullBox("smhd", concat(u16(0), u16(0)))
                : fullBox("nmhd", new byte[0]);
        byte[] dinf = box("dinf", fullBox("dref", concat(u32(1),
                fullBox("url ", u32(1)))));
        byte[] minf = box("minf", concat(header, dinf, stbl));
        byte[] mdia = box("mdia", concat(mdhd, hdlr, minf));
        return box("trak", concat(tkhd, mdia));
    }

    private byte[] traf(int id, long decodeTimeUs, int sampleSize, long deltaUs,
                        int dataOffset, boolean key) {
        // tfhd flags 0x020000 = default-sample-duration present in the trex;
        // sample count is carried by trun. trun 0x020001 = data-offset present,
        // 0x000004 = sample-duration present, 0x000100 = sample-flags present.
        // default-base-is-moof: offsets are relative to the moof start.
        byte[] tfhd = fullBox("tfhd", 0, 0x020000, u32(id));
        // timescale is 1,000,000 (microseconds), so no *1000 here. version 1 = 64-bit.
        byte[] tfdt = fullBox("tfdt", 1, 0, u64(decodeTimeUs));
        // 0x000701 = data-offset + sample-duration + sample-size + sample-flags
        int flags = 0x000701;
        byte[] trun = fullBox("trun", 0, flags, concat(
                u32(1),               // sample_count
                u32(dataOffset),
                u32(deltaUs),         // sample_duration (per spec order)
                u32(sampleSize),      // sample_size
                u32(key ? 0x02000000 : 0x01010000)));
        return box("traf", concat(tfhd, tfdt, trun));
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
        return fullBox(type, 0, 0, body);
    }

    private static byte[] fullBox(String type, int version, int flags, byte[] body) {
        return box(type, concat(new byte[]{(byte) version,
                (byte) (flags >> 16), (byte) (flags >> 8), (byte) flags}, body));
    }
}
