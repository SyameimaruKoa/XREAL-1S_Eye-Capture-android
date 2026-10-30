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
import android.hardware.display.DisplayManager;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.net.Uri;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class RecordService extends Service {
    private static final String TAG = "XrealRec";
    private static final String CHANNEL_ID = "xreal_recording";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START = "jp.xreal.recorder.START";
    public static final String ACTION_STOP = "jp.xreal.recorder.STOP";
    public static final String EXTRA_DEVICE = "device";
    public static final String EXTRA_OUTPUT_TREE = "output_tree";
    public static final String EXTRA_OUTPUT_LABEL = "output_label";
    public static final String EXTRA_DIAGNOSTICS = "diagnostics";
    public static final String EXTRA_VIDEO_ROUTE = "video_route";
    public static final int ROUTE_TCP_HEVC = 0;
    public static final int ROUTE_UVC_HEVC = 1;
    public static final int ROUTE_UVC_MJPEG_30 = 2;
    public static final int ROUTE_UVC_MJPEG_60 = 3;

    private static volatile String status = "待機中";
    private static volatile boolean recording;
    private static volatile long videoFrames;
    private static volatile long videoBytes;
    private static volatile long firstVideoNs;
    private static volatile long lastVideoNs;

    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean audioSilenceDetected;
    private volatile boolean audioExactZero;
    private static volatile boolean phoneExactZero;
    private static volatile boolean phoneQuiet;
    private static final AtomicInteger phonePeak = new AtomicInteger();
    private final AtomicInteger audioPeak = new AtomicInteger();
    private volatile boolean audioSilenceEverDetected;
    private UsbDevice device;
    private Uri outputTree;
    private String outputLabel;
    private boolean diagnostics;
    private int videoRoute;
    private static volatile long recordingStartedMs;
    private DisplayManager displayManager;
    private RecordingPresentation recordingPresentation;
    private final Handler displayHandler = new Handler(Looper.getMainLooper());
    private final Runnable displayUpdate = new Runnable() {
        @Override
        public void run() {
            updateRecordingDisplay();
            if (recording) {
                displayHandler.postDelayed(this, 1000);
            }
        }
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            updateRecordingDisplay();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            updateRecordingDisplay();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            updateRecordingDisplay();
        }
    };

    public static String getStatus() {
        return status;
    }

    public static boolean isRecording() {
        return recording;
    }

    static boolean isAudioExactZero() { return phoneExactZero; }
    static boolean isAudioQuiet() { return phoneQuiet; }
    static int getAudioPeak() { return phonePeak.getAndSet(0); }
    static long getElapsedSeconds() {
        return recording ? Math.max(0, (SystemClock.elapsedRealtime() - recordingStartedMs) / 1000) : 0;
    }

    public static String getVideoRateSummary() {
        long spanNs = lastVideoNs - firstVideoNs;
        if (videoFrames < 2 || spanNs < 1_000_000_000L) {
            return "受信レートを計測中";
        }
        double seconds = spanNs / 1_000_000_000.0;
        return String.format(Locale.US, "受信平均 %.2f fps / %.2f Mbps",
                (videoFrames - 1) / seconds, videoBytes * 8.0 / seconds / 1_000_000.0);
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
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, displayHandler);
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
            String selectedTree = intent.getStringExtra(EXTRA_OUTPUT_TREE);
            outputTree = selectedTree == null ? null : Uri.parse(selectedTree);
            outputLabel = intent.getStringExtra(EXTRA_OUTPUT_LABEL);
            if (outputLabel == null || outputLabel.isEmpty()) {
                outputLabel = "DCIM / 100XREAL";
            }
            diagnostics = intent.getBooleanExtra(EXTRA_DIAGNOSTICS, false);
            videoRoute = intent.getIntExtra(EXTRA_VIDEO_ROUTE, ROUTE_TCP_HEVC);
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
            // Held only while recording and released on every service shutdown path.
            wakeLock.acquire();

            recording = true;
            videoFrames = 0;
            videoBytes = 0;
            firstVideoNs = 0;
            lastVideoNs = 0;
            recordingStartedMs = SystemClock.elapsedRealtime();
            stopRequested = false;
            audioSilenceDetected = false;
            audioExactZero = false;
            phoneExactZero = false;
            phoneQuiet = false;
            phonePeak.set(0);
            audioPeak.set(0);
            audioSilenceEverDetected = false;
            status = "開始準備中...";
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runRecording();
                }
            }, "xreal-record");
            worker.start();
            displayHandler.removeCallbacks(displayUpdate);
            displayHandler.post(displayUpdate);
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
        VideoSource videoSource = null;
        AudioSource audio = null;
        RecordingMuxer muxer = null;
        FileOutputStream hevcOut = null;
        VideoWriter writer = null;
        RecordingOutput output = null;
        String mp4Name = null;
        File hevcFile = null;
        File wavFile = null;

        try {
            final boolean mjpeg = videoRoute == ROUTE_UVC_MJPEG_30
                    || videoRoute == ROUTE_UVC_MJPEG_60;
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null) {
                dir = getFilesDir();
            }
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("cannot create output directory");
            }
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            if (diagnostics) {
                hevcFile = new File(dir, "xreal_" + ts + (mjpeg ? ".mjpeg" : ".hevc"));
                wavFile = new File(dir, "xreal_" + ts + ".wav");
            }

            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            videoSource = videoRoute == ROUTE_TCP_HEVC ? new TcpSource(this)
                    : mjpeg ? new MjpegSource(um, device, videoRoute == ROUTE_UVC_MJPEG_60 ? 60 : 30)
                    : new UvcSource(um, device);
            videoSource.open();

            long captureTimeMs = System.currentTimeMillis();
            output = RecordingOutput.create(this, outputTree, captureTimeMs, mjpeg);
            mp4Name = output.getFilename();
            muxer = mjpeg ? output.openMatroskaMuxer() : output.openMuxer();
            final JSONObject cameraInfo = new JSONObject();
            cameraInfo.put("schema_version", 1);
            cameraInfo.put("file", mp4Name);
            cameraInfo.put("recording_start_time", new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(captureTimeMs)));
            cameraInfo.put("usb_product", device.getProductName());
            cameraInfo.put("usb_manufacturer", device.getManufacturerName());
            cameraInfo.put("usb_vendor_id", device.getVendorId());
            cameraInfo.put("usb_product_id", device.getProductId());
            cameraInfo.put("video_source", videoRoute == ROUTE_TCP_HEVC
                    ? "USB Ethernet HEVC passthrough" : mjpeg
                    ? "USB UVC MJPEG passthrough" : "USB UVC HEVC passthrough");
            cameraInfo.put("requested_width", videoSource.getWidth());
            cameraInfo.put("requested_height", videoSource.getHeight());
            cameraInfo.put("requested_frames_per_second", videoSource.getFrameRate());
            cameraInfo.put("audio", "USB microphone, mono AAC, 48000 Hz");
            if (hevcFile != null) {
                hevcOut = new FileOutputStream(hevcFile);
            }

            final RecordingMuxer finalMuxer = muxer;
            final FileOutputStream finalHevc = hevcOut;
            final RecordingOutput finalOutput = output;
            final VideoSource finalVideo = videoSource;
            final AtomicReference<AudioSource> finalAudioRef = new AtomicReference<>();
            final long t0Ns = SystemClock.elapsedRealtimeNanos();
            final VideoWriter finalWriter = new VideoWriter(new VideoWriter.Output() {
                @Override
                public void write(byte[] annexB, long ptsUs, boolean keyFrame, int auIndex)
                        throws Exception {
                    long writeStartNs = SystemClock.elapsedRealtimeNanos();
                    if (finalHevc != null) {
                        finalHevc.write(annexB);
                    }
                    long rawEndNs = SystemClock.elapsedRealtimeNanos();
                    finalMuxer.writeVideo(annexB, ptsUs, keyFrame);
                    long muxEndNs = SystemClock.elapsedRealtimeNanos();
                    long rawMs = (rawEndNs - writeStartNs) / 1_000_000L;
                    long muxMs = (muxEndNs - rawEndNs) / 1_000_000L;
                    if (rawMs >= 50 || muxMs >= 50) {
                        Log.w(TAG, "video sink timing auIndex=" + auIndex
                                + " rawMs=" + rawMs + " muxMs=" + muxMs
                                + " bytes=" + annexB.length);
                    }
                }

                @Override
                public void finish() throws Exception {
                    try {
                        finalMuxer.finish();
                    } finally {
                        try {
                            if (finalHevc != null) {
                                finalHevc.close();
                            }
                        } finally {
                            try {
                                long spanNs = lastVideoNs - firstVideoNs;
                                cameraInfo.put("received_video_frames", videoFrames);
                                cameraInfo.put("received_video_bytes", videoBytes);
                                cameraInfo.put("video_rate_basis", "host receive timestamps");
                                if (videoFrames > 1 && spanNs > 0) {
                                    double seconds = spanNs / 1_000_000_000.0;
                                    cameraInfo.put("received_average_fps", (videoFrames - 1) / seconds);
                                    cameraInfo.put("received_average_bitrate_bps", videoBytes * 8.0 / seconds);
                                }
                                AudioSource capturedAudio = finalAudioRef.get();
                                if (capturedAudio != null) {
                                    cameraInfo.put("audio_capture_status",
                                            capturedAudio.isFailed() ? "failed" : "completed");
                                    if (capturedAudio.isFailed()) {
                                        cameraInfo.put("audio_error", capturedAudio.getError());
                                    }
                                    cameraInfo.put("audio_input_sample_rate_hz", 48000);
                                    cameraInfo.put("audio_output_channels", 1);
                                    cameraInfo.put("aac_encoded_bytes", capturedAudio.getEncodedBytes());
                                    cameraInfo.put("aac_encoded_samples", capturedAudio.getEncodedSamples());
                                    long audioSpanUs = capturedAudio.getLastEncodedPtsUs()
                                            - capturedAudio.getFirstEncodedPtsUs();
                                    if (capturedAudio.getEncodedSamples() > 1 && audioSpanUs > 0) {
                                        cameraInfo.put("aac_average_bitrate_bps",
                                                capturedAudio.getEncodedBytes() * 8_000_000.0 / audioSpanUs);
                                    }
                                }
                                finalOutput.writeMetadata(cameraInfo.toString(2));
                            } finally {
                                finalOutput.complete();
                            }
                        }
                    }
                }
            });
            writer = finalWriter;

            final boolean[] csdSent = {false};
            videoSource.start(new UvcSource.Listener() {
                @Override
                public void onCsd(byte[] csd) {
                    if (csdSent[0]) {
                        return;
                    }
                    csdSent[0] = true;
                    MediaFormat fmt = MediaFormat.createVideoFormat(
                            mjpeg ? "video/mjpeg" : MediaFormat.MIMETYPE_VIDEO_HEVC,
                            finalVideo.getWidth(), finalVideo.getHeight());
                    if (!mjpeg) {
                        fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd));
                    }
                    fmt.setInteger(MediaFormat.KEY_FRAME_RATE, finalVideo.getFrameRate());
                    finalMuxer.addVideoTrack(fmt);
                }

                @Override
                public void onAccessUnit(byte[] annexB, long timestampNs) {
                    try {
                        long ptsUs = Math.max(0L, (timestampNs - t0Ns) / 1000L);
                        finalWriter.enqueue(annexB, ptsUs, mjpeg || UvcSource.isKeyFrame(annexB),
                                finalVideo.getAccessUnits());
                        if (videoFrames == 0) {
                            firstVideoNs = timestampNs;
                        }
                        lastVideoNs = timestampNs;
                        videoFrames++;
                        videoBytes += annexB.length;
                    } catch (Exception e) {
                        throw new UvcSource.RawWriteException(e);
                    }
                }

                @Override
                public void onError(String message) {
                    status = "映像エラー: " + message;
                    Log.e(TAG, "video error: " + message);
                }
            });

            // Preserve the previously validated UVC start order. TCP does not
            // need the UVC negotiation delay before starting the microphone.
            if (videoRoute != ROUTE_TCP_HEVC) {
                Thread.sleep(2000);
            }

            final AudioSource finalAudio = new AudioSource(this, t0Ns, muxer, wavFile,
                    new AudioSource.Listener() {
                        @Override
                        public void onError(String message) {
                            status = "音声エラー: " + message;
                            Log.e(TAG, "audio error: " + message);
                        }

                        @Override
                        public void onAudioLevel(int peak, boolean exactZero) {
                            audioPeak.accumulateAndGet(peak, Math::max);
                            audioExactZero = exactZero;
                            phonePeak.accumulateAndGet(peak, Math::max);
                            phoneExactZero = exactZero;
                        }

                        @Override
                        public void onSilenceChanged(boolean silent) {
                            audioSilenceDetected = silent;
                            phoneQuiet = silent;
                            if (silent) {
                                audioSilenceEverDetected = true;
                            }
                            status = silent ? "録画中: 音声が3秒以上非常に小さいか無音です"
                                    : "録画中: USB音声の入力を検出";
                            Log.w(TAG, "USB audio low-level warning=" + silent);
                            NotificationManager nm = (NotificationManager)
                                    getSystemService(Context.NOTIFICATION_SERVICE);
                            nm.notify(NOTIFICATION_ID, buildNotification());
                        }
                    });
            audio = finalAudio;
            finalAudioRef.set(finalAudio);
            audio.start();
            status = "録画中: " + mp4Name;

            boolean audioFailureHandled = false;
            while (!stopRequested && !videoSource.isFailed()
                    && !muxer.isFailed() && !finalWriter.isFailed()) {
                if (audio.isFailed()) {
                    muxer.startVideoOnly();
                    if (!audioFailureHandled) {
                        audioFailureHandled = true;
                        status = "録画中: 音声停止、映像のみ継続: " + audio.getError();
                        NotificationManager nm = (NotificationManager)
                                getSystemService(Context.NOTIFICATION_SERVICE);
                        nm.notify(NOTIFICATION_ID, buildNotification());
                    }
                }
                Thread.sleep(200);
            }

            audio.stop();
            videoSource.stop();
            if (audio.isFailed()) {
                muxer.startVideoOnly();
            }
            finalWriter.finish();

            String result = String.format(Locale.US,
                    "%s / %s\nAU=%d audioFrames=%d%s",
                    outputLabel, mp4Name, videoSource.getAccessUnits(), audio.getFramesWritten(),
                    "\n" + getVideoRateSummary()
                            + (diagnostics ? "\n診断ファイル: " + dir.getAbsolutePath() : ""));
            if (videoSource.isFailed()) {
                status = "映像停止: " + videoSource.getError() + "\n" + result;
            } else if (audio.isFailed()) {
                status = "音声停止: " + audio.getError() + "\n" + result;
            } else if (finalWriter.isFailed()) {
                status = "映像出力エラー: " + finalWriter.getError() + "\n" + result;
            } else if (muxer.isFailed()) {
                status = "Muxerエラー: " + muxer.getError() + "\n" + result;
            } else {
                status = "保存完了: " + result;
            }
            if (muxer.isFailed() && (videoSource.isFailed() || audio.isFailed())) {
                status += "\nMuxerエラー: " + muxer.getError();
            }
            if (audioSilenceEverDetected) {
                status += "\n注意: 音声が3秒以上非常に小さいか無音でした";
            }
        } catch (Throwable t) {
            status = "録画エラー: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "recording failed", t);
            if (mp4Name != null) {
                status += "\n" + outputLabel + " / " + mp4Name;
            }
        } finally {
            if (audio != null) {
                try {
                    audio.stop();
                } catch (Throwable ignored) {
                }
            }
            if (videoSource != null) {
                try {
                    videoSource.stop();
                } catch (Throwable ignored) {
                }
            }
            if (writer != null) {
                try {
                    if (muxer != null && audio != null && audio.isFailed()) {
                        muxer.startVideoOnly();
                    }
                    writer.finish();
                } catch (Throwable ignored) {
                }
            } else if (muxer != null) {
                try {
                    if (audio != null && audio.isFailed()) {
                        muxer.startVideoOnly();
                    }
                    muxer.finish();
                } catch (Throwable ignored) {
                }
            }
            if (hevcOut != null && writer == null) {
                try {
                    hevcOut.close();
                } catch (Throwable ignored) {
                }
            }
            if (output != null && writer == null) {
                try {
                    output.complete();
                } catch (Throwable t) {
                    status += "\n保存先への公開失敗: " + t.getMessage();
                }
            }
            recording = false;
            displayHandler.post(new Runnable() {
                @Override
                public void run() {
                    dismissRecordingDisplay();
                }
            });
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
                .setContentText(status.startsWith("録画中: 音声停止")
                        ? "音声停止・映像のみ録画中" : audioSilenceDetected
                        ? "音声が3秒以上非常に小さいか無音です"
                        : "録画中（UVC HEVC + USB mic）")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateRecordingDisplay() {
        if (!recording || displayManager == null) {
            dismissRecordingDisplay();
            return;
        }
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display target = null;
        for (Display display : displays) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY && display.isValid()) {
                target = display;
                break;
            }
        }
        if (target == null) {
            dismissRecordingDisplay();
            return;
        }
        if (recordingPresentation != null
                && recordingPresentation.getDisplay().getDisplayId() != target.getDisplayId()) {
            dismissRecordingDisplay();
        }
        if (recordingPresentation == null) {
            try {
                boolean sideMode = getSharedPreferences("recording_options", MODE_PRIVATE)
                        .getBoolean("display_side", false);
                recordingPresentation = new RecordingPresentation(this, target, sideMode);
                recordingPresentation.show();
            } catch (RuntimeException e) {
                Log.w(TAG, "external recording display unavailable", e);
                dismissRecordingDisplay();
                return;
            }
        }
        long elapsed = Math.max(0, (SystemClock.elapsedRealtime() - recordingStartedMs) / 1000);
        recordingPresentation.updateStatus(status, stopRequested, audioSilenceDetected,
                audioExactZero, audioPeak.getAndSet(0), elapsed,
                getVideoRateSummary());
    }

    private void dismissRecordingDisplay() {
        if (recordingPresentation != null) {
            try {
                recordingPresentation.dismiss();
            } catch (RuntimeException e) {
                Log.w(TAG, "could not dismiss recording display", e);
            }
            recordingPresentation = null;
        }
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
        displayHandler.removeCallbacksAndMessages(null);
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        dismissRecordingDisplay();
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
