package com.syameimarukoa.xreal_recorder;

import java.io.FileOutputStream;

/** Generates an fMP4 through StreamingMuxerSink with non-60fps PTS for timing validation. */
public final class StreamingSinkTimingGen {
    public static void main(String[] args) throws Exception {
        try (FileOutputStream out = new FileOutputStream(args[0])) {
            StreamingGyroflowMuxer muxer = new StreamingGyroflowMuxer(out, new byte[0], 1600, 1200);
            StreamingMuxerSink sink = new StreamingMuxerSink(muxer);
            sink.setHeader(GyroflowProto.header(1600, 1200, 60f, 0L, 1000, "xYz"));
            long[] pts = {30281L, 47123L, 64001L, 80843L, 97679L};
            byte[] au = new byte[]{0, 0, 0, 1, 0x26, 0x01};
            for (long t : pts) {
                sink.onImuSample(new GyroflowProto.ImuSample((double) (t - pts[0]),
                        0.01f, 0f, 0f, 0f, 9.8f, 0f));
                sink.writeVideo(au, t, true);
            }
            sink.finish();
        }
        System.out.println("generated " + args[0]);
    }
}
