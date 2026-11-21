package com.syameimarukoa.xreal_recorder;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

public final class GyroflowProtoTest {
    public static void main(String[] args) {
        GyroflowProto.ImuSample sample = new GyroflowProto.ImuSample(
                1234567.0, 0.5f, -0.25f, 0.125f, 1f, 9.8f, 2f);
        byte[] frame = GyroflowProto.frame(1, 1000.0, 2000.0,
                Collections.singletonList(sample));
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

        // Header -> CameraMetadata -> imu_rotation quaternion must be roll -180 deg.
        byte[] header = GyroflowProto.header(1920, 1080, 30f, 33333333L, 1000, "xYz");
        byte[] camera = readBytes(header, 1);
        byte[] rotation = readBytes(camera, 14);
        require(readFloat(rotation, 1) == 0f, "roll quaternion w = 0");
        require(readFloat(rotation, 4) == -1f, "roll quaternion z = -1");
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
