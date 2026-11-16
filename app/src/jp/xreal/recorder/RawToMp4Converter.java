package jp.xreal.recorder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
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
 * have their 7-byte ADTS header stripped before being written as audio samples.</p>
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
        List<Au> aus = readAccessUnits(hevcFile);
        if (aus.isEmpty()) {
            throw new IOException("生HEVCに映像フレームがありません");
        }
        byte[] csd = buildCsd(aus);
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
                audioTrack = muxer.addTrack(audioFormat);
            }
            muxer.start();

            long videoTimescaleUs = 0;
            // Use one video AU per 1/fps step at the container level. The recorded HEVC
            // carries no per-frame timestamps in raw form, so a constant cadence from the
            // known mode fps is the best available approximation.
            long stepUs = 1_000_000L / fps;
            for (int i = 0; i < aus.size(); i++) {
                Au au = aus.get(i);
                ByteBuffer buf = ByteBuffer.wrap(au.data);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                info.set(0, au.data.length, videoTimescaleUs, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                muxer.writeSampleData(videoTrack, buf, info);
                videoTimescaleUs += stepUs;
            }

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
            return new Result(aus.size(), writtenAudio);
        } finally {
            try { muxer.stop(); } catch (RuntimeException ignored) { }
            muxer.release();
        }
    }

    private static final class Au {
        final byte[] data;
        final int nalType;
        Au(byte[] data, int nalType) {
            this.data = data;
            this.nalType = nalType;
        }
    }

    private static final class AdtsFrame {
        final byte[] data;
        AdtsFrame(byte[] data) { this.data = data; }
    }

    /** Split Annex-B stream into NAL units; collect parameter sets for csd. */
    private static List<Au> readAccessUnits(File file) throws IOException {
        List<Au> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            byte[] buf = new byte[(int) Math.min(len, 16L * 1024 * 1024)];
            raf.seek(0);
            int n = raf.read(buf);
            if (n <= 0) {
                return result;
            }
            List<int[]> starts = new ArrayList<>(); // {offset, codeLength}
            int i = 0;
            while (i + 3 <= n) {
                if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) {
                    starts.add(new int[]{i, 3});
                    i += 3;
                } else if (i + 4 <= n && buf[i] == 0 && buf[i + 1] == 0
                        && buf[i + 2] == 0 && buf[i + 3] == 1) {
                    starts.add(new int[]{i, 4});
                    i += 4;
                } else {
                    i++;
                }
            }
            for (int k = 0; k < starts.size(); k++) {
                int offset = starts.get(k)[0] + starts.get(k)[1];
                int end = (k + 1 < starts.size()) ? starts.get(k + 1)[0] : n;
                if (end <= offset) {
                    continue;
                }
                byte[] nal = new byte[end - offset];
                System.arraycopy(buf, offset, nal, 0, nal.length);
                if (nal.length > 0) {
                    int type = (nal[0] >> 1) & 0x3f;
                    result.add(new Au(nal, type));
                }
            }
        }
        return result;
    }

    /** Build csd-0: Annex-B (00 00 00 01 + NAL) VPS/SPS/PPS, same as UvcSource.extractCsd. */
    private static byte[] buildCsd(List<Au> aus) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Au au : aus) {
            if (au.data.length == 0) {
                continue;
            }
            if (au.nalType == 32 || au.nalType == 33 || au.nalType == 34) {
                out.write(new byte[]{0, 0, 0, 1});
                out.write(au.data);
            }
        }
        return out.size() > 0 ? out.toByteArray() : null;
    }

    private static List<AdtsFrame> parseAdts(byte[] data) {
        List<AdtsFrame> result = new ArrayList<>();
        int i = 0;
        while (i + 7 <= data.length) {
            if ((data[i] & 0xff) != 0xff || (data[i + 1] & 0xf0) != 0xf0) {
                i++;
                continue;
            }
            int len = ((data[i + 3] & 0x03) << 11) | ((data[i + 4] & 0xff) << 3)
                    | ((data[i + 5] & 0xe0) >> 5);
            if (len < 7 || i + len > data.length) {
                i++;
                continue;
            }
            byte[] frame = new byte[len - 7];
            System.arraycopy(data, i + 7, frame, 0, frame.length);
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
