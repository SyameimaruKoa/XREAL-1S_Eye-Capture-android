package com.syameimarukoa.xreal_recorder;

import android.content.Context;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Injects Gyroflow protobuf telemetry into an MP4 as a metadata track. */
final class Mp4TelemetryInjector {
    private Mp4TelemetryInjector() {
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
    /** Finish the MP4; gyrf samples were written incrementally during recording. */
    static void appendGyrf(StreamingMuxSink sink, int width, int height, float fps,
            long durationUs, int imuRateHz, String orientation,
            List<GyroflowProto.ImuSample> samples, long firstImuTimeUs)
            throws Exception {
        // Append a final gyrf sample so readers probing the file tail see it.
        if (!samples.isEmpty()) {
            double startUs = samples.get(0).shifted(firstImuTimeUs).timeUs;
            double endUs = samples.get(samples.size() - 1).shifted(firstImuTimeUs).timeUs;
            byte[] frame = GyroflowProto.frame(Integer.MAX_VALUE, startUs, endUs,
                    samples, width, height);
            sink.writeGyrf(GyroflowProto.main(null, frame), (long) startUs);
        }
        sink.close();
    }

    /** Offline conversion of raw HEVC/AAC/gyrf into an MP4. */
    static String convertRawToMp4(Context context, File hevc, File aac,
            int width, int height, float fps, long durationUs, int imuRateHz,
            String orientation, List<GyroflowProto.ImuSample> samples,
            long firstImuTimeUs, File output) throws Exception {
        int frames = (int) Math.max(1L, Math.round(durationUs * fps / 1_000_000.0));
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
            File aacInput = toAdts(context, aac);
            return GyroflowInjector.muxRaw(hevc.getAbsolutePath(),
                    aacInput.getAbsolutePath(), dir.getAbsolutePath(),
                    output.getAbsolutePath());
        } finally {
            deleteRecursively(dir);
        }
    }

    private static File toAdts(Context context, File aac) throws IOException {
        byte[] data = readAll(aac);
        if (data.length >= 2 && (data[0] & 0xff) == 0xff && (data[1] & 0xf0) == 0xf0) {
            return aac;
        }
        File out = new File(context.getCacheDir(), "aac_adts_" + aac.getName());
        try (FileOutputStream stream = new FileOutputStream(out)) {
            int pos = 0;
            while (pos + 4 <= data.length) {
                int size = ((data[pos] & 0xff) << 24) | ((data[pos + 1] & 0xff) << 16)
                        | ((data[pos + 2] & 0xff) << 8) | (data[pos + 3] & 0xff);
                if (size <= 0 || size > data.length - pos - 4) {
                    break;
                }
                int frameLength = size + 7;
                stream.write(new byte[]{
                        (byte) 0xff, (byte) 0xf1, (byte) 0x4c,
                        (byte) (0x40 | ((frameLength >>> 11) & 0x07)),
                        (byte) ((frameLength >>> 3) & 0xff),
                        (byte) (((frameLength & 0x07) << 5) | 0x1f),
                        (byte) 0xfc
                });
                stream.write(data, pos + 4, size);
                pos += 4 + size;
            }
        }
        return out;
    }

    private static byte[] readAll(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
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
    private static void writeAll(File f, byte[] data) throws IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(data);
        }
    }
}
