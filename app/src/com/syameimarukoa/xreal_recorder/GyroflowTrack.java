package com.syameimarukoa.xreal_recorder;

import org.mp4parser.boxes.iso14496.part12.CompositionTimeToSample;
import org.mp4parser.boxes.iso14496.part12.SubSampleInformationBox;
import org.mp4parser.boxes.sampleentry.MpegSampleEntry;
import org.mp4parser.boxes.sampleentry.SampleEntry;
import org.mp4parser.boxes.samplegrouping.GroupEntry;
import org.mp4parser.muxer.Edit;
import org.mp4parser.muxer.Sample;
import org.mp4parser.muxer.SampleImpl;
import org.mp4parser.muxer.Track;
import org.mp4parser.muxer.TrackMetaData;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Metadata track carrying Gyroflow protobuf samples, one per video frame. */
final class GyroflowTrack implements Track {
    private final List<Sample> samples;
    private final long[] sampleDurations;
    private final long timescale;
    private final long duration;
    private final TrackMetaData metaData;
    private final SampleEntry sampleEntry = new MpegSampleEntry("gyrf");

    GyroflowTrack(List<byte[]> payloads, long timescale, long[] sampleDurations) {
        this.timescale = timescale;
        this.sampleDurations = sampleDurations;
        this.samples = new ArrayList<>(payloads.size());
        for (byte[] payload : payloads) {
            this.samples.add(new SampleImpl(ByteBuffer.wrap(payload), sampleEntry));
        }
        long duration = 0;
        for (long value : sampleDurations) {
            duration += value;
        }
        this.metaData = new TrackMetaData();
        this.metaData.setTimescale(timescale);
        this.metaData.setLanguage("und");
        Date now = new Date(0);
        this.metaData.setCreationTime(now);
        this.metaData.setModificationTime(now);
        this.duration = duration;
    }

    @Override
    public List<SampleEntry> getSampleEntries() {
        return Collections.singletonList(sampleEntry);
    }

    @Override
    public long[] getSampleDurations() {
        return sampleDurations;
    }

    @Override
    public long getDuration() {
        return duration;
    }

    @Override
    public List<CompositionTimeToSample.Entry> getCompositionTimeEntries() {
        return null;
    }

    @Override
    public long[] getSyncSamples() {
        return null;
    }

    @Override
    public List<org.mp4parser.boxes.iso14496.part12.SampleDependencyTypeBox.Entry>
            getSampleDependencies() {
        return null;
    }

    @Override
    public TrackMetaData getTrackMetaData() {
        return metaData;
    }

    @Override
    public String getHandler() {
        return "meta";
    }

    @Override
    public List<Sample> getSamples() {
        return samples;
    }

    @Override
    public SubSampleInformationBox getSubsampleInformationBox() {
        return null;
    }

    @Override
    public String getName() {
        return "Gyroflow telemetry";
    }

    @Override
    public List<Edit> getEdits() {
        return null;
    }

    @Override
    public Map<GroupEntry, long[]> getSampleGroups() {
        return Collections.emptyMap();
    }

    @Override
    public void close() throws IOException {
    }
}
