package com.syameimarukoa.xreal_recorder;

import java.util.List;
import java.util.Locale;

/** Builds a Gyroflow .gcsv sidecar from logged motion samples. */
final class GyroCsvFormatter {
    private GyroCsvFormatter() {
    }

    static String row(long timeMs, float[] gyro, float[] accel) {
        return String.format(Locale.US, "%d,%.6f,%.6f,%.6f,%.5f,%.5f,%.5f",
                timeMs, gyro[0], gyro[1], gyro[2], accel[0], accel[1], accel[2]);
    }

    static String format(String videoFilename, long captureTimeMs, List<String> rows) {
        String safeName = videoFilename == null ? "video.mp4" : videoFilename.replaceAll("[^A-Za-z0-9._-]", "_");
        StringBuilder sb = new StringBuilder();
        sb.append("GYROFLOW IMU LOG\n");
        sb.append("version,1.3\n");
        sb.append("id,xreal_1s_imu\n");
        sb.append("orientation,xYz\n");
        sb.append("note,glasses_imu\n");
        sb.append("timestamp,").append(captureTimeMs / 1000L).append('\n');
        sb.append("videofilename,").append(safeName).append('\n');
        sb.append("tscale,0.001\n");
        sb.append("gscale,1.0\n");
        sb.append("ascale,0.10197162\n");
        sb.append("t,gx,gy,gz,ax,ay,az\n");
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        return sb.toString();
    }
}
