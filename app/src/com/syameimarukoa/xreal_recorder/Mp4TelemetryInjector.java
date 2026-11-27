package com.syameimarukoa.xreal_recorder;

import android.content.Context;

import org.mp4parser.Box;
import org.mp4parser.Container;
import org.mp4parser.PropertyBoxParserImpl;
import org.mp4parser.boxes.iso14496.part12.EditBox;
import org.mp4parser.boxes.iso14496.part12.EditListBox;
import org.mp4parser.boxes.iso14496.part12.MovieBox;
import org.mp4parser.boxes.iso14496.part12.TrackBox;
import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.Track;
import org.mp4parser.muxer.builder.DefaultMp4Builder;
import org.mp4parser.muxer.container.mp4.MovieCreator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/** Injects Gyroflow protobuf telemetry into an MP4 as a metadata track. */
final class Mp4TelemetryInjector {
    private Mp4TelemetryInjector() {
    }

    static void inject(Context context, File input, File output, int width, int height,
                       float fps, long durationUs, int imuRateHz, String orientation,
                       List<GyroflowProto.ImuSample> samples, long firstImuTimeUs)
            throws Exception {
        if (context != null && PropertyBoxParserImpl.BOX_MAP_CACHE == null) {
            Properties mapping = new Properties();
            try (InputStream stream = context.getAssets().open("isoparser2-default.properties")) {
                mapping.load(stream);
            }
            PropertyBoxParserImpl.BOX_MAP_CACHE = mapping;
        }
        if (!input.exists() || input.length() < 1024) {
            throw new IOException("Recording MP4 is empty or too short: "
                    + (input.exists() ? input.length() : "missing"));
        }
        // media3 fMP4 writes an audio sample entry without esds. mp4parser's
        // MovieCreator needs esds to keep the audio track, so synthesize one
        // for AAC LC / 48 kHz / mono before reading the movie.
        addEsdsIfMissing(input);
        Movie movie = MovieCreator.build(input.getAbsolutePath());
        Track video = null;
        for (Track track : movie.getTracks()) {
            if ("vide".equals(track.getHandler())) {
                video = track;
                break;
            }
        }
        if (video == null) {
            throw new IllegalStateException("no video track in recording");
        }
        long timescale = video.getTrackMetaData().getTimescale();
        long[] durations = video.getSampleDurations();
        if (durations.length == 0) {
            throw new IllegalStateException("empty video track");
        }

        List<byte[]> payloads = buildGyrfSamples(width, height, fps, durationUs,
                imuRateHz, orientation, samples, firstImuTimeUs, timescale, durations);
        movie.addTrack(new GyroflowTrack(payloads, timescale, durations));

        DefaultMp4Builder builder = new DefaultMp4Builder();
        Container container = builder.build(movie);
        long movieTimescale = builder.getTimescale(movie);
        fixTiming(container, movie, movieTimescale, movie.getTimescale());
        try (FileOutputStream stream = new FileOutputStream(output)) {
            container.writeContainer(stream.getChannel());
        }
        appendGyrfHeaderAtEnd(output);
    }

