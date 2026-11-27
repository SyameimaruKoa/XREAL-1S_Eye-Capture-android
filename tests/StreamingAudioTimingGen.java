package com.syameimarukoa.xreal_recorder;

import java.io.FileOutputStream;

/** Generates an fMP4 with audio frames at 21.33 ms cadence for timing validation. */
public final class StreamingAudioTimingGen {
    public static void main(String[] args) throws Exception {
        try (FileOutputStream out = new FileOutputStream(args[0])) {
            StreamingGyroflowMuxer muxer = new StreamingGyroflowMuxer(out, new byte[0], 1600, 1200);
            StreamingMuxerSink sink = new StreamingMuxerSink(muxer);
            sink.setHeader(GyroflowProto.header(1600, 1200, 60f, 0L, 1000, "xYz"));
            long[] videoPts = {30281L, 47123L, 64001L, 80843L, 97679L};
            byte[] au = new byte[]{0, 0, 0, 1, 0x26, 0x01};
            // 16.8 ms video frames but 21.33 ms AAC: 11 frames across the clip.
            for (int i = 0; i < 11; i++) {
                sink.writeAudio(new byte[]{0x21, 0x10, (byte) i, 0x04, 0x00, 0x01, 0x02},
                        30281L + i * 21333L);
            }
            for (long t : videoPts) {
                sink.writeVideo(au, t, true);
            }
            sink.finish();
        }
        System.out.println("generated " + args[0]);
    }
}
