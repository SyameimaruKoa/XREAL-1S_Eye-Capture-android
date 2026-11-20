package com.syameimarukoa.xreal_recorder;

import java.util.Arrays;

public final class GyroCsvFormatterTest {
    public static void main(String[] args) {
        String row = GyroCsvFormatter.row(1234L, new float[]{0.1f, -0.2f, 0.3f},
                new float[]{0f, 1f, -1f});
        require("1234,0.100000,-0.200000,0.300000,0.00000,1.00000,-1.00000".equals(row),
                "row formatting");

        String csv = GyroCsvFormatter.format("VID_2026,11,20.mp4", 1763600000000L,
                Arrays.asList(row));
        require(csv.startsWith("GYROFLOW IMU LOG\nversion,1.3\nid,xreal_1s_imu\norientation,YxZ\n"),
                "mandatory header");
        require(csv.contains("videofilename,VID_2026_11_20.mp4\n"), "filename sanitized");
        require(csv.contains("tscale,0.001\ngscale,1.0\nascale,0.10197162\n"), "scale fields");
        require(csv.endsWith("t,gx,gy,gz,ax,ay,az\n" + row + "\n"), "column header and data");
        System.out.println("GyroCsvFormatterTest passed");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
