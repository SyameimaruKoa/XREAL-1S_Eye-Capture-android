package com.syameimarukoa.xreal_recorder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Parses the XREAL 1S TCP 52998 IMU report frames. */
final class ImuReportParser {
    static final int MAGIC = 0x2836;
    static final int BODY_SIZE = 128;
    static final int REPORT_IMU = 0x0b;
    static final int REPORT_MAGNETOMETER = 0x04;

    private ImuReportParser() {
    }

    static boolean isHeader(byte[] header) {
        if (header == null || header.length < 6) {
            return false;
        }
        int magic = ((header[0] & 0xff) << 8) | (header[1] & 0xff);
        int length = ((header[2] & 0xff) << 24) | ((header[3] & 0xff) << 16)
                | ((header[4] & 0xff) << 8) | (header[5] & 0xff);
        return magic == MAGIC && length == BODY_SIZE;
    }

    static Sample parse(byte[] body) {
        if (body == null || body.length < BODY_SIZE) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        int reportType = buffer.getInt(0x18);
        if (reportType != REPORT_IMU && reportType != REPORT_MAGNETOMETER) {
            return null;
        }
        float temperature = buffer.getFloat(0x40);
        long timestampNs = buffer.getLong(8);
        float[] gyro = new float[3];
        float[] accel = new float[3];
        float[] magnetometer = new float[3];
        if (reportType == REPORT_IMU) {
            for (int i = 0; i < 3; i++) {
                gyro[i] = buffer.getFloat(0x1c + i * 4);
                accel[i] = buffer.getFloat(0x28 + i * 4);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                magnetometer[i] = buffer.getFloat(0x34 + i * 4);
            }
        }
        return new Sample(timestampNs, reportType, gyro, accel, magnetometer, temperature);
    }

    static final class Sample {
        final long timestampNs;
        final int reportType;
        final float[] gyro;
        final float[] accel;
        final float[] magnetometer;
        final float temperature;

        Sample(long timestampNs, int reportType, float[] gyro, float[] accel,
               float[] magnetometer, float temperature) {
            this.timestampNs = timestampNs;
            this.reportType = reportType;
            this.gyro = gyro;
            this.accel = accel;
            this.magnetometer = magnetometer;
            this.temperature = temperature;
        }
    }
}
