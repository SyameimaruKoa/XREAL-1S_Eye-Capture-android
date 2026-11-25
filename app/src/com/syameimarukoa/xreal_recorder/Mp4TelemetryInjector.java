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
