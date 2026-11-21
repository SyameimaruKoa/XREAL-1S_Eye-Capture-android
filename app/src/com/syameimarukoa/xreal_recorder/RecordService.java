package com.syameimarukoa.xreal_recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
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
    public static final int SAVE_MODE_MP4 = 0;       // mode 1: convert while recording
    public static final int SAVE_MODE_RAW = 1;       // mode 2: raw sidecars then convert
    public static final int SAVE_MODE_FMP4 = 2;      // mode 3: fragmented MP4 (extra library)
    public static final int SAVE_MODE_RAW_ONLY = 3;  // mode 4: raw sidecars only, no MP4
    public static final String EXTRA_SAVE_MODE = "save_mode";
    public static final String PREF_SAVE_MODE = "save_mode";

    private static volatile String status = "待機中";
    private static volatile boolean recording;
    private static volatile boolean startPending;
    private static volatile long videoFrames;
    private static volatile long videoBytes;
    private static volatile long firstVideoNs;
    private static volatile long lastVideoNs;

    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private volatile boolean destroyed;
    private static volatile boolean stopRequested;
    private volatile boolean audioSilenceDetected;
    private volatile boolean audioExactZero;
    private static volatile boolean phoneExactZero;
    private static volatile boolean phoneQuiet;
    private static volatile boolean phoneAudioSeen;
    private static final AtomicInteger phonePeak = new AtomicInteger();
    private final AtomicInteger audioPeak = new AtomicInteger();
    private volatile boolean audioSilenceEverDetected;
    private UsbDevice device;
    private Uri outputTree;
    private String outputLabel;
    private boolean diagnostics;
    private int videoRoute;
    private int saveMode = SAVE_MODE_MP4;
    private static volatile long recordingStartedMs;
    private static volatile String thermalSummary = "";
    private static volatile GlassesImuClient telemetryImuClient;
    private static volatile String videoLinkMessage = "";
    private static volatile int videoReconnectCount;
    private volatile VideoSource activeVideoSource;
    private final AtomicReference<VideoWriter> sessionWriter = new AtomicReference<>();
    private final AtomicReference<RecordingMuxer> sessionMuxer = new AtomicReference<>();
    private final AtomicReference<AudioSource> sessionAudio = new AtomicReference<>();
    private final AtomicReference<GlassesImuClient> sessionImu = new AtomicReference<>();
    private volatile boolean sessionActive;
    private volatile boolean reconnectReady;
    private volatile long sessionT0Ns;
    private final AtomicReference<boolean[]> sessionCsdSent = new AtomicReference<>();
    private final AtomicReference<FileOutputStream> sessionHevcOut = new AtomicReference<>();
    private Thread telemetryThread;
    private volatile boolean telemetryRunning;
    private DisplayManager displayManager;
    private RecordingPresentation recordingPresentation;
    private int presentationMode = -1;
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

    static boolean isRecordingOrStarting() {
        return recording || startPending;
    }

    static void setStartPending(boolean pending) {
        startPending = pending;
    }

    static boolean isAudioExactZero() { return phoneExactZero; }
    static boolean isAudioQuiet() { return phoneQuiet; }
    static boolean hasAudioData() { return phoneAudioSeen; }
    static boolean hasVideoData() { return videoFrames > 0; }
    static boolean isStopping() { return recording && stopRequested; }
    static boolean isVideoStalled() {
        return VideoProgressWatchdog.isStalled(recording, stopRequested, videoFrames,
                lastVideoNs, recordingStartedMs, SystemClock.elapsedRealtime(),
                SystemClock.elapsedRealtimeNanos());
    }
    static int getAudioPeak() { return phonePeak.getAndSet(0); }
    static long getElapsedSeconds() {
        return recording ? Math.max(0, (SystemClock.elapsedRealtime() - recordingStartedMs) / 1000) : 0;
    }

    public static String getVideoRateSummary() {
        String link = videoLinkMessage;
        if (link != null && !link.isEmpty()) {
            return link;
        }
        long spanNs = lastVideoNs - firstVideoNs;
        if (videoFrames < 2 || spanNs < 1_000_000_000L) {
            return "受信レートを計測中";
        }
        double seconds = spanNs / 1_000_000_000.0;
        String rate = String.format(Locale.US, "受信平均 %.2f fps / %.2f Mbps",
                (videoFrames - 1) / seconds, videoBytes * 8.0 / seconds / 1_000_000.0);
        if (videoReconnectCount > 0) {
            rate += " / 再接続" + videoReconnectCount + "回";
        }
        return rate;
    }

    /** Measured video bitrate in bits/s, or 0 while still measuring. */
    public static double getVideoBitrateBps() {
        long spanNs = lastVideoNs - firstVideoNs;
        if (videoFrames < 2 || spanNs < 1_000_000_000L) {
            return 0;
        }
        return videoBytes * 8.0 / (spanNs / 1_000_000_000.0);
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
                startPending = false;
                return START_NOT_STICKY;
            }
            device = intent.getParcelableExtra(EXTRA_DEVICE);
            if (device == null) {
                startPending = false;
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
            int mode = intent.getIntExtra(EXTRA_SAVE_MODE, -1);
            if (mode < 0) {
                mode = getSharedPreferences("recording_options", MODE_PRIVATE)
                        .getInt(PREF_SAVE_MODE, SAVE_MODE_FMP4);
            }
            saveMode = (mode == SAVE_MODE_RAW || mode == SAVE_MODE_RAW_ONLY
                    || mode == SAVE_MODE_FMP4) ? mode : SAVE_MODE_MP4;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, buildNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification());
                }
            } catch (Throwable t) {
                startPending = false;
                status = "Foreground Service開始失敗: " + t;
                Log.e(TAG, "startForeground failed", t);
                stopSelf();
                return START_NOT_STICKY;
            }

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XrealRec:record");
            // Held only while recording and released on every service shutdown path.
            wakeLock.acquire();

            videoFrames = 0;
            videoBytes = 0;
            firstVideoNs = 0;
            lastVideoNs = 0;
            stopRequested = false;
            recordingStartedMs = SystemClock.elapsedRealtime();
            recording = true;
            startPending = false;
            audioSilenceDetected = false;
            audioExactZero = false;
            phoneExactZero = false;
            phoneQuiet = false;
            phoneAudioSeen = false;
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
        final boolean rawMode = saveMode == SAVE_MODE_RAW || saveMode == SAVE_MODE_RAW_ONLY;
        final boolean rawOnly = saveMode == SAVE_MODE_RAW_ONLY;
        final boolean fmp4Mode = saveMode == SAVE_MODE_FMP4;
        VideoSource videoSource = null;

        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null) {
                dir = getFilesDir();
            }
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("cannot create output directory");
            }

            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            videoSource = videoRoute == ROUTE_TCP_HEVC ? new TcpSource(this)
                    : new UvcSource(um, device);
            activeVideoSource = videoSource;
            if (videoSource instanceof TcpSource) {
                final String liveToken = java.util.UUID.randomUUID().toString().replace("-", "");
                String liveMode = getSharedPreferences("recording_options", MODE_PRIVATE)
                        .getString("confirmed_live_mode", "1600 1200 60");
                final String restoredMode = liveMode == null ? "1600 1200 60" : liveMode;
                ((TcpSource) videoSource).setReconnectHook(() -> {
                    try {
                        GlassesStatusClient client = new GlassesStatusClient(this);
                        String current = client.readLiveMode(liveToken);
                        if (!restoredMode.equals(current)) {
                            client.setLiveMode(current, restoredMode, liveToken);
                            String verified = client.readLiveMode(liveToken);
                            if (!restoredMode.equals(verified)) {
                                throw new IOException("画質を復元できませんでした: " + verified);
                            }
                        }
                        Log.i(TAG, "live mode restored after reconnect: " + restoredMode);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            videoSource.open();
            final VideoSource finalVideo = videoSource;

            videoSource.start(new UvcSource.Listener() {
                @Override
                public void onCsd(byte[] csd) {
                    boolean[] csdState = sessionCsdSent.get();
                    if (csdState == null || csdState[0]) {
                        return;
                    }
                    csdState[0] = true;
                    FileOutputStream hevc = sessionHevcOut.get();
                    if (rawMode && hevc != null && csd != null) {
                        try {
                            hevc.write(csd);
                            hevc.flush();
                        } catch (Exception e) {
                            Log.w(TAG, "could not write CSD to raw HEVC", e);
                        }
                    }
                    MediaFormat fmt = MediaFormat.createVideoFormat(
                            MediaFormat.MIMETYPE_VIDEO_HEVC,
                            finalVideo.getWidth(), finalVideo.getHeight());
                    fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd));
                    fmt.setInteger(MediaFormat.KEY_FRAME_RATE, finalVideo.getFrameRate());
                    RecordingMuxer currentMuxer = sessionMuxer.get();
                    if (currentMuxer != null) {
                        currentMuxer.addVideoTrack(fmt);
                    }
                }

                @Override
                public void onAccessUnit(byte[] annexB, long timestampNs) {
                    VideoWriter currentWriter = sessionWriter.get();
                    if (currentWriter == null) {
                        return; // session rotation in progress
                    }
                    try {
                        long receivedNs = SystemClock.elapsedRealtimeNanos();
                        long ptsUs = Math.max(0L, (timestampNs - sessionT0Ns) / 1000L);
                        currentWriter.enqueue(annexB, ptsUs, UvcSource.isKeyFrame(annexB),
                                finalVideo.getAccessUnits());
                        if (videoFrames == 0) {
                            firstVideoNs = receivedNs;
                        }
                        lastVideoNs = receivedNs;
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

                @Override
                public void onStreamStalled(String reason) {
                    // Save only the current session. Keep the service and TcpSource
                    // alive so the reconnect can finish locally.
                    sessionActive = false;
                    finishSession();
                    videoLinkMessage = "映像一時停止: 再接続待ち…";
                    status = "USB切断: 録画を保存しました。再接続を待っています";
                    Log.w(TAG, "video stream stalled: " + reason);
                }

                @Override
                public void onStreamResumed() {
                    videoLinkMessage = "";
                    if (finalVideo instanceof TcpSource) {
                        videoReconnectCount = ((TcpSource) finalVideo).getReconnectCount();
                    }
                    reconnectReady = true;
                    status = "映像を再開しました";
                    Log.i(TAG, "video stream resumed");
                }

                @Override
                public void onStreamReset() {
                    Log.i(TAG, "video stream reset after reconnect");
                }
            });

            if (videoRoute != ROUTE_TCP_HEVC) {
                Thread.sleep(2000);
            }

            if (!startSession(finalVideo, rawMode, rawOnly, fmp4Mode, dir)) {
                throw new IllegalStateException("could not start recording session");
            }
            startTelemetry();

            boolean audioFailureHandled = false;
            while (!stopRequested && !finalVideo.isFailed()) {
                if (!sessionActive) {
                    if (reconnectReady) {
                        reconnectReady = false;
                        audioFailureHandled = false;
                        if (!startSession(finalVideo, rawMode, rawOnly, fmp4Mode, dir)) {
                            break;
                        }
                    } else {
                        Thread.sleep(50);
                        continue;
                    }
                }
                AudioSource a = sessionAudio.get();
                if (a != null && a.isFailed()) {
                    RecordingMuxer m = sessionMuxer.get();
                    if (m != null) {
                        m.startVideoOnly();
                    }
                    if (!audioFailureHandled) {
                        audioFailureHandled = true;
                        status = "録画中: 音声停止、映像のみ継続: " + a.getError();
                        NotificationManager nm = (NotificationManager)
                                getSystemService(Context.NOTIFICATION_SERVICE);
                        nm.notify(NOTIFICATION_ID, buildNotification());
                    }
                }
                Thread.sleep(200);
            }

            if (sessionActive) {
                finishSession();
            }
        } catch (Throwable t) {
            Log.e(TAG, "recording failed", t);
            status = "録画エラー: " + t.getMessage();
            finishSession();
        } finally {
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
            if (destroyed) {
                recording = false;
            }
        }
    }

    private void finishSession() {
        VideoWriter w = sessionWriter.getAndSet(null);
        if (w != null) {
            w.finish();
        }
        sessionMuxer.set(null);
        sessionCsdSent.set(null);
        FileOutputStream hevc = sessionHevcOut.getAndSet(null);
        if (hevc != null) {
            try {
                hevc.close();
            } catch (Throwable ignored) {
            }
        }
        AudioSource a = sessionAudio.getAndSet(null);
        if (a != null) {
            a.stop();
        }
        GlassesImuClient imu = sessionImu.getAndSet(null);
        if (imu != null) {
            imu.stop();
        }
        telemetryImuClient = null;
    }

    private boolean startSession(VideoSource videoSource, boolean rawMode,
            boolean rawOnly, boolean fmp4Mode, File dir) throws Exception {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String rawExt = rawOnly ? ".hevc4" : ".hevc";
        String audioExt = rawOnly ? ".aac4" : ".aac";
        File hevcFile = null;
        File wavFile = null;
        File adtsFile = null;
        if (rawMode || diagnostics) {
            hevcFile = new File(dir, "xreal_" + ts + rawExt);
        }
        if (diagnostics) {
            wavFile = new File(dir, "xreal_" + ts + ".wav");
        }
        if (rawMode) {
            adtsFile = new File(dir, "xreal_" + ts + audioExt);
        }
        long captureTimeMs = System.currentTimeMillis();
        RecordingOutput output = null;
        RecordingMuxer muxer = null;
        String mp4Name;
        if (!rawMode) {
            output = RecordingOutput.create(this, outputTree, captureTimeMs);
            mp4Name = output.getFilename();
            muxer = fmp4Mode ? output.openFragmentedMuxer() : output.openMuxer();
        } else {
            mp4Name = hevcFile != null
                    ? hevcFile.getName().replaceFirst("(?i)\\.hevc4?$", ".mp4")
                    : "VID_" + ts + ".mp4";
        }
        FileOutputStream hevcOut = hevcFile != null ? new FileOutputStream(hevcFile) : null;
        GlassesImuClient imu = GlassesImuClient.start(this);
        final String finalMp4Name = mp4Name;
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
                ? "USB Ethernet HEVC passthrough" : "USB UVC HEVC passthrough");
        if (videoRoute == ROUTE_TCP_HEVC) {
            cameraInfo.put("requested_width", JSONObject.NULL);
            cameraInfo.put("requested_height", JSONObject.NULL);
            cameraInfo.put("requested_frames_per_second", JSONObject.NULL);
            cameraInfo.put("requested_video_note", "TCP format is determined by the glasses");
        } else {
            cameraInfo.put("requested_width", videoSource.getWidth());
            cameraInfo.put("requested_height", videoSource.getHeight());
            cameraInfo.put("requested_frames_per_second", videoSource.getFrameRate());
        }
        cameraInfo.put("audio", "USB microphone, mono AAC, 48000 Hz");

        final RecordingMuxer finalMuxer = muxer;
        final FileOutputStream finalHevc = hevcOut;
        final RecordingOutput finalOutput = output;
        final GlassesImuClient finalImu = imu;
        final long t0Ns = SystemClock.elapsedRealtimeNanos();
        sessionT0Ns = t0Ns;
        VideoWriter writer = new VideoWriter(new VideoWriter.Output() {
            @Override
            public void write(byte[] annexB, long ptsUs, boolean keyFrame, int auIndex)
                    throws Exception {
                if (finalHevc != null) {
                    finalHevc.write(annexB);
                }
                if (finalMuxer != null) {
                    finalMuxer.writeVideo(annexB, ptsUs, keyFrame);
                }
            }

            @Override
            public void finish() throws Exception {
                try {
                    if (finalMuxer != null) {
                        finalMuxer.finish();
                    }
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
                            if (videoFrames > 0) {
                                cameraInfo.put("received_video_width", videoSource.getWidth());
                                cameraInfo.put("received_video_height", videoSource.getHeight());
                            }
                            cameraInfo.put("video_rate_basis", "host receive timestamps");
                            if (videoSource instanceof TcpSource) {
                                ((TcpSource) videoSource).appendTimingMetadata(cameraInfo);
                            }
                            if (videoFrames > 1 && spanNs > 0) {
                                double seconds = spanNs / 1_000_000_000.0;
                                cameraInfo.put("received_average_fps", (videoFrames - 1) / seconds);
                                cameraInfo.put("received_average_bitrate_bps",
                                        videoBytes * 8.0 / seconds);
                            }
                            AudioSource capturedAudio = sessionAudio.get();
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
                            }
                            if (finalOutput != null) {
                                if (finalImu != null) {
                                    cameraInfo.put("imu", new JSONObject(finalImu.getSummary()));
                                }
                                finalOutput.writeMetadata(cameraInfo.toString(2));
                                if (finalImu != null && finalImu.getSampleCount() > 0) {
                                    boolean embedded = false;
                                    if (!rawMode) {
                                        try {
                                            List<GyroflowProto.ImuSample> imuSamples =
                                                    finalImu.getSamples();
                                            double imuSpanUs = imuSamples.size() > 1
                                                    ? imuSamples.get(imuSamples.size() - 1).timeUs
                                                    - imuSamples.get(0).timeUs : 0.0;
                                            long durationUs = imuSpanUs > 0
                                                    ? (long) imuSpanUs
                                                    : (spanNs > 0 ? spanNs / 1000L : 0L);
                                            float fps = 0f;
                                            if (durationUs > 0 && videoFrames > 1) {
                                                fps = (float) ((videoFrames - 1)
                                                        * 1_000_000.0 / durationUs);
                                            }
                                            if (fps <= 0) fps = videoSource.getFrameRate();
                                            if (fps <= 0) fps = 30f;
                                            int rate = 1000;
                                            if (imuSamples.size() > 1) {
                                                double spanUs = imuSamples.get(
                                                        imuSamples.size() - 1).timeUs
                                                        - imuSamples.get(0).timeUs;
                                                if (spanUs > 0) {
                                                    rate = (int) (imuSamples.size()
                                                            * 1_000_000.0 / spanUs);
                                                }
                                            }
                                            embedded = finalOutput.injectTelemetry(
                                                    videoSource.getWidth(),
                                                    videoSource.getHeight(), fps,
                                                    durationUs, rate, "xYz", imuSamples,
                                                    finalImu.getFirstTimestampNs() / 1000L);
                                        } catch (Throwable t) {
                                            Log.e(TAG, "telemetry injection failed", t);
                                        }
                                    }
                                    if (!embedded) {
                                        try {
                                            finalOutput.writeGyroCsv(finalImu.formatCsv(
                                                    finalMp4Name, captureTimeMs));
                                        } catch (Throwable t) {
                                            Log.e(TAG, "gcsv write failed", t);
                                        }
                                    }
                                }
                            }
                        } finally {
                            if (finalOutput != null) {
                                finalOutput.complete();
                            }
                        }
                    }
                }
            }
        });

        AudioSource audio = new AudioSource(this, t0Ns, muxer, wavFile, adtsFile,
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
                        phoneAudioSeen = true;
                    }

                    @Override
                    public void onSilenceChanged(boolean silent) {
                        audioSilenceDetected = silent;
                        phoneQuiet = silent;
                        if (silent) {
                            audioSilenceEverDetected = true;
                        }
                        status = silent ? "録画中: USB音声の入力を検出"
                                : "録画中: USB音声の入力を検出";
                        Log.i(TAG, "USB audio low-level state=" + silent);
                    }
                });

        sessionWriter.set(writer);
        sessionMuxer.set(muxer);
        sessionAudio.set(audio);
        sessionImu.set(imu);
        sessionHevcOut.set(hevcOut);
        sessionCsdSent.set(new boolean[1]);
        telemetryImuClient = imu;
        videoFrames = 0;
        videoBytes = 0;
        firstVideoNs = 0;
        lastVideoNs = 0;
        sessionActive = true;
        audio.start();
        status = "録画中: " + mp4Name;
        videoReconnectCount = 0;
        Log.i(TAG, "session started: " + mp4Name);
        return true;
    }

    private void finishWriter(VideoWriter writer) {
        writer.finish(new Runnable() {
            @Override
            public void run() {
                status = "保存処理に時間がかかっています。完了までお待ちください";
                Log.w(TAG, "Video output is still finalizing");
                try {
                    NotificationManager manager = (NotificationManager)
                            getSystemService(Context.NOTIFICATION_SERVICE);
                    manager.notify(NOTIFICATION_ID, buildNotification());
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not update saving notification", e);
                }
            }
        });
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
                        ? "音声停止・映像のみ録画中"
                        : videoRoute == ROUTE_TCP_HEVC ? "録画中（TCP HEVC + USBマイク）"
                        : "録画中（UVC HEVC + USBマイク）")
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
        int displayMode = RecordingPresentation.getDisplayMode(
                getSharedPreferences("recording_options", MODE_PRIVATE));
        if (displayMode == RecordingPresentation.MODE_OFF) {
            dismissRecordingDisplay();
            return;
        }
        if (recordingPresentation != null
                && (recordingPresentation.getDisplay().getDisplayId() != target.getDisplayId()
                || presentationMode != displayMode)) {
            dismissRecordingDisplay();
        }
        if (recordingPresentation == null) {
            try {
                recordingPresentation = new RecordingPresentation(this, target, displayMode);
                recordingPresentation.show();
                presentationMode = displayMode;
            } catch (RuntimeException e) {
                Log.w(TAG, "external recording display unavailable", e);
                dismissRecordingDisplay();
                return;
            }
        }
        long elapsed = Math.max(0, (SystemClock.elapsedRealtime() - recordingStartedMs) / 1000);
        recordingPresentation.updateStatus(status, stopRequested, hasVideoData(), isVideoStalled(),
                audioSilenceDetected, phoneAudioSeen,
                audioExactZero, audioPeak.getAndSet(0), elapsed,
                getVideoRateSummary(), thermalSummary);
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
        presentationMode = -1;
    }

    private void startTelemetry() {
        if (telemetryThread != null && telemetryThread.isAlive()) {
            return;
        }
        telemetryRunning = true;
        telemetryThread = new Thread(() -> {
            while (telemetryRunning) {
                StringBuilder sb = new StringBuilder();
                try {
                    GlassesTelemetryClient.Snapshot snap = new GlassesTelemetryClient(this).read();
                    if (snap.temperatureC != null) {
                        sb.append(String.format(Locale.US, "グラス %.1f℃", snap.temperatureC));
                    }
                } catch (Exception ignored) {
                }
                GlassesImuClient imu = telemetryImuClient;
                if (imu != null) {
                    Float imuTemp = imu.getTemperatureC();
                    if (imuTemp != null) {
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append(String.format(Locale.US, "IMU %.1f℃", imuTemp));
                    }
                }
                try {
                    Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (battery != null) {
                        int tenths = battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                        if (tenths != Integer.MIN_VALUE) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(String.format(Locale.US, "スマホ %.1f℃", tenths / 10.0f));
                        }
                    }
                } catch (Exception ignored) {
                }
                thermalSummary = sb.toString();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "xreal-telemetry");
        telemetryThread.setDaemon(true);
        telemetryThread.start();
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
        destroyed = true;
        displayHandler.removeCallbacksAndMessages(null);
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        dismissRecordingDisplay();
        stopRequested = true;
        VideoSource vs = activeVideoSource;
        if (vs != null) {
            vs.stop();
        }
        releaseWakeLock();
        Thread t = worker;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (t == null || !t.isAlive()) {
            recording = false;
        }
        startPending = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}