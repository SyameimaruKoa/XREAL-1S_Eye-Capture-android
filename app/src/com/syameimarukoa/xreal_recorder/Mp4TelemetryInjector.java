package com.syameimarukoa.xreal_recorder;

import android.content.Context;


import java.io.File;
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
    /** Append gyrf samples to the MP4 being written, then finish it. */
    static void appendGyrf(StreamingMuxSink sink, int width, int height, float fps,
            long durationUs, int imuRateHz, String orientation,
            List<GyroflowProto.ImuSample> samples, long firstImuTimeUs)
            throws Exception {
        int frames = (int) Math.max(1L, Math.round(durationUs * fps / 1_000_000.0));
        long step = Math.max(1L, Math.round(1_000_000.0 / fps));
        long[] durations = new long[frames];
        java.util.Arrays.fill(durations, step);
        List<byte[]> payloads = buildGyrfSamples(width, height, fps, durationUs,
                imuRateHz, orientation, samples, firstImuTimeUs, 1_000_000L, durations);
        for (int i = 0; i < payloads.size(); i++) {
            sink.writeGyrf(payloads.get(i), i * step);
        }
        sink.close();
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
