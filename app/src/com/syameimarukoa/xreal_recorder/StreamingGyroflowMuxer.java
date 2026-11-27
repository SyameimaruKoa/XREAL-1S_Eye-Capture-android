package com.syameimarukoa.xreal_recorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-pass streaming MP4 writer.
 *
 * <p>ftyp + head moov (video/audio tracks, no gyrf) are written at start.
 * Each sample appends a video/audio moof+mdat fragment plus a raw mdat holding
 * the Gyroflow protobuf payload. stop() only appends mfra and a tail moov that
 * carries the gyrf sample table (stsd/stts/stsc/stsz/stco) pointing at those
 * payloads. mp4parse merges both moov boxes, so Gyroflow sees a normal gyrf
 * track while the file remains append-only and crash-resilient.
 */
final class StreamingGyroflowMuxer {
    private static final int TIMESCALE = 1_000_000;
    private static final int FTYP_SIZE = 32;
    private static final int TRAF_SIZE = 76; // tfhd(16) + tfdt(20) + trun(32) + header(8)
    private static final int MFHD_SIZE = 16;

    private final OutputStream out;
    private byte[] csd;
    private int width;
    private int height;
    private final int videoTrackId = 1;
    private final int audioTrackId = 2;
    private final int metaTrackId = 3;
    private int fragmentNumber;
    private long decodeTimeUs;
    private long totalDurationUs;
    private long writeOffset;
    private boolean closed;

    private final List<Long> fragmentOffsets = new ArrayList<>();
    private final List<Long> fragmentTimes = new ArrayList<>();
    private final List<Long> gyroOffsets = new ArrayList<>();
    private final List<Integer> gyroSizes = new ArrayList<>();
    private final List<Long> gyroDurations = new ArrayList<>();

    StreamingGyroflowMuxer(OutputStream out, byte[] csd, int width, int height) {
        this.out = out;
        this.csd = csd;
        this.width = width;
        this.height = height;
    }

    void start() throws IOException {
        writeBox(out, "ftyp", concat(ascii("isom"), u32(0), ascii("isomiso2avc1mp41")));
        byte[] moov = headMoov();
        out.write(moov);
        writeOffset = FTYP_SIZE + moov.length;
        out.flush();
    }

    void setCsd(byte[] csd, int w, int h) {
        this.csd = csd;
        this.width = w;
        this.height = h;
    }

    /** Append one video/audio fragment and one raw gyro mdat payload. */
    void writeSample(byte[] video, byte[] audio, byte[] gyro, long durationUs)
            throws IOException {
        if (closed) {
            return;
        }
        if (audio == null) {
            audio = new byte[0];
        }
        if (gyro == null) {
            gyro = new byte[0];
        }
        long baseDecode = decodeTimeUs;
        boolean hasAudio = audio != null && audio.length > 0;
        // With default-base-is-moof, trun data offsets are relative to the moof start.
        int moofSize = 8 + MFHD_SIZE + (hasAudio ? 2 : 1) * TRAF_SIZE;
        int dataStart = moofSize + 8; // skip mdat header
        byte[] trafVideo = traf(videoTrackId, baseDecode, video.length, durationUs,
                dataStart, true);
        byte[] moofBody = concat(fullBox("mfhd", u32(fragmentNumber)), trafVideo);
        long mediaSize = 8L + video.length;
        if (hasAudio) {
            byte[] trafAudio = traf(audioTrackId, baseDecode, audio.length, durationUs,
                    dataStart + video.length, false);
            moofBody = concat(moofBody, trafAudio);
            mediaSize += audio.length;
        }
        byte[] moof = box("moof", moofBody);
        out.write(moof);
        out.write(boxHeader("mdat", mediaSize));
        out.write(video);
        if (hasAudio) {
            out.write(audio);
        }
        fragmentOffsets.add(writeOffset);
        fragmentTimes.add(decodeTimeUs);
        writeOffset += moof.length + mediaSize;

        // Gyro payload: a standalone mdat, addressed by the tail moov stco.
        long gyroOffset = writeOffset + 8;
        out.write(boxHeader("mdat", 8L + gyro.length));
        out.write(gyro);
        gyroOffsets.add(gyroOffset);
        gyroSizes.add(gyro.length);
        gyroDurations.add(durationUs);
        writeOffset += 8L + gyro.length;

        decodeTimeUs += durationUs;
        totalDurationUs += durationUs;
        fragmentNumber++;
    }

