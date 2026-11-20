package com.syameimarukoa.xreal_recorder;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** Logs phone gyroscope and accelerometer samples for Gyroflow post-processing. */
final class MotionLogger implements SensorEventListener {
    private static final int MAX_ROWS = 200000;
    private static final float GRAVITY = 9.80665f;

    private final SensorManager manager;
    private final Sensor gyroscope;
    private final Sensor accelerometer;
    private final HandlerThread thread;
    private final Handler handler;
    private final List<String> rows = new ArrayList<>();
    private final float[] gyro = new float[3];
    private final float[] accel = new float[3];
    private boolean gyroSeen;
    private boolean accelSeen;
    private final long startNs;

    static MotionLogger start(Context context) {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) {
            return null;
        }
        Sensor gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyroscope == null) {
            return null;
        }
        Sensor accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        return new MotionLogger(manager, gyroscope, accelerometer);
    }

    private MotionLogger(SensorManager manager, Sensor gyroscope, Sensor accelerometer) {
        this.manager = manager;
        this.gyroscope = gyroscope;
        this.accelerometer = accelerometer;
        this.thread = new HandlerThread("xreal-motion");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
        this.startNs = SystemClock.elapsedRealtimeNanos();
        manager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME, handler);
        if (accelerometer != null) {
            manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, handler);
        } else {
            accelSeen = true;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        synchronized (rows) {
            if (event.sensor == gyroscope) {
                gyro[0] = event.values[0];
                gyro[1] = event.values[1];
                gyro[2] = event.values[2];
                gyroSeen = true;
            } else if (event.sensor == accelerometer) {
                accel[0] = event.values[0] / GRAVITY;
                accel[1] = event.values[1] / GRAVITY;
                accel[2] = event.values[2] / GRAVITY;
                accelSeen = true;
            }
            if (!gyroSeen || !accelSeen || rows.size() >= MAX_ROWS) {
                return;
            }
            long timeMs = Math.max(0L, (event.timestamp - startNs) / 1_000_000L);
            rows.add(GyroCsvFormatter.row(timeMs, gyro, accel));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    String formatCsv(String videoFilename, long captureTimeMs) {
        synchronized (rows) {
            return GyroCsvFormatter.format(videoFilename, captureTimeMs, new ArrayList<>(rows));
        }
    }

    int getSampleCount() {
        synchronized (rows) {
            return rows.size();
        }
    }

    void stop() {
        manager.unregisterListener(this);
        thread.quitSafely();
    }
}