    private static void addEsdsIfMissing(File file) throws IOException {
        byte[] d = readAll(file);
        // Locate the first 'mp4a' sample entry; if it already has esds, done.
        int mp4a = -1;
        for (int i = 0; i + 8 <= d.length; i++) {
            int sz = structInt(d, i);
            if (sz < 8 || i + sz > d.length) {
                continue;
            }
            String t = new String(d, i + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if ("mp4a".equals(t)) {
                mp4a = i;
                break;
            }
        }
        if (mp4a < 0) {
            return;
        }
        int mp4aEnd = mp4a + structInt(d, mp4a);
        for (int i = mp4a + 8; i + 8 <= mp4aEnd; i++) {
            String t = new String(d, i + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if ("esds".equals(t)) {
                return;
            }
        }
        byte[] esds = buildEsds();
        int oldLen = structInt(d, mp4a);
        byte[] out = new byte[d.length + esds.length];
        System.arraycopy(d, 0, out, 0, mp4aEnd);
        System.arraycopy(esds, 0, out, mp4aEnd, esds.length);
        System.arraycopy(d, mp4aEnd, out, mp4aEnd + esds.length, d.length - mp4aEnd);
        structPutInt(out, mp4a, oldLen + esds.length);
        // Grow every ancestor box containing mp4a (moov/trak/mdia/minf/stbl/stsd)
        // by the same amount so the box tree stays consistent.
        MoovLoc loc = findMoovBytes(out);
        if (loc != null) {
            growAncestors(out, (int) loc.offset, mp4a, esds.length);
        }
        writeAll(file, out);
    }

    private static boolean growAncestors(byte[] d, int start, int target, int delta) {
        int end = start + structInt(d, start);
        boolean contains = false;
        int pos = start + 8;
        while (pos + 8 <= end && pos + 8 <= d.length) {
            int sz = structInt(d, pos);
            if (sz < 8 || pos + sz > d.length) {
                break;
            }
            if (pos <= target && target < pos + sz) {
                if (growAncestors(d, pos, target, delta)) {
                    contains = true;
                }
            }
            pos += sz;
        }
        if (contains) {
            structPutInt(d, start, structInt(d, start) + delta);
        }
        return contains || (start <= target && target < end);
    }

    private static byte[] buildEsds() {
        // ES_Descriptor > DecoderConfig(AAC LC, audio) > DecoderSpecificInfo
        // (AudioSpecificConfig 0x11 0x88 = AAC LC, 48000 Hz, mono) > SL.
        byte[] dsi = {0x05, 0x02, 0x11, (byte) 0x88};
        byte[] dec = new byte[15 + dsi.length];
        dec[0] = 0x04; dec[1] = (byte) (dec.length - 2);
        dec[2] = 0x40; dec[3] = 0x15;
        dec[4] = 0; dec[5] = 0; dec[6] = 0;
        dec[7] = 0; dec[8] = 0x01; dec[9] = (byte) 0xf4; dec[10] = 0;
        dec[11] = 0; dec[12] = 0x01; dec[13] = (byte) 0xf4; dec[14] = 0;
        System.arraycopy(dsi, 0, dec, 15, dsi.length);
        byte[] sl = {0x06, 0x01, 0x02};
        byte[] es = new byte[5 + dec.length + sl.length];
        es[0] = 0x03; es[1] = (byte) (dec.length + sl.length);
        es[2] = 0; es[3] = 0; es[4] = 0;
        System.arraycopy(dec, 0, es, 5, dec.length);
        System.arraycopy(sl, 0, es, 5 + dec.length, sl.length);
        byte[] box = new byte[12 + es.length];
        structPutInt(box, 0, box.length);
        byte[] type = "esds".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.arraycopy(type, 0, box, 4, 4);
        System.arraycopy(es, 0, box, 12, es.length);
        return box;
    }

    private static List<byte[]> buildGyrfSamples(int width, int height, float fps,
            long durationUs, int imuRateHz, String orientation,
            List<GyroflowProto.ImuSample> samples, long firstImuTimeUs,
            long timescale, long[] durations) {
        byte[] header = GyroflowProto.header(width, height, fps, durationUs,
                imuRateHz, orientation);
        // Gyroflow expects timestamps relative to the start of the clip (0-based),
        // not the glasses' absolute device clock (tens of thousands of seconds).
        // Feeding absolute timestamps makes it map only the tail of the IMU stream
        // onto the video, so horizon lock tracks poorly and YPR appears late.
        List<GyroflowProto.ImuSample> ordered = new ArrayList<>(samples.size());
        for (GyroflowProto.ImuSample s : samples) {
            ordered.add(s.shifted(firstImuTimeUs));
        }
        Collections.sort(ordered, (a, b) -> Double.compare(a.timeUs, b.timeUs));
        List<byte[]> payloads = new ArrayList<>(durations.length);
        int sampleIndex = 0;
        double cursorUs = 0;
        for (int i = 0; i < durations.length; i++) {
            // Use each frame's real duration so metadata timestamps follow the
            // actual (variable) video PTS instead of an assumed constant step.
            double frameUs = durations[i] * 1_000_000.0 / timescale;
            double startUs = cursorUs;
            double frameEndUs = startUs + frameUs;
            cursorUs = frameEndUs;
            // Gyroflow spec: one metadata sample per video frame, IMU samples
            // distributed by their real frame window. Storing the whole stream in
            // the first frame is non-conforming and may be rejected by readers.
            List<GyroflowProto.ImuSample> frameSamples = new ArrayList<>();
            while (sampleIndex < ordered.size()
                    && ordered.get(sampleIndex).timeUs < frameEndUs) {
                frameSamples.add(ordered.get(sampleIndex));
                sampleIndex++;
            }
            // XREAL corrects rolling shutter in-camera. Declare zero readout span
            // so Gyroflow does not warp rows ("jelly").
            double endUs = startUs;
            byte[] frame = GyroflowProto.frame(i + 1, startUs, endUs, frameSamples,
                    width, height);
            payloads.add(GyroflowProto.main(i == 0 ? header : null, frame));
        }
        return payloads;
    }

    /**
     * Add a gyrf track without rebuilding the movie. mp4parser cannot read the
     * esds-less audio sample entry produced by media3 fMP4, so a full remux
     * drops the audio track. Instead we copy the original moov (with audio and
     * video intact) to the end of the file and append a gyrf track to it. The
     * head moov is left untouched; readers merge the tail moov.
     */
    static void injectWithoutRemux(Context context, File input, File output,
            int width, int height, float fps, long durationUs, int imuRateHz,
            String orientation, List<GyroflowProto.ImuSample> samples,
            long firstImuTimeUs) throws Exception {
        if (context != null && PropertyBoxParserImpl.BOX_MAP_CACHE == null) {
            Properties mapping = new Properties();
            try (InputStream stream = context.getAssets().open("isoparser2-default.properties")) {
                mapping.load(stream);
            }
            PropertyBoxParserImpl.BOX_MAP_CACHE = mapping;
        }
        byte[] source = readAll(input);
        MoovLoc loc = findMoovBytes(source);
        if (loc == null) {
            throw new IOException("no moov in recording");
        }
        // Read video durations/timescale from the original moov via mp4parser's
        // lightweight sample table reader, avoiding a full Movie remux.
        Movie movie = MovieCreator.build(input.getAbsolutePath());
        Track video = null;
        for (Track t : movie.getTracks()) {
            if ("vide".equals(t.getHandler())) {
                video = t;
                break;
            }
        }
        if (video == null) {
            throw new IOException("no video track");
        }
        long timescale = video.getTrackMetaData().getTimescale();
        long[] durations = video.getSampleDurations();
        List<byte[]> payloads = buildGyrfSamples(width, height, fps, durationUs,
                imuRateHz, orientation, samples, firstImuTimeUs, timescale, durations);

        // Build a gyrf-only moov to obtain a valid TrackBox for the metadata.
        Movie gyrfMovie = new Movie();
        gyrfMovie.addTrack(new GyroflowTrack(payloads, timescale, durations));
        Container gyrfContainer = new DefaultMp4Builder().build(gyrfMovie);
        java.io.ByteArrayOutputStream gyrfMoovBytes = new java.io.ByteArrayOutputStream();
        gyrfContainer.writeContainer(java.nio.channels.Channels.newChannel(gyrfMoovBytes));
        byte[] gyrfMoov = gyrfMoovBytes.toByteArray();
        MoovLoc gyrfLoc = findMoovBytes(gyrfMoov);
        int gyrfTrakStart = findBox(gyrfMoov, (int) gyrfLoc.offset,
                (int) gyrfLoc.size, "trak");
        int gyrfTrakLen = structSize(gyrfMoov, gyrfTrakStart);
        byte[] gyrfTrak = java.util.Arrays.copyOfRange(gyrfMoov,
                gyrfTrakStart, gyrfTrakStart + gyrfTrakLen);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(source);
        long gyrfDataOffset = source.length;
        for (byte[] p : payloads) {
            out.write(p);
        }
        // Append the original moov + the gyrf trak at the end.
        byte[] tail = java.util.Arrays.copyOfRange(source, (int) loc.offset,
                (int) (loc.offset + loc.size));
        int newTrakAt = tail.length;
        out.write(tail, 0, newTrakAt);
        out.write(gyrfTrak);
        // Patch the tail moov size and the gyrf stco to point at the appended data.
        byte[] result = out.toByteArray();
        int tailMoovAbs = lastMoovOffset(result);
        // stco in the gyrf trak starts at tailMoovAbs + newTrakAt + (offset within trak)
        int stcoAbs = findBox(result, tailMoovAbs + newTrakAt, gyrfTrakLen, "stco");
        int chunkCount = structInt(result, stcoAbs + 12);
        long offset = gyrfDataOffset;
        for (int i = 0; i < chunkCount; i++) {
            structPutInt(result, stcoAbs + 16 + 4 * i, (int) offset);
            // each chunk holds one or more samples; advance by total sample bytes
            // handled below by the original builder - use uniform from builder
        }
        // Rebuild stco from the builder's own chunk layout instead of guessing:
        int stszAbs = findBox(result, tailMoovAbs + newTrakAt, gyrfTrakLen, "stsz");
        int sampleCount = structInt(result, stszAbs + 16);
        int sampleSize = structInt(result, stszAbs + 12);
        long[] sampleSizes = new long[sampleCount];
        if (sampleSize > 0) {
            java.util.Arrays.fill(sampleSizes, sampleSize);
        } else {
            for (int i = 0; i < sampleCount; i++) {
                sampleSizes[i] = structInt(result, stszAbs + 20 + 4 * i);
            }
        }
        int stscAbs = findBox(result, tailMoovAbs + newTrakAt, gyrfTrakLen, "stsc");
        int scCount = structInt(result, stscAbs + 12);
        int idx = 0;
        offset = gyrfDataOffset;
        for (int c = 1; c <= chunkCount; c++) {
            int perChunk = 1;
            for (int e = 0; e < scCount; e++) {
                if (structInt(result, stscAbs + 16 + 12 * e) <= c) {
                    perChunk = structInt(result, stscAbs + 20 + 12 * e);
                }
            }
            structPutInt(result, stcoAbs + 16 + 4 * (c - 1), (int) offset);
            for (int k = 0; k < perChunk && idx < sampleCount; k++) {
                offset += sampleSizes[idx++];
            }
        }
        structPutInt(result, tailMoovAbs, (int) loc.size + gyrfTrakLen);
        writeAll(new java.io.File(output.getAbsolutePath()), result);
        appendGyrfHeaderAtEnd(output);
    }

    /** Mux raw HEVC + ADTS AAC + gyrf with the official Gyroflow ffmpeg tool. */
    static void injectWithFfmpeg(Context context, File hevc, File aac, File output,
            int width, int height, float fps, long durationUs, int imuRateHz,
            String orientation, List<GyroflowProto.ImuSample> samples,
            long firstImuTimeUs) throws Exception {
        int frames = Math.max(1, Math.round(durationUs * fps / 1_000_000.0));
        long step = Math.max(1L, Math.round(1_000_000.0 / fps));
        long[] durations = new long[frames];
        java.util.Arrays.fill(durations, step);
        List<byte[]> payloads = buildGyrfSamples(width, height, fps, durationUs,
                imuRateHz, orientation, samples, firstImuTimeUs, 1_000_000L, durations);
        File dir = new File(context.getCacheDir(), "gyrf_frames");
        deleteRecursively(dir);
        if (!dir.mkdirs()) {
            throw new IOException("cannot create gyrf frame directory");
        }
        try {
            for (int i = 0; i < payloads.size(); i++) {
                writeAll(new File(dir, String.format(java.util.Locale.US,
                        "frame_%06d.bin", i + 1)), payloads.get(i));
            }
            if (!GyroflowInjector.muxRaw(hevc.getAbsolutePath(),
                    aac.getAbsolutePath(), dir.getAbsolutePath(),
                    output.getAbsolutePath())) {
                throw new IOException("gyroflow_proto_inject muxRaw failed");
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static byte[] readAll(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static int structSize(byte[] b, int i) {
        return structInt(b, i);
    }

    private static int structInt(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16)
                | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    private static void structPutInt(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }

    private static int sumLengths(List<byte[]> ps) {
        int n = 0;
        for (byte[] p : ps) {
            n += p.length;
        }
        return n;
    }

    private static int lastMoovOffset(byte[] d) {
        int last = -1;
        for (int i = 0; i + 8 <= d.length; i++) {
            int sz = structInt(d, i);
            String t = new String(d, i + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (sz < 8 || i + sz > d.length) {
                continue;
            }
            if ("moov".equals(t)) {
                last = i;
            }
        }
        return last;
    }

    private static MoovLoc findMoovBytes(byte[] d) {
        int i = 0;
        while (i + 8 <= d.length) {
            int sz = structInt(d, i);
            String t = new String(d, i + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (sz < 8 || i + sz > d.length) {
                return null;
            }
            if ("moov".equals(t)) {
                return new MoovLoc(i, sz);
            }
            i += sz;
        }
        return null;
    }

    /**
     * Gyroflow's detect only scans the first/last 5 MB of a file for the
     * "GyroflowProtobuf" magic. In long clips the injected gyrf samples sit in
     * the middle and detection fails. Copy the first (header) gyrf sample to
     * the very end of the file and repoint stco[0] at it, so the magic is in the
     * tail window. Only the moov's stco entry is rewritten - no second full
     * write of the recording.
     */
    private static void appendGyrfHeaderAtEnd(File file) throws IOException {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw");
        try {
            long size = raf.length();
            MoovLoc loc = findMoov(raf, size);
            if (loc == null) {
                return;
            }
            raf.seek(loc.offset);
            byte[] moov = new byte[(int) loc.size];
            raf.readFully(moov);
            int trak = findGyrfTrak(moov);
            if (trak < 0) {
                return;
            }
            int stcoRel = findBox(moov, trak, trakLen(moov, trak), "stco");
            int stszRel = findBox(moov, trak, trakLen(moov, trak), "stsz");
            if (stcoRel < 0 || stszRel < 0) {
                return;
            }
            int chunkCount = be32(moov, stcoRel + 12);
            int sampleCount = be32(moov, stszRel + 16);
            if (chunkCount < 1 || sampleCount < 1) {
                return;
            }
            long firstOff = be32u(moov, stcoRel + 16);
            int firstSize = be32(moov, stszRel + 20);
            if (firstSize <= 0 || firstSize > 1024 * 1024 || firstOff + firstSize > size) {
                return;
            }
            byte[] sample = new byte[firstSize];
            raf.seek(firstOff);
            raf.readFully(sample);
            if (!new String(sample, java.nio.charset.StandardCharsets.ISO_8859_1)
                    .contains("GyroflowProtobuf")) {
                return;
            }
            raf.seek(size);
            raf.write(sample);
            raf.seek(loc.offset + stcoRel + 16);
            raf.write(intBytes((int) size));
        } finally {
            raf.close();
        }
    }

    private static final class MoovLoc {
        final long offset; final long size;
        MoovLoc(long o, long s) { offset = o; size = s; }
    }

    private static MoovLoc findMoov(java.io.RandomAccessFile raf, long size) throws IOException {
        long pos = 0;
        while (pos + 8 <= size) {
            raf.seek(pos);
            byte[] hdr = new byte[8];
            raf.readFully(hdr);
            long sz = be32u(hdr, 0);
            String type = new String(hdr, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (sz < 8 || pos + sz > size) {
                return null;
            }
            if ("moov".equals(type) && sz < Integer.MAX_VALUE) {
                return new MoovLoc(pos, sz);
            }
            pos += sz;
        }
        return null;
    }

    private static int findGyrfTrak(byte[] moov) {
        int pos = 8;
        while (pos + 8 <= moov.length) {
            int sz = be32(moov, pos);
            String type = new String(moov, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (sz < 8 || pos + sz > moov.length) {
                return -1;
            }
            if ("trak".equals(type)) {
                int stsd = findBox(moov, pos, sz, "stsd");
                if (stsd >= 0) {
                    String entry = new String(moov, stsd + 16, 4,
                            java.nio.charset.StandardCharsets.ISO_8859_1);
                    if ("gyrf".equals(entry)) {
                        return pos;
                    }
                }
            }
            pos += sz;
        }
        return -1;
    }

    private static int trakLen(byte[] moov, int trak) {
        return be32(moov, trak);
    }

    private static int findBox(byte[] buf, int start, int length, String wanted) {
        int pos = start + 8;
        int end = start + length;
        while (pos + 8 <= end) {
            int sz = be32(buf, pos);
            String type = new String(buf, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (sz < 8 || pos + sz > end) {
                return -1;
            }
            if (wanted.equals(type)) {
                return pos;
            }
            int nested = findBox(buf, pos, sz, wanted);
            if (nested >= 0) {
                return nested;
            }
            pos += sz;
        }
        return -1;
    }

    private static int be32(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16)
                | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    private static long be32u(byte[] b, int i) {
        return be32(b, i) & 0xffffffffL;
    }

    private static byte[] intBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** mp4parser uses GCD for edit lists but LCM for mvhd; align both to mvhd. */
    private static void fixTiming(Container container, Movie movie,
                                  long movieTimescale, long editTimescale) {
        for (Box box : container.getBoxes()) {
            if (!(box instanceof MovieBox)) {
                continue;
            }
            for (TrackBox trackBox : ((MovieBox) box).getBoxes(TrackBox.class)) {
                if (editTimescale > 0 && editTimescale != movieTimescale) {
                    for (EditBox edit : trackBox.getBoxes(EditBox.class)) {
                        for (EditListBox list : edit.getBoxes(EditListBox.class)) {
                            for (EditListBox.Entry entry : list.getEntries()) {
                                entry.setSegmentDuration(entry.getSegmentDuration()
                                        * movieTimescale / editTimescale);
                            }
                        }
                    }
                }
                long trackId = trackBox.getTrackHeaderBox().getTrackId();
                for (Track track : movie.getTracks()) {
                    if (track.getTrackMetaData().getTrackId() != trackId) {
                        continue;
                    }
                    long trackTimescale = track.getTrackMetaData().getTimescale();
                    long duration = trackTimescale > 0
                            ? track.getDuration() * movieTimescale / trackTimescale : 0L;
                    trackBox.getTrackHeaderBox().setDuration(duration);
                    break;
                }
            }
        }
    }
}