    /** Append one AAC frame as its own fragment, stamped with its real PTS. */
    void writeAudioSample(byte[] audio, long ptsUs, long durationUs) throws IOException {
        if (closed || audio == null || audio.length == 0) {
            return;
        }
        // A standalone moof: mfhd + one audio traf, then one mdat. Keeping audio
        // in its own fragment lets every AAC frame (21.3 ms at 48 kHz) keep its
        // true timing instead of being forced to the 16.8 ms video frame rate.
        int moofSize = 8 + MFHD_SIZE + TRAF_SIZE;
        int dataStart = moofSize + 8;
        byte[] trafAudio = traf(audioTrackId, ptsUs, audio.length, durationUs,
                dataStart, false);
        byte[] moof = box("moof", concat(
                fullBox("mfhd", u32(fragmentNumber)), trafAudio));
        long mdatSize = 8L + audio.length;
        out.write(moof);
        out.write(boxHeader("mdat", mdatSize));
        out.write(audio);
        writeOffset += moof.length + mdatSize;
        fragmentNumber++;
    }

    void stop() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        // Random-access table for the video/audio fragments.
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        for (int i = 0; i < fragmentOffsets.size(); i++) {
            entries.write(concat(u32(videoTrackId), u64(fragmentOffsets.get(i)),
                    u64(fragmentTimes.get(i)), u64(0)));
        }
        byte[] tfra = fullBox("tfra", concat(
                u32(videoTrackId), u32(0), u32(0), u32(0), u32(fragmentOffsets.size()),
                entries.toByteArray()));
        byte[] mfro = fullBox("mfro", u32(8L + tfra.length + 16L));
        out.write(box("mfra", concat(tfra, mfro)));
        // Tail moov: the gyrf sample table Gyroflow actually reads (stsz/stco).
        out.write(tailMoov());
        out.flush();
        out.close();
    }

    /** Head moov: video/audio tracks plus mvex. No gyrf track here on purpose. */
    private byte[] headMoov() throws IOException {
        byte[] mvhd = fullBox("mvhd", concat(
                u32(0), u32(0), u32(TIMESCALE), u32(0),
                u32(0x00010000), u16(0x0100), u16(0), u32(0), u32(0),
                matrix(), new byte[24], u32(metaTrackId + 1)));
        byte[] trakVideo = trak(videoTrackId, "vide", videoSampleEntry());
        byte[] trakAudio = trak(audioTrackId, "soun", audioSampleEntry());
        byte[] mvex = box("mvex", concat(
                trex(videoTrackId), trex(audioTrackId)));
        return box("moov", concat(mvhd, trakVideo, trakAudio, mvex));
    }

    private byte[] trex(int id) {
        return fullBox("trex", concat(u32(id), u32(1), u32(0), u32(0), u32(0)));
    }

    /** Tail moov: only the gyrf track, with real sample tables. mp4parse merges it. */
    private byte[] tailMoov() throws IOException {
        byte[] mvhd = fullBox("mvhd", concat(
                u32(0), u32(0), u32(TIMESCALE), u32(totalDurationUs),
                u32(0x00010000), u16(0x0100), u16(0), u32(0), u32(0),
                matrix(), new byte[24], u32(metaTrackId + 1)));
        return box("moov", concat(mvhd, metaTrak()));
    }

    private byte[] metaTrak() throws IOException {
        byte[] tkhd = fullBox("tkhd", 0, 3, concat(
                u32(0), u32(0), u32(metaTrackId), u32(0), u32(totalDurationUs),
                u32(0), u32(0), u32(0), u32(0),
                matrix(), u32(0), u32(0)));
        byte[] mdhd = fullBox("mdhd", concat(
                u32(0), u32(0), u32(TIMESCALE), u32(totalDurationUs), u16(0x55c4), u16(0)));
        byte[] hdlr = fullBox("hdlr", concat(
                u32(0), ascii("meta"), u32(0), u32(0), u32(0), new byte[]{0}));
        byte[] stsd = fullBox("stsd", concat(u32(1), metaSampleEntry()));
        byte[] stbl = box("stbl", concat(stsd, stts(), stsc(), stsz(), stco()));
        byte[] dinf = box("dinf", fullBox("dref", 0, 0, concat(u32(1),
                fullBox("url ", 0, 1, new byte[0]))));
        byte[] minf = box("minf", concat(fullBox("nmhd", new byte[0]), dinf, stbl));
        byte[] mdia = box("mdia", concat(mdhd, hdlr, minf));
        return box("trak", concat(tkhd, mdia));
    }

    /** stts: run-length encoded sample durations. */
    private byte[] stts() {
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        long last = -1;
        long count = 0;
        for (long d : gyroDurations) {
            if (d != last) {
                if (count > 0) {
                    writeU32(entries, count);
                    writeU32(entries, last);
                }
                last = d;
                count = 1;
            } else {
                count++;
            }
        }
        if (count > 0) {
            writeU32(entries, count);
            writeU32(entries, last);
        }
        return fullBox("stts", concat(u32(gyroDurations.size() == 0 ? 0 : entries.size() / 8),
                entries.toByteArray()));
    }

    /** One sample per chunk, so stco can point at each payload exactly. */
    private byte[] stsc() {
        return fullBox("stsc", concat(u32(1), u32(1), u32(1), u32(1)));
    }

    private byte[] stsz() {
        ByteArrayOutputStream sizes = new ByteArrayOutputStream();
        for (int s : gyroSizes) {
            writeU32(sizes, s);
        }
        return fullBox("stsz", concat(u32(0), u32(gyroSizes.size()), sizes.toByteArray()));
    }

    private byte[] stco() {
        ByteArrayOutputStream offsets = new ByteArrayOutputStream();
        for (long o : gyroOffsets) {
            writeU32(offsets, o);
        }
        return fullBox("stco", concat(u32(gyroOffsets.size()), offsets.toByteArray()));
    }

    private byte[] trak(int id, String handler, byte[] sampleEntry) throws IOException {
        byte[] tkhd = fullBox("tkhd", 0, 3, concat(
                u32(0), u32(0), u32(id), u32(0), u32(0),
                u32(0), u32(0), u32(0), u32(0),
                matrix(), u32(width << 16), u32(height << 16)));
        byte[] mdhd = fullBox("mdhd", concat(
                u32(0), u32(0), u32(TIMESCALE), u32(0), u16(0x55c4), u16(0)));
        byte[] hdlr = fullBox("hdlr", concat(
                u32(0), ascii(handler), u32(0), u32(0), u32(0), new byte[]{0}));
        byte[] stsd = fullBox("stsd", concat(u32(1), sampleEntry));
        byte[] stbl = box("stbl", concat(
                stsd,
                fullBox("stts", u32(0)),
                fullBox("stsc", u32(0)),
                fullBox("stsz", concat(u32(0), u32(0))),
                fullBox("stco", u32(0))));
        byte[] header = "vide".equals(handler)
                ? fullBox("vmhd", concat(u16(0), u16(0), u16(0), u16(0)))
                : fullBox("smhd", concat(u16(0), u16(0)));
        byte[] dinf = box("dinf", fullBox("dref", 0, 0, concat(u32(1),
                fullBox("url ", 0, 1, new byte[0]))));
        byte[] minf = box("minf", concat(header, dinf, stbl));
        byte[] mdia = box("mdia", concat(mdhd, hdlr, minf));
        return box("trak", concat(tkhd, mdia));
    }

    private byte[] traf(int id, long decodeTimeUs, int sampleSize, long deltaUs,
                        int dataOffset, boolean key) {
        byte[] tfhd = fullBox("tfhd", 0, 0x020000, u32(id));
        byte[] tfdt = fullBox("tfdt", 1, 0, u64(decodeTimeUs));
        // FFmpeg isom.h: DATA_OFFSET=0x1, SAMPLE_DURATION=0x100,
        // SAMPLE_SIZE=0x200, SAMPLE_FLAGS=0x400. The body writes exactly these
        // four fields in order. 0x701 is the correct combination.
        int flags = 0x000701;
        byte[] trun = fullBox("trun", 0, flags, concat(
                u32(1), u32(dataOffset), u32(deltaUs), u32(sampleSize),
                u32(key ? 0x02000000 : 0x01010000)));
        return box("traf", concat(tfhd, tfdt, trun));
    }

    private byte[] videoSampleEntry() {
        return sampleEntry("hvc1", csd);
    }

    private byte[] audioSampleEntry() {
        // AudioSampleEntry (ISO 14496-12 12.2.3): SampleEntry (6 reserved +
        // 2 data-ref-index) + 8 reserved + channelcount(2) + samplesize(2) +
        // pre_defined(2) + reserved(2) + samplerate(4, 16.16). mp4parse reads
        // exactly this layout; a VisualSampleEntry body leaves trailing bytes
        // and triggers CheckParserStateErr. esds is optional for our purpose.
        // channelcount is 1: AudioSource encodes mono (48000 Hz, AAC LC).
        return box("mp4a", concat(new byte[6], u16(1), new byte[8],
                u16(1), u16(16), u16(0), u16(0), u32(48000L << 16)));
    }

    private byte[] metaSampleEntry() {
        // Empty MpegSampleEntry (16 bytes total), as in Gyroflow's injector.
        return box("gyrf", concat(new byte[6], u16(1)));
    }

    private byte[] sampleEntry(String fourcc, byte[] config) {
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

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
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

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        out.write(u32(v), 0, 4);
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
