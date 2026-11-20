package com.syameimarukoa.xreal_recorder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ImuReportParserTest {
    public static void main(String[] args) {
        require(ImuReportParser.isHeader(new byte[]{0x28, 0x36, 0, 0, 0, (byte) 0x80}),
                "valid IMU header");
        require(!ImuReportParser.isHeader(new byte[]{0x28, 0x36, 0, 0, 0, 0x7f}),
                "wrong body length");
        require(!ImuReportParser.isHeader(new byte[]{0x27, 0x36, 0, 0, 0, (byte) 0x80}),
                "wrong magic");

        byte[] body = body(ImuReportParser.REPORT_IMU, 1_234_567_890L);
        ImuReportParser.Sample sample = ImuReportParser.parse(body);
        require(sample != null, "IMU sample parsed");
        require(sample.timestampNs == 1_234_567_890L, "timestamp");
        require(close(sample.gyro[0], 0.1f) && close(sample.gyro[1], 0.2f)
                && close(sample.gyro[2], 0.3f), "gyro XYZ");
        require(close(sample.accel[0], 1f) && close(sample.accel[1], 9.8f)
                && close(sample.accel[2], 2f), "accel XYZ");
        require(close(sample.temperature, 47.5f), "temperature");

        byte[] magBody = body(ImuReportParser.REPORT_MAGNETOMETER, 1_234_567_890L);
        ImuReportParser.Sample mag = ImuReportParser.parse(magBody);
        require(mag != null, "magnetometer sample parsed");
        require(close(mag.magnetometer[0], 5f) && close(mag.magnetometer[1], 6f)
                && close(mag.magnetometer[2], 7f), "magnetometer XYZ");

        byte[] unknown = body(0x01, 0L);
        require(ImuReportParser.parse(unknown) == null, "unknown report type");
        System.out.println("ImuReportParserTest passed");
    }

    private static byte[] body(int reportType, long timestampNs) {
        byte[] body = new byte[ImuReportParser.BODY_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(8, timestampNs);
        buffer.putInt(0x18, reportType);
        buffer.putFloat(0x1c, 0.1f);
        buffer.putFloat(0x20, 0.2f);
        buffer.putFloat(0x24, 0.3f);
        buffer.putFloat(0x28, 1f);
        buffer.putFloat(0x2c, 9.8f);
        buffer.putFloat(0x30, 2f);
        buffer.putFloat(0x34, 5f);
        buffer.putFloat(0x38, 6f);
        buffer.putFloat(0x3c, 7f);
        buffer.putFloat(0x40, 47.5f);
        return body;
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) < 0.0001f;
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
