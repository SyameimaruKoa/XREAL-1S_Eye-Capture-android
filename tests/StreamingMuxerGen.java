package com.syameimarukoa.xreal_recorder;

import java.io.FileOutputStream;

/** Generates a tiny fMP4 with a gyrf track for offline format validation. */
public final class StreamingMuxerGen {
    public static void main(String[] args) throws Exception {
        byte[] csd = new byte[]{0x40, 0x01, 0x0c, 0x01, (byte) 0xff, (byte) 0xff, 0x01, 0x60};
        try (FileOutputStream out = new FileOutputStream(args[0])) {
            StreamingGyroflowMuxer muxer = new StreamingGyroflowMuxer(out, csd, 1600, 1200);
            muxer.start();
            byte[] video = new byte[]{0, 0, 0, 1, 0x26, 0x01};
            byte[] audio = new byte[]{0x21, 0x10, 0x04};
            byte[] gyro = GyroflowProto.main(GyroflowProto.header(1600, 1200, 60f,
                    16_666L, 1000, "xYz"),
                    GyroflowProto.frame(1, 0, 16_666,
                            java.util.Collections.emptyList(), 1600, 1200));
            for (int i = 0; i < 30; i++) {
                muxer.writeSample(video, audio, gyro, 16_666L);
            }
            muxer.stop();
        }
        System.out.println("generated " + args[0]);
    }
}
