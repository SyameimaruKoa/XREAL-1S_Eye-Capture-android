package com.syameimarukoa.xreal_recorder;

import android.content.Context;

import org.mp4parser.Container;
import org.mp4parser.PropertyBoxParserImpl;
import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.Track;
import org.mp4parser.muxer.builder.DefaultMp4Builder;
import org.mp4parser.muxer.container.mp4.MovieCreator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Collections;
import java.util.List;

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
        double stepUs = durations[0] * 1_000_000.0 / timescale;
        List<byte[]> payloads = new ArrayList<>(durations.length);
        for (int i = 0; i < durations.length; i++) {
            double startUs = firstImuTimeUs + i * stepUs;
            double endUs = startUs + stepUs;
            List<GyroflowProto.ImuSample> frameSamples = i == 0
                    ? samples : Collections.<GyroflowProto.ImuSample>emptyList();
            byte[] frame = GyroflowProto.frame(i + 1, startUs, endUs, frameSamples);
            payloads.add(GyroflowProto.main(i == 0 ? header : null, frame));
        }
        movie.addTrack(new GyroflowTrack(payloads, timescale, durations));

        Container container = new DefaultMp4Builder().build(movie);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            container.writeContainer(stream.getChannel());
        }
    }
}
