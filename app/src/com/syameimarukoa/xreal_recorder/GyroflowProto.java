package com.syameimarukoa.xreal_recorder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Minimal protobuf writer for the Gyroflow telemetry schema. */
final class GyroflowProto {
    private static final double RAD_TO_DEG = 180.0 / Math.PI;
    // XREAL 1S horizontal FOV ~77.3 deg at 1600x1200 (fx ~1000 px). Keeping FOV
    // as the constant makes the focal length scale correctly for any resolution.
    private static final double HORIZONTAL_FOV_DEG = 77.3;

    private GyroflowProto() {
    }

    static byte[] header(int width, int height, float fps, long durationUs, int imuRateHz,
                         String orientation) {
        ByteArrayOutputStream camera = new ByteArrayOutputStream();
        writeString(camera, 1, "XREAL");
        writeString(camera, 2, "1S");
        writeString(camera, 5, "XREAL");
        writeString(camera, 6, "1S");
        writeString(camera, 13, orientation);
        // imu_rotation: glasses-to-camera body alignment. Device-verified by the
        // user: field14 -180 deg needed a further -90 deg in Gyroflow's
        // Motion data -> Rotation to level the horizon, so the total correction
        // is -270 deg == +90 deg about Z. Baking +90 here lets Rotation stay 0.
        // Gyroflow applies this after the axis permutation. Quaternion (w,0,0,z).
        ByteArrayOutputStream rotation = new ByteArrayOutputStream();
        writeFloat(rotation, 1, 0.70710678f);
        writeFloat(rotation, 2, 0f);
        writeFloat(rotation, 3, 0f);
        writeFloat(rotation, 4, 0.70710678f);
        writeBytes(camera, 14, rotation.toByteArray());

        ByteArrayOutputStream clip = new ByteArrayOutputStream();
        writeVarint(clip, 1, width);
        writeVarint(clip, 2, height);
        writeDouble(clip, 3, (double) durationUs);
        writeFloat(clip, 4, fps);
        writeFloat(clip, 5, fps);
        writeFloat(clip, 6, fps);
        writeVarint(clip, 8, imuRateHz);
        writeFloat(clip, 10, 1f);

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeBytes(header, 1, camera.toByteArray());
        writeBytes(header, 2, clip.toByteArray());
        return header.toByteArray();
    }

    static byte[] frame(int frameNumber, double startUs, double endUs,
                        List<ImuSample> samples, int width, int height) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeDouble(frame, 1, startUs);
        writeDouble(frame, 2, endUs);
        writeVarint(frame, 3, frameNumber);
        // XREAL corrects lens distortion in-camera, so declare a pinhole model.
        // Supplying LensData also avoids Gyroflow's "lens profile not loaded" warning.
        ByteArrayOutputStream lens = new ByteArrayOutputStream();
        double halfFovRad = Math.toRadians(HORIZONTAL_FOV_DEG) / 2.0;
        float fx = (float) (width / (2.0 * Math.tan(halfFovRad)));
        writeFloat(lens, 1, fx);
        writeFloat(lens, 1, fx);
        writeFloat(lens, 1, width / 2f);
        writeFloat(lens, 1, 0f);
        writeFloat(lens, 1, fx);
        writeFloat(lens, 1, height / 2f);
        writeFloat(lens, 1, 0f);
        writeFloat(lens, 1, 0f);
        writeFloat(lens, 1, 1f);
        lens.write(0x2a); // field 5 (no_distortion), wire type 2
        lens.write(0x00); // empty message
        writeBytes(frame, 16, lens.toByteArray());
        for (ImuSample sample : samples) {
            writeBytes(frame, 17, imu(sample));
        }
        return frame.toByteArray();
    }

    static byte[] main(byte[] header, byte[] frame) {
        ByteArrayOutputStream main = new ByteArrayOutputStream();
        writeString(main, 1, "GyroflowProtobuf");
        writeVarint(main, 2, 1);
        if (header != null) {
            writeBytes(main, 3, header);
        }
        writeBytes(main, 4, frame);
        return main.toByteArray();
    }

    private static byte[] imu(ImuSample s) {
        ByteArrayOutputStream imu = new ByteArrayOutputStream();
        writeDouble(imu, 1, s.timeUs);
        writeFloat(imu, 2, (float) (s.gyroX * RAD_TO_DEG));
        writeFloat(imu, 3, (float) (s.gyroY * RAD_TO_DEG));
        writeFloat(imu, 4, (float) (s.gyroZ * RAD_TO_DEG));
        writeFloat(imu, 5, s.accelX);
        writeFloat(imu, 6, s.accelY);
        writeFloat(imu, 7, s.accelZ);
        return imu.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) {
        writeBytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(ByteArrayOutputStream out, int field, byte[] value) {
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, field << 3);
        writeVarint(out, value);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while (value >= 0x80) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void writeFloat(ByteArrayOutputStream out, int field, float value) {
        writeVarint(out, (field << 3) | 5);
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array();
        out.write(bytes, 0, bytes.length);
    }

    private static void writeDouble(ByteArrayOutputStream out, int field, double value) {
        writeVarint(out, (field << 3) | 1);
        byte[] bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array();
        out.write(bytes, 0, bytes.length);
    }

    static final class ImuSample {
        final double timeUs;
        final float gyroX;
        final float gyroY;
        final float gyroZ;
        final float accelX;
        final float accelY;
        final float accelZ;

        ImuSample(double timeUs, float gyroX, float gyroY, float gyroZ,
                  float accelX, float accelY, float accelZ) {
            this.timeUs = timeUs;
            this.gyroX = gyroX;
            this.gyroY = gyroY;
            this.gyroZ = gyroZ;
            this.accelX = accelX;
            this.accelY = accelY;
            this.accelZ = accelZ;
        }

        ImuSample shifted(double originUs) {
            return new ImuSample(timeUs - originUs, gyroX, gyroY, gyroZ,
                    accelX, accelY, accelZ);
        }
    }
}
