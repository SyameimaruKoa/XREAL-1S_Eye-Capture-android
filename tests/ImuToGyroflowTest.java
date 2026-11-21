package com.syameimarukoa.xreal_recorder;

import java.util.Collections;
import java.util.List;

/** End-to-end test using a real IMU report captured from XREAL 1S over TCP. */
public final class ImuToGyroflowTest {
    public static void main(String[] args) {
        byte[] body = realReport();
        ImuReportParser.Sample sample = ImuReportParser.parse(body);
        require(sample != null, "real report parsed");
        require(sample.reportType == ImuReportParser.REPORT_IMU, "IMU type");

        GyroflowProto.ImuSample g = new GyroflowProto.ImuSample(
                sample.timestampNs / 1000.0,
                sample.gyro[0], sample.gyro[1], sample.gyro[2],
                sample.accel[0], sample.accel[1], sample.accel[2]);
        List<GyroflowProto.ImuSample> samples = Collections.singletonList(g);

        byte[] hdr = GyroflowProto.header(1600, 1200, 60f, 16_666_667L, 1000, "xYz");
        byte[] frame = GyroflowProto.frame(1, 0.0, 16_666.667, samples, 1600, 1200);
        byte[] main = GyroflowProto.main(hdr, frame);
        require(main.length > 0, "protobuf generated from real report");

        // The frame must carry the IMU sample (field 17) and lens data (field 16).
        boolean imu = false;
        boolean lens = false;
        int[] pos = {0};
        while (pos[0] < frame.length) {
            int[] f = {0}, w = {0};
            key(frame, pos, f, w);
            if (w[0] == 2) {
                int len = 0, shift = 0;
                while (true) {
                    int b = frame[pos[0]++] & 0xff;
                    len |= (b & 0x7f) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                pos[0] += len;
                if (f[0] == 17) imu = true;
                if (f[0] == 16) lens = true;
            } else if (w[0] == 0) {
                while ((frame[pos[0]++] & 0x80) != 0) { }
            } else if (w[0] == 5) pos[0] += 4;
            else if (w[0] == 1) pos[0] += 8;
            else break;
        }
        require(imu, "IMU sample embedded in protobuf frame");
        require(lens, "lens data embedded in protobuf frame");
        System.out.println("ImuToGyroflowTest passed");
    }

    private static byte[] realReport() {
        int[] v = {
        0x08,0xab,0xff,0x00,0x00,0x00,0x00,0x00,0xad,0x90,0xda,0x21,
        0x2e,0x04,0x00,0x00,0x72,0x34,0x00,0x00,0x00,0x00,0x00,0x00,
        0x0b,0x00,0x00,0x00,0x6e,0x88,0xae,0x3a,0x63,0x14,0x9d,0x3a,
        0x58,0xa0,0x0b,0xbb,0x66,0x96,0x39,0x3f,0x66,0xc1,0xbe,0xc0,
        0x66,0x84,0xf9,0x40,0x00,0x00,0x48,0xc5,0x00,0x00,0x48,0xc5,
        0x00,0x00,0x48,0xc5,0x00,0x80,0x26,0x42,0x02,0x7f,0x16,0x46,
        0x00,0x40,0x1f,0x00,0x00,0x40,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0xf0,0x7e,0x0e,0x00,0x00,0x00,0x00,
        0x60,0x85,0x20,0x8e,0x7f,0x00,0x00,0x00,0xd0,0x64,0x64,0x8e,
        0x7f,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00
        };
        byte[] out = new byte[ImuReportParser.BODY_SIZE];
        for (int i = 0; i < out.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static void key(byte[] data, int[] pos, int[] field, int[] wire) {
        int value = 0, shift = 0;
        int index = pos[0];
        while (index < data.length) {
            int b = data[index++] & 0xff;
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        pos[0] = index;
        field[0] = value >>> 3;
        wire[0] = value & 7;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
