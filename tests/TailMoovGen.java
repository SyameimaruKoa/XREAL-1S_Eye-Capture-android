package com.syameimarukoa.xreal_recorder;

import java.io.FileOutputStream;

/** Generates an fMP4 with a tail moov carrying the gyrf sample table, for validation. */
public final class TailMoovGen {
    public static void main(String[] args) throws Exception {
        // Generate a normal fMP4 first (current StreamingGyroflowMuxer).
        byte[] csd = new byte[]{0x40, 0x01, 0x0c, 0x01, (byte) 0xff, (byte) 0xff, 0x01, 0x60};
        try (FileOutputStream out = new FileOutputStream(args[0])) {
            StreamingGyroflowMuxer muxer = new StreamingGyroflowMuxer(out, csd, 1600, 1200);
            muxer.start();
            byte[] video = new byte[]{0, 0, 0, 1, 0x26, 0x01};
            byte[] audio = new byte[]{0x21, 0x10, 0x04};
            for (int i = 0; i < 30; i++) {
                byte[] gyro = GyroflowProto.main(i == 0 ? GyroflowProto.header(1600, 1200, 60f,
                        500_000L, 1000, "xYz") : null,
                        GyroflowProto.frame(i + 1, i * 16_666.0, (i + 1) * 16_666.0,
                                java.util.Collections.emptyList(), 1600, 1200));
                muxer.writeSample(video, audio, gyro, 16_666L);
            }
            muxer.stop();
        }
        System.out.println("generated " + args[0]);
    }
}
