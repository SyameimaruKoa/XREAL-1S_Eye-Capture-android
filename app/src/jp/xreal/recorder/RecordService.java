package jp.xreal.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class RecordService extends Service {
    private static final String TAG = "XrealRec";
    private static final String CHANNEL_ID = "xreal_recording";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START = "jp.xreal.recorder.START";
    public static final String ACTION_STOP = "jp.xreal.recorder.STOP";
    public static final String EXTRA_DEVICE = "device";

    private static volatile String status = "待機中";
    private static volatile boolean recording;

    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean audioSilenceDetected;
    private volatile boolean audioSilenceEverDetected;
    private UsbDevice device;

    public static String getStatus() {
        return status;
    }

    public static boolean isRecording() {
        return recording;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "XREAL recording", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("UVC + USB microphone recording");
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            if (recording) {
                return START_NOT_STICKY;
            }
            device = intent.getParcelableExtra(EXTRA_DEVICE);
            if (device == null) {
                status = "エラー: USBデバイスがありません";
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, buildNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification());
                }
            } catch (Throwable t) {
                status = "Foreground Service開始失敗: " + t;
                Log.e(TAG, "startForeground failed", t);
                stopSelf();
                return START_NOT_STICKY;
            }

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XrealRec:record");
            wakeLock.acquire(2 * 60 * 60 * 1000L);

            recording = true;
            stopRequested = false;
            audioSilenceDetected = false;
            audioSilenceEverDetected = false;
            status = "開始準備中...";
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runRecording();
                }
            }, "xreal-record");
            worker.start();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            if (recording) {
                status = "停止処理中...";
            }
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void runRecording() {
        UvcSource uvc = null;
        AudioSource audio = null;
        MuxerSink muxer = null;
        FileOutputStream hevcOut = null;
        File mp4File = null;
        File hevcFile = null;
        File wavFile = null;

        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null) {
                dir = getFilesDir();
            }
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("cannot create output directory");
            }
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            mp4File = new File(dir, "xreal_" + ts + ".mp4");
            hevcFile = new File(dir, "xreal_" + ts + ".hevc");
            wavFile = new File(dir, "xreal_" + ts + ".wav");

            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            uvc = new UvcSource(um, device);
            uvc.open();

            muxer = new MuxerSink(mp4File.getAbsolutePath());
            hevcOut = new FileOutputStream(hevcFile);

            final MuxerSink finalMuxer = muxer;
            final FileOutputStream finalHevc = hevcOut;
            final long t0Ns = SystemClock.elapsedRealtimeNanos();

            final boolean[] csdSent = {false};
            uvc.start(new UvcSource.Listener() {
                @Override
                public void onCsd(byte[] csd) {
                    if (csdSent[0]) {
                        return;
                    }
                    csdSent[0] = true;
                    MediaFormat fmt = MediaFormat.createVideoFormat(
                            MediaFormat.MIMETYPE_VIDEO_HEVC, 2048, 1512);
                    fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd));
                    fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
                    finalMuxer.addVideoTrack(fmt);
                }

                @Override
                public void onAccessUnit(byte[] annexB, long arrivalNs) {
                    try {
                        finalHevc.write(annexB);
                    } catch (Exception e) {
                        throw new IllegalStateException("RAW HEVC write failed: "
                                + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                    }
                    long ptsUs = Math.max(0L, (arrivalNs - t0Ns) / 1000L);
                    finalMuxer.writeVideo(annexB, ptsUs, UvcSource.isKeyFrame(annexB));
                }

                @Override
                public void onError(String message) {
                    status = "UVCエラー: " + message;
                    Log.e(TAG, "UVC error: " + message);
                }
            });

            // Required start order: UVC first, then AudioRecord after 2 s.
            Thread.sleep(2000);

            final AudioSource finalAudio = new AudioSource(this, t0Ns, muxer, wavFile,
                    new AudioSource.Listener() {
                        @Override
                        public void onError(String message) {
                            status = "音声エラー: " + message;
                            Log.e(TAG, "audio error: " + message);
                        }

                        @Override
                        public void onSilenceChanged(boolean silent) {
                            audioSilenceDetected = silent;
                            if (silent) {
                                audioSilenceEverDetected = true;
                            }
                            status = silent ? "録画中: USB音声が10秒以上すべてゼロです"
                                    : "録画中: USB音声の入力を検出";
                            Log.w(TAG, "USB audio exact-zero warning=" + silent);
                            NotificationManager nm = (NotificationManager)
                                    getSystemService(Context.NOTIFICATION_SERVICE);
                            nm.notify(NOTIFICATION_ID, buildNotification());
                        }
                    });
            audio = finalAudio;
            audio.start();
            status = "録画中: " + mp4File.getName();

            while (!stopRequested && !uvc.isFailed() && !audio.isFailed() && !muxer.isFailed()) {
                Thread.sleep(200);
            }

            audio.stop();
            uvc.stop();
            if (audio.isFailed()) {
                muxer.startVideoOnly();
            }
            muxer.finish();
            hevcOut.close();
            hevcOut = null;

            String result = String.format(Locale.US,
                    "%s\nAU=%d audioFrames=%d\nWAV=%s\nHEVC=%s",
                    mp4File.getAbsolutePath(), uvc.getAccessUnits(),
                    audio.getFramesWritten(), wavFile.getAbsolutePath(),
                    hevcFile.getAbsolutePath());
            if (uvc.isFailed()) {
                status = "UVC停止: " + uvc.getError() + "\n" + result;
            } else if (audio.isFailed()) {
                status = "音声停止: " + audio.getError() + "\n" + result;
            } else if (muxer.isFailed()) {
                status = "Muxerエラー: " + muxer.getError() + "\n" + result;
            } else {
                status = "保存完了: " + result;
            }
            if (muxer.isFailed() && (uvc.isFailed() || audio.isFailed())) {
                status += "\nMuxerエラー: " + muxer.getError();
            }
            if (audioSilenceEverDetected) {
                status += "\n注意: USB音声が10秒以上すべてゼロでした";
            }
        } catch (Throwable t) {
            status = "録画エラー: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "recording failed", t);
            if (mp4File != null) {
                status += "\n" + mp4File.getAbsolutePath();
            }
        } finally {
            if (audio != null) {
                try {
                    audio.stop();
                } catch (Throwable ignored) {
                }
            }
            if (uvc != null) {
                try {
                    uvc.stop();
                } catch (Throwable ignored) {
                }
            }
            if (muxer != null) {
                try {
                    if (audio != null && audio.isFailed()) {
                        muxer.startVideoOnly();
                    }
                    muxer.finish();
                } catch (Throwable ignored) {
                }
            }
            if (hevcOut != null) {
                try {
                    hevcOut.close();
                } catch (Throwable ignored) {
                }
            }
            recording = false;
            releaseWakeLock();
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
            stopSelf();
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("XREAL Recorder")
                .setContentText(audioSilenceDetected
                        ? "USB音声が10秒以上すべてゼロです"
                        : "録画中（UVC HEVC + USB mic）")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Throwable ignored) {
            }
            wakeLock = null;
        }
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        releaseWakeLock();
        Thread t = worker;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        recording = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
