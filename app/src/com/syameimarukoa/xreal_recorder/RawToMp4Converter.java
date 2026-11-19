package com.syameimarukoa.xreal_recorder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts raw Annex-B HEVC + ADTS AAC sidecars into an MP4 without re-encoding.
 *
 * <p>Used by save mode 2: the recorder writes only raw files while capturing, then
 * calls this converter on normal stop (or on the next launch after a crash).
 * HEVC access units are split on Annex-B start codes; VPS/SPS/PPS NALs form the
 * csd-0 codec config, and the remaining NALs become video samples. AAC frames
 * are stored with a 4-byte length prefix (platform encoder does not emit ADTS)
 * and written as raw AAC samples with a synthesized AudioSpecificConfig.</p>
 */
final class RawToMp4Converter {
    private static final String TAG = "XrealConvert";
    private static final int AUDIO_FRAME_SAMPLES = 1024;

    static final class Result {
        final int auCount;
        final int audioFrames;
        Result(int auCount, int audioFrames) {
            this.auCount = auCount;
            this.audioFrames = audioFrames;
        }
    }

    static Result convert(File hevcFile, File aacFile, File outputFile, int width, int height, int fps)
            throws IOException {
        // Two passes keep peak memory bounded: the first pass collects only the
        // small VPS/SPS/PPS parameter sets, the second streams AUs into the muxer
        // one at a time. Holding every AU of a long recording (over 1 GB) in a
        // List caused an OutOfMemoryError during next-launch recovery.
        byte[] csd = readCsd(hevcFile);
        if (csd == null) {
            throw new IOException("HEVCのVPS/SPS/PPSを取得できません");
        }

        MediaMuxer muxer = new MediaMuxer(outputFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
            videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            int videoTrack = muxer.addTrack(videoFormat);

            int audioTrack = -1;
            byte[] audioData = aacFile != null && aacFile.exists() ? readAll(aacFile) : new byte[0];
            List<AdtsFrame> aacFrames = parseAdts(audioData);
            if (!aacFrames.isEmpty()) {
                MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1);
                audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                // Build AudioSpecificConfig from the encoder's fixed settings:
                // AAC LC (5b 00010), 48 kHz index 3 (4b 0011), mono (4b 0001),
                // padded to 16 bits -> 0x11 0x88.
                byte[] asc = new byte[]{0x11, (byte) 0x88};
                audioFormat.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
                audioTrack = muxer.addTrack(audioFormat);
            }
            muxer.start();

            // Use one video AU per 1/fps step at the container level. The recorded HEVC
            // carries no per-frame timestamps in raw form, so a constant cadence from the
            // known mode fps is the best available approximation.
            long stepUs = 1_000_000L / fps;
            final long[] videoTimescaleUs = {0};
            long auCount = forEachNal(hevcFile, false, (data, type) -> {
                ByteBuffer buf = ByteBuffer.wrap(data);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                info.set(0, data.length, videoTimescaleUs[0], MediaCodec.BUFFER_FLAG_KEY_FRAME);
                muxer.writeSampleData(videoTrack, buf, info);
                videoTimescaleUs[0] += stepUs;
            });

            int writtenAudio = 0;
            if (audioTrack >= 0) {
                long audioPts = 0;
                long audioStepUs = AUDIO_FRAME_SAMPLES * 1_000_000L / 48000;
                for (AdtsFrame frame : aacFrames) {
                    ByteBuffer buf = ByteBuffer.wrap(frame.data);
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    info.set(0, frame.data.length, audioPts, 0);
                    muxer.writeSampleData(audioTrack, buf, info);
                    audioPts += audioStepUs;
                    writtenAudio++;
                }
            }
            return new Result((int) auCount, writtenAudio);
        } finally {
            try { muxer.stop(); } catch (RuntimeException ignored) { }
            muxer.release();
        }
    }

    private static final class AdtsFrame {
        final byte[] data;
        AdtsFrame(byte[] data) { this.data = data; }
    }

    private interface NalConsumer {
        void accept(byte[] data, int type) throws IOException;
    }

    /**
     * Streams Annex-B NAL units to a consumer without retaining them all. Returns
     * the number of NAL units seen. When csdOnly is true, only VPS/SPS/PPS (types
     * 32/33/34) are passed to the consumer; the count still covers every NAL.
     */
    private static long forEachNal(File file, boolean csdOnly, NalConsumer consumer)
            throws IOException {
        long count = 0;
        final int CHUNK = 64 * 1024;
        try (java.io.BufferedInputStream in =
                     new java.io.BufferedInputStream(new java.io.FileInputStream(file), CHUNK)) {
            java.io.ByteArrayOutputStream nal = new java.io.ByteArrayOutputStream(CHUNK);
            byte[] buf = new byte[CHUNK];
            int zeros = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                for (int i = 0; i < n; i++) {
                    int b = buf[i] & 0xff;
                    if (b == 0) {
                        zeros++;
                        continue;
                    }
                    if (b == 1 && zeros >= 2) {
                        // start code found (2 or 3 zero bytes before the 1)
                        if (nal.size() > 0) {
                            byte[] data = nal.toByteArray();
                            int type = (data[0] >> 1) & 0x3f;
                            if (!csdOnly || type == 32 || type == 33 || type == 34) {
                                consumer.accept(data, type);
                            }
                            count++;
                            nal.reset();
                        }
                        zeros = 0;
                        continue;
                    }
                    // ordinary data byte: flush accumulated zeros then the byte
                    for (int z = 0; z < zeros; z++) {
                        nal.write(0);
                    }
                    zeros = 0;
                    nal.write(b);
                }
            }
            if (nal.size() > 0) {
                byte[] data = nal.toByteArray();
                int type = (data[0] >> 1) & 0x3f;
                if (!csdOnly || type == 32 || type == 33 || type == 34) {
                    consumer.accept(data, type);
                }
                count++;
            }
        }
        return count;
    }

    /** Build csd-0: Annex-B (00 00 00 01 + NAL) VPS/SPS/PPS, same as UvcSource.extractCsd. */
    private static byte[] readCsd(File file) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        forEachNal(file, true, (data, type) -> {
            if (data.length > 0) {
                out.write(new byte[]{0, 0, 0, 1});
                out.write(data);
            }
        });
        return out.size() > 0 ? out.toByteArray() : null;
    }

    /** Parse our sidecar format: 4-byte big-endian length followed by raw AAC frames. */
    private static List<AdtsFrame> parseAdts(byte[] data) {
        List<AdtsFrame> result = new ArrayList<>();
        int i = 0;
        while (i + 4 <= data.length) {
            int len = ((data[i] & 0xff) << 24) | ((data[i + 1] & 0xff) << 16)
                    | ((data[i + 2] & 0xff) << 8) | (data[i + 3] & 0xff);
            i += 4;
            if (len <= 0 || len > 100000 || i + len > data.length) {
                break;
            }
            byte[] frame = new byte[len];
            System.arraycopy(data, i, frame, 0, len);
            result.add(new AdtsFrame(frame));
            i += len;
        }
        return result;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return data;
        }
    }
}
