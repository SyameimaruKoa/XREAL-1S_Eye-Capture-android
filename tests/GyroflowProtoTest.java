package com.syameimarukoa.xreal_recorder;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

public final class GyroflowProtoTest {
    public static void main(String[] args) {
        GyroflowProto.ImuSample sample = new GyroflowProto.ImuSample(
                1234567.0, 0.5f, -0.25f, 0.125f, 1f, 9.8f, 2f);
        byte[] frame = GyroflowProto.frame(1, 1000.0, 2000.0,
                Collections.singletonList(sample), 1600, 1200);
        byte[] lens = readBytes(frame, 16);
        require(lens.length > 0, "lens data present");
        boolean noDistortion = false;
        for (int i = 0; i + 1 < lens.length; i++) {
            if ((lens[i] & 0xff) == 0x2a && lens[i + 1] == 0x00) {
                noDistortion = true;
                break;
            }
        }
        require(noDistortion, "no_distortion declared");

        // fx must scale with resolution for a fixed FOV.
        byte[] lens1600 = readBytes(GyroflowProto.frame(1, 0, 1,
                Collections.singletonList(sample), 1600, 1200), 16);
        byte[] lens800 = readBytes(GyroflowProto.frame(1, 0, 1,
                Collections.singletonList(sample), 800, 600), 16);
        double fx1600 = firstFloat(lens1600);
        double fx800 = firstFloat(lens800);
        require(Math.abs(fx1600 / fx800 - 2.0) < 0.01,
                "fx scales with resolution: " + fx1600 + " / " + fx800);
        byte[] main = GyroflowProto.main(GyroflowProto.header(1920, 1080, 30f,
                33333333L, 1000, "YxZ"), frame);

        require(main.length > 0, "encoded message");
        String magic = readString(main, 1);
        require("GyroflowProtobuf".equals(magic), "magic string: " + magic);
        require(readVarint(main, 2) == 1, "protocol version");
        byte[] nested = readBytes(main, 4);
        require(nested.length > 0, "frame message");
        require(readDouble(nested, 1) == 1000.0, "start timestamp");
        require(readDouble(nested, 2) == 2000.0, "end timestamp");
        require(readVarint(nested, 3) == 1, "frame number");
        byte[] imu = readBytes(nested, 17);
        require(imu.length > 0, "imu sample");
        require(Math.abs(readDouble(imu, 1) - 1234567.0) < 0.001, "sample timestamp");
        require(Math.abs(readFloat(imu, 2) - (float) (0.5 * 180.0 / Math.PI)) < 0.001,
                "gyro X in deg/s");

        // Header -> CameraMetadata -> imu_rotation quaternion must be +90 deg (Z).
        byte[] header = GyroflowProto.header(1920, 1080, 30f, 33333333L, 1000, "xYz");
        byte[] camera = readBytes(header, 1);
        byte[] rotation = readBytes(camera, 14);
        require(Math.abs(readFloat(rotation, 1) - 0.70710678f) < 1e-6f,
                "roll quaternion w");
        require(Math.abs(readFloat(rotation, 4) - 0.70710678f) < 1e-6f,
                "roll quaternion z");
        System.out.println("GyroflowProtoTest passed");
    }

    private static int key(byte[] data, int[] pos, int[] field, int[] wire) {
        int value = 0;
        int shift = 0;
        int index = pos[0];
        while (index < data.length) {
            int b = data[index++] & 0xff;
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        pos[0] = index;
        field[0] = value >>> 3;
        wire[0] = value & 7;
        return value;
    }

    private static float firstFloat(byte[] lens) {
        // field 1 (camera_intrinsic_matrix) repeated float: tag 0x0d then 4 LE bytes
        for (int i = 0; i + 4 < lens.length; i++) {
            if ((lens[i] & 0xff) == 0x0d) {
                return Float.intBitsToFloat(
                        (lens[i + 1] & 0xff) | ((lens[i + 2] & 0xff) << 8)
                                | ((lens[i + 3] & 0xff) << 16)
                                | ((lens[i + 4] & 0xff) << 24));
            }
        }
        throw new AssertionError("no float in lens data");
    }

    private static byte[] readBytes(byte[] data, int wanted) {
        int[] pos = {0};
        int[] field = {0};
        int[] wire = {0};
        while (pos[0] < data.length) {
            key(data, pos, field, wire);
            if (wire[0] == 2) {
                int length = 0;
                int shift = 0;
                while (true) {
                    int b = data[pos[0]++] & 0xff;
                    length |= (b & 0x7f) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                byte[] value = new byte[length];
                System.arraycopy(data, pos[0], value, 0, length);
                pos[0] += length;
                if (field[0] == wanted) {
                    return value;
                }
            } else if (wire[0] == 0) {
                while ((data[pos[0]++] & 0x80) != 0) {
                }
            } else if (wire[0] == 1) {
                pos[0] += 8;
            } else if (wire[0] == 5) {
                pos[0] += 4;
            }
        }
        throw new AssertionError("field not found: " + wanted);
    }

    private static long readVarint(byte[] data, int wanted) {
        int[] pos = {0};
        int[] field = {0};
        int[] wire = {0};
        while (pos[0] < data.length) {
            key(data, pos, field, wire);
            if (wire[0] == 0) {
                long value = 0;
                int shift = 0;
                while (true) {
                    int b = data[pos[0]++] & 0xff;
                    value |= ((long) (b & 0x7f)) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                if (field[0] == wanted) {
                    return value;
                }
            } else if (wire[0] == 2) {
                int length = 0;
                int shift = 0;
                while (true) {
                    int b = data[pos[0]++] & 0xff;
                    length |= (b & 0x7f) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                pos[0] += length;
            } else if (wire[0] == 1) {
                pos[0] += 8;
            } else if (wire[0] == 5) {
                pos[0] += 4;
            }
        }
        throw new AssertionError("field not found: " + wanted);
    }

    private static double readDouble(byte[] data, int wanted) {
        byte[] raw = readFixed(data, wanted, 1);
        return java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).getDouble();
    }

    private static float readFloat(byte[] data, int wanted) {
        byte[] raw = readFixed(data, wanted, 5);
        return java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    private static byte[] readFixed(byte[] data, int wanted, int expectedWire) {
        int[] pos = {0};
        int[] field = {0};
        int[] wire = {0};
        while (pos[0] < data.length) {
            key(data, pos, field, wire);
            if (wire[0] == 2) {
                int length = 0;
                int shift = 0;
                while (true) {
                    int b = data[pos[0]++] & 0xff;
                    length |= (b & 0x7f) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                pos[0] += length;
            } else if (wire[0] == 0) {
                while ((data[pos[0]++] & 0x80) != 0) {
                }
            } else if (wire[0] == expectedWire && field[0] == wanted) {
                int size = wire[0] == 1 ? 8 : 4;
                byte[] value = new byte[size];
                System.arraycopy(data, pos[0], value, 0, size);
                return value;
            } else if (wire[0] == 1) {
                pos[0] += 8;
            } else if (wire[0] == 5) {
                pos[0] += 4;
            }
        }
        throw new AssertionError("field not found: " + wanted);
    }

    private static String readString(byte[] data, int wanted) {
        return new String(readBytes(data, wanted), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
