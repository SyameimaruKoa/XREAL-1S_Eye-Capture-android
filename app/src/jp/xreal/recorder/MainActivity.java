package jp.xreal.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.TextureView;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "jp.xreal.recorder.USB_PERMISSION";
    /** AI/development command action (adb shell am broadcast). */
    public static final String ACTION_DEV = "jp.xreal.recorder.DEV_CMD";
    private static final int REQ_PERMISSIONS = 1001;
    private static final int REQ_OUTPUT_FOLDER = 1002;
    private static final String PREFS = "recording_options";
    private static final String DEFAULT_OUTPUT_LABEL = "DCIM / 100XREAL";

    private UsbManager usbManager;
    private UsbDevice device;
    private TextView statusView;
    private TextView recordingIndicator;
    private TextView audioIndicator;
    private RecordingPresentation.WaveformView phoneWaveform;
    private TextureView previewView;
    private HevcPreview hevcPreview;
    private boolean photoMode;
    private MaterialButton modeButton;
    private LinearLayout photoCard;
    private LinearLayout videoCard;
    private MaterialButton displayModeButton;
    private MaterialCheckBox keepScreenCheck;
    private FrameLayout screenSaver;
    private TextView screenSaverStatus;
    private TextView screenSaverRate;
    private TextView screenSaverAudio;
    private LinearLayout screenSaverContent;
    private RecordingPresentation.WaveformView screenSaverWaveform;
    private long lastTouchMs = SystemClock.elapsedRealtime();
    private MaterialButton startButton;
    private MaterialButton stopButton;
    private MaterialButton enableButton;
    private MaterialButton glassesStatusButton;
    private MaterialButton glasses60Button;
    private MaterialButton glassesLiveModeButton;
    private static final String DEFAULT_LIVE_MODE = "1600 1200 60";
    private String confirmedLiveMode = DEFAULT_LIVE_MODE;
    private MaterialButton glassesNormalButton;
    private MaterialButton glassesRecoverButton;
    private MaterialButton deviceSettingsToggle;
    private LinearLayout deviceSettings;
    private MaterialButton folderButton;
    private MaterialButton resetFolderButton;
    private MaterialCheckBox diagnosticsCheck;
    private MaterialButton saveModeButton;
    private int selectedSaveMode;
    private static final String[] SAVE_MODES = {
            "MP4 同時変換（標準）",
            "生保存→停止時にMP4変換（保全）",
            "fMP4（断片化MP4・保全）",
            "生データのみ（無変換）"
    };
    private TextView destinationView;
    private TextView telemetryView;
    private volatile String telemetryText = "グラス情報: 取得待ち";
    private Thread telemetryThread;
    private volatile boolean telemetryRunning;
    private int selectedVideoRoute;
    private static final String[] VIDEO_ROUTES = {
            "720p/30（広い画角）",
            "1600p/30（高画質）",
            "1600p/60（高レート）",
            "UVC HEVC"
    };
    private static final String[] DISPLAY_MODES = {
            "固定表示（右上）",
            "サイド表示（中央・拡大）",
            "オフ（通常の複製）"
    };
    private SharedPreferences preferences;
    private String outputTree;
    private String outputLabel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastStatus = "";
    private boolean receiverRegistered = false;
    private static volatile boolean glassesStatusPending;
    private boolean recoveryShown;
    /** Continue UVC enablement right after the USB permission dialog is accepted. */
    private boolean pendingUvcEnable;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && d != null) {
                device = d;
                setStatus("USB権限を許可しました: " + d.getProductName());
                if (pendingUvcEnable) {
                    pendingUvcEnable = false;
                    enableUvc();
                }
            } else {
                pendingUvcEnable = false;
                setStatus("USB権限が拒否されました");
            }
        }
    };

    /** Development commands: adb shell am broadcast -a jp.xreal.recorder.DEV_CMD --es cmd <cmd> */
    private final BroadcastReceiver devReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_DEV.equals(intent.getAction())) {
                return;
            }
            String cmd = intent.getStringExtra("cmd");
            if (cmd == null) {
                setStatus("DEV_CMD: cmd extra required");
                return;
            }
            switch (cmd) {
                case "start":
                    startRecording();
                    break;
                case "stop":
                    stopRecording();
                    break;
                case "permission":
                    findDevice();
                    break;
                case "status":
                    setStatus("DEV " + (RecordService.isRecording() ? "REC" : "IDLE")
                            + " / " + (device == null ? "no device" : device.getDeviceName()));
                    break;
                default:
                    setStatus("DEV_CMD unknown: " + cmd);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        outputTree = preferences.getString("output_tree", null);
        outputLabel = preferences.getString("output_label", DEFAULT_OUTPUT_LABEL);
        setContentView(buildUi());
        recoverPendingRawRecordings();
        requestRuntimePermissions();
        findDevice();
        applyMode();
    }

    /** Convert raw sidecars left by an interrupted mode-2 recording on next launch. */
    private void recoverPendingRawRecordings() {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null || !dir.exists()) {
                return;
            }
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".hevc")
                    && name.startsWith("xreal_"));
            if (files == null) {
                return;
            }
            int recovered = 0;
            int failed = 0;
            for (File hevc : files) {
                File mp4 = new File(dir, hevc.getName().replaceFirst("(?i)\\.hevc$", ".mp4"));
                if (mp4.exists()) {
                    continue; // already converted and published
                }
                File aac = new File(dir, hevc.getName().replaceFirst("(?i)\\.hevc$", ".aac"));
                try {
                    RawToMp4Converter.Result r = RawToMp4Converter.convert(
                            hevc, aac.exists() ? aac : null, mp4, 1280, 720, 30);
                    if (hevc.delete()) {
                        recovered++;
                    }
                    if (aac.exists()) {
                        aac.delete();
                    }
                    Log.i("XrealRecover", "recovered " + mp4.getName() + " au=" + r.auCount);
                } catch (Exception e) {
                    failed++;
                    Log.w("XrealRecover", "failed " + hevc.getName() + ": " + e.getMessage());
                }
            }
            final int fr = recovered;
            final int ff = failed;
            handler.post(() -> {
                if (fr > 0 || ff > 0) {
                    setStatus("未変換の録画を復旧: 成功" + fr + " / 失敗" + ff);
                }
            });
        });
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private int color(int attr, int fallback) {
        return com.google.android.material.color.MaterialColors.getColor(this, attr, fallback);
    }

    private int surfaceColor() { return color(com.google.android.material.R.attr.colorSurface, Color.BLACK); }
    private int surfaceContainerColor() { return color(com.google.android.material.R.attr.colorSurfaceContainer, Color.DKGRAY); }
    private int onSurfaceColor() { return color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE); }
    private int onSurfaceVariantColor() { return color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY); }
    private int primaryColor() { return color(com.google.android.material.R.attr.colorPrimary, Color.rgb(180, 200, 255)); }

    private void applyTextAppearance(TextView view, int attr) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attr, value, true)) {
            view.setTextAppearance(value.resourceId);
        }
    }

    private MaterialButton button(String text, boolean filled, View.OnClickListener listener) {
        MaterialButton b = new MaterialButton(this, null,
                filled ? com.google.android.material.R.attr.materialButtonStyle : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private MaterialCheckBox checkBox(String text, boolean checked,
                                      CompoundButton.OnCheckedChangeListener listener) {
        MaterialCheckBox c = new MaterialCheckBox(this);
        c.setText(text);
        c.setChecked(checked);
        c.setOnCheckedChangeListener(listener);
        return c;
    }

    /** Add a Material 3 card to the root and return its vertical content box. */
    private LinearLayout addCard(LinearLayout root, int topMargin) {
        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(this);
        card.setCardBackgroundColor(surfaceContainerColor());
        card.setStrokeWidth(0);
        card.setRadius(dp(16));
        card.setCardElevation(0);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.addView(box);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        root.addView(card, lp);
        return box;
    }

    private void addRow(LinearLayout box, View view, int topMargin) {
        box.addView(view, topMargin(topMargin));
    }

    private LinearLayout.LayoutParams topMargin(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(surfaceColor());

        TextView title = new TextView(this);
        title.setText("XREAL 1S + Eye Recorder");
        applyTextAppearance(title, com.google.android.material.R.attr.textAppearanceHeadlineSmall);
        title.setTextColor(onSurfaceColor());
        root.addView(title);

        telemetryView = new TextView(this);
        applyTextAppearance(telemetryView, com.google.android.material.R.attr.textAppearanceBodyMedium);
        telemetryView.setTextColor(primaryColor());
        telemetryView.setPadding(0, dp(8), 0, dp(8));
        telemetryView.setText(telemetryText);
        root.addView(telemetryView);

        // Mode switch: photo (live viewfinder) vs video (recording).
        photoMode = preferences.getBoolean("photo_mode", false);
        modeButton = button(photoMode ? "モード: 写真" : "モード: 動画", false, v -> {
            photoMode = !photoMode;
            preferences.edit().putBoolean("photo_mode", photoMode).apply();
            applyMode();
            modeButton.setText(photoMode ? "モード: 写真" : "モード: 動画");
        });
        addCard(root, dp(8)).addView(modeButton,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        // Photo mode: live viewfinder (angle check) + shutter. No recording.
        photoCard = addCard(root, dp(8));
        TextView photoLabel = new TextView(this);
        photoLabel.setText("画角プレビュー");
        applyTextAppearance(photoLabel, com.google.android.material.R.attr.textAppearanceTitleSmall);
        photoLabel.setTextColor(onSurfaceColor());
        addRow(photoCard, photoLabel, 0);
        previewView = new TextureView(this);
        addRow(photoCard, previewView, dp(8));
        LinearLayout.LayoutParams previewLp = (LinearLayout.LayoutParams) previewView.getLayoutParams();
        previewLp.height = dp(220);
        hevcPreview = new HevcPreview(previewView, message -> setStatus(message));
        MaterialButton captureButton = button("シャッター", true, v -> captureStill());
        addRow(photoCard, captureButton, dp(8));

        recordingIndicator = new TextView(this);
        applyTextAppearance(recordingIndicator, com.google.android.material.R.attr.textAppearanceHeadlineLarge);
        recordingIndicator.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        recordingIndicator.setTextColor(primaryColor());
        root.addView(recordingIndicator);
        phoneWaveform = new RecordingPresentation.WaveformView(this);
        root.addView(phoneWaveform, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));
        audioIndicator = new TextView(this);
        applyTextAppearance(audioIndicator, com.google.android.material.R.attr.textAppearanceBodyMedium);
        audioIndicator.setTextColor(onSurfaceVariantColor());
        root.addView(audioIndicator);

        statusView = new TextView(this);
        applyTextAppearance(statusView, com.google.android.material.R.attr.textAppearanceBodyLarge);
        statusView.setTextColor(onSurfaceVariantColor());
        statusView.setText("待機中");
        statusView.setPadding(0, 0, 0, dp(8));

        videoCard = addCard(root, dp(8));
        startButton = button("録画開始", true, v -> startRecording());
        addRow(videoCard, startButton, 0);
        stopButton = button("録画停止", false, v -> stopRecording());
        stopButton.setEnabled(false);
        addRow(videoCard, stopButton, dp(8));
        addRow(videoCard, statusView, dp(8));

        boolean showRecovery = preferences.getBoolean("glasses_recovery_required", false);
        LinearLayout utilsCard = addCard(root, dp(8));
        deviceSettingsToggle = button(showRecovery
                ? "本体・UVC 設定を閉じる" : "本体・UVC 設定", false, v -> {
            boolean open = deviceSettings.getVisibility() != View.VISIBLE;
            deviceSettings.setVisibility(open ? View.VISIBLE : View.GONE);
            deviceSettingsToggle.setText(open
                    ? "本体・UVC 設定を閉じる" : "本体・UVC 設定");
        });
        addRow(utilsCard, deviceSettingsToggle, 0);

        deviceSettings = new LinearLayout(this);
        deviceSettings.setOrientation(LinearLayout.VERTICAL);
        deviceSettings.setVisibility(showRecovery ? View.VISIBLE : View.GONE);
        enableButton = button("UVCを有効化", false, v -> enableUvc());
        addRow(deviceSettings, enableButton, 0);
        glassesStatusButton = button("本体の状態を確認", false, v -> checkGlassesStatus());
        addRow(deviceSettings, glassesStatusButton, dp(8));
        glasses60Button = button("60fps に設定（再起動）", false, v -> prepareGlasses60());
        addRow(deviceSettings, glasses60Button, dp(8));
        TextView glassesModeNote = new TextView(this);
        glassesModeNote.setText("再起動します。画角が狭くなります。");
        applyTextAppearance(glassesModeNote, com.google.android.material.R.attr.textAppearanceBodySmall);
        glassesModeNote.setTextColor(onSurfaceVariantColor());
        addRow(deviceSettings, glassesModeNote, dp(4));
        glassesNormalButton = button("720p/30 に戻す（再起動）", false, v -> restoreGlassesNormal());
        addRow(deviceSettings, glassesNormalButton, dp(8));
        glassesRecoverButton = button("中断設定を復旧", false, v -> recoverGlassesPreparation());
        addRow(deviceSettings, glassesRecoverButton, dp(8));
        addRow(utilsCard, deviceSettings, dp(8));

        LinearLayout viewCard = addCard(root, dp(8));
        displayModeButton = button("XREAL 第二画面: "
                + DISPLAY_MODES[RecordingPresentation.getDisplayMode(preferences)], false, view -> {
            int selected = RecordingPresentation.getDisplayMode(preferences);
            new MaterialAlertDialogBuilder(this)
                    .setTitle("XREAL 第二画面")
                    .setSingleChoiceItems(DISPLAY_MODES, selected, (choice, index) -> {
                        preferences.edit().putInt(RecordingPresentation.MODE_PREFERENCE, index).apply();
                        displayModeButton.setText("XREAL 第二画面: " + DISPLAY_MODES[index]);
                        choice.dismiss();
                    })
                    .setNegativeButton("キャンセル", null)
                    .show();
        });
        addRow(viewCard, displayModeButton, 0);
        glassesLiveModeButton = button("", false, v -> showQualityPicker());
        glassesLiveModeButton.setSingleLine(false);

        selectedVideoRoute = preferences.getInt("video_route", RecordService.ROUTE_TCP_HEVC);
        if (selectedVideoRoute < 0 || selectedVideoRoute >= VIDEO_ROUTES.length) {
            selectedVideoRoute = RecordService.ROUTE_TCP_HEVC;
        }
        updateQualityButton();
        addRow(viewCard, glassesLiveModeButton, dp(8));

        LinearLayout settingsCard = addCard(root, dp(8));
        keepScreenCheck = checkBox("録画中はスマホを点灯", preferences.getBoolean("keep_screen", true),
                (button, checked) -> preferences.edit().putBoolean("keep_screen", checked).apply());
        addRow(settingsCard, keepScreenCheck, 0);
        diagnosticsCheck = checkBox("診断用 HEVC・WAV を別保存",
                preferences.getBoolean("diagnostics", false),
                (button, checked) -> preferences.edit().putBoolean("diagnostics", checked).apply());
        addRow(settingsCard, diagnosticsCheck, dp(4));

        LinearLayout outputCard = addCard(root, dp(8));
        destinationView = new TextView(this);
        applyTextAppearance(destinationView, com.google.android.material.R.attr.textAppearanceBodyLarge);
        destinationView.setTextColor(onSurfaceColor());
        updateDestination();
        addRow(outputCard, destinationView, 0);
        folderButton = button("保存先を選ぶ", false, v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQ_OUTPUT_FOLDER);
        });
        addRow(outputCard, folderButton, dp(8));
        resetFolderButton = button("標準の保存先に戻す", false, v -> {
            outputTree = null;
            outputLabel = DEFAULT_OUTPUT_LABEL;
            preferences.edit().remove("output_tree").putString("output_label", outputLabel).apply();
            updateDestination();
        });
        addRow(outputCard, resetFolderButton, dp(8));

        int savedMode = preferences.getInt(RecordService.PREF_SAVE_MODE, RecordService.SAVE_MODE_FMP4);
        selectedSaveMode = savedMode == RecordService.SAVE_MODE_RAW ? 1
                : savedMode == RecordService.SAVE_MODE_RAW_ONLY ? 3
                : savedMode == 2 ? 2 : 0;
        saveModeButton = button("保存モード: " + SAVE_MODES[selectedSaveMode], false, view -> {
            if (RecordService.isRecordingOrStarting()) {
                setStatus("録画中は保存モードを変更できません");
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle("保存モード")
                    .setSingleChoiceItems(SAVE_MODES, selectedSaveMode, (choice, index) -> {
                        selectedSaveMode = index;
                        int modeConst = index == 1 ? RecordService.SAVE_MODE_RAW
                                : index == 3 ? RecordService.SAVE_MODE_RAW_ONLY
                                : index == 2 ? 2 : RecordService.SAVE_MODE_MP4;
                        preferences.edit().putInt(RecordService.PREF_SAVE_MODE, modeConst).commit();
                        saveModeButton.setText("保存モード: " + SAVE_MODES[index]);
                        choice.dismiss();
                    })
                    .setNegativeButton("キャンセル", null)
                    .show();
        });
        saveModeButton.setSingleLine(false);
        LinearLayout modeCard = addCard(root, dp(8));
        addRow(modeCard, saveModeButton, 0);

        TextView help = new TextView(this);
        help.setText("接続して録画開始 → 停止");
        applyTextAppearance(help, com.google.android.material.R.attr.textAppearanceBodySmall);
        help.setTextColor(onSurfaceVariantColor());
        help.setPadding(0, dp(16), 0, dp(8));
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(surfaceColor());
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(pad, pad + insets.getSystemWindowInsetTop(),
                    pad, pad + insets.getSystemWindowInsetBottom());
            return insets;
        });
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(surfaceColor());
        container.addView(scroll);
        screenSaver = new FrameLayout(this);
        screenSaver.setBackgroundColor(Color.BLACK);
        screenSaver.setVisibility(View.GONE);
        screenSaverContent = new LinearLayout(this);
        screenSaverContent.setOrientation(LinearLayout.VERTICAL);
        screenSaverContent.setGravity(Gravity.CENTER);
        screenSaverStatus = new TextView(this);
        screenSaverStatus.setTextColor(Color.rgb(150, 150, 150));
        screenSaverStatus.setTextSize(20);
        screenSaverStatus.setGravity(Gravity.CENTER);
        screenSaverStatus.setSingleLine(false);
        screenSaverStatus.setMaxLines(10);
        screenSaverContent.addView(screenSaverStatus);
        screenSaverRate = new TextView(this);
        screenSaverRate.setTextColor(Color.rgb(150, 150, 150));
        screenSaverRate.setTextSize(14);
        screenSaverRate.setGravity(Gravity.CENTER);
        screenSaverContent.addView(screenSaverRate);
        screenSaverAudio = new TextView(this);
        screenSaverAudio.setTextColor(Color.rgb(150, 150, 150));
        screenSaverAudio.setTextSize(16);
        screenSaverAudio.setGravity(Gravity.CENTER);
        screenSaverContent.addView(screenSaverAudio);
        screenSaverWaveform = new RecordingPresentation.WaveformView(this);
        float density = getResources().getDisplayMetrics().density;
        screenSaverContent.addView(screenSaverWaveform, new LinearLayout.LayoutParams(
                (int) (220 * density), (int) (40 * density)));
        TextView screenSaverHint = new TextView(this);
        screenSaverHint.setText("タップで戻る");
        screenSaverHint.setTextColor(Color.rgb(150, 150, 150));
        screenSaverHint.setTextSize(14);
        screenSaverHint.setGravity(Gravity.CENTER);
        screenSaverContent.addView(screenSaverHint);
        screenSaver.addView(screenSaverContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        screenSaver.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setScreenSaverVisible(false);
                lastTouchMs = SystemClock.elapsedRealtime();
            }
        });
        container.addView(screenSaver, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return container;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastTouchMs = SystemClock.elapsedRealtime();
        }
        return super.dispatchTouchEvent(event);
    }

    private void setScreenSaverVisible(boolean visible) {
        if (screenSaver == null || (screenSaver.getVisibility() == View.VISIBLE) == visible) {
            return;
        }
        screenSaver.setVisibility(visible ? View.VISIBLE : View.GONE);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            if (visible) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
        }
    }

    private void updateDestination() {
        if (destinationView != null) {
            destinationView.setText("保存先: " + outputLabel);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OUTPUT_FOLDER || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri tree = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            setStatus("選択したフォルダーへの書き込み権限がありません");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(tree, flags);
            String label = "選択したフォルダー";
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = getContentResolver().query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    label = cursor.getString(0);
                }
            }
            outputTree = tree.toString();
            outputLabel = label;
            preferences.edit().putString("output_tree", outputTree)
                    .putString("output_label", outputLabel).apply();
            updateDestination();
        } catch (RuntimeException e) {
            setStatus("保存先を設定できません: " + e.getMessage());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateConfirmedLiveMode(null);
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(usbReceiver, filter);
            }
            receiverRegistered = true;
        }
        IntentFilter devFilter = new IntentFilter(ACTION_DEV);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(devReceiver, devFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(devReceiver, devFilter);
        }
        findDevice();
        pollStatus();
        startTelemetry();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPreviewSession();
        telemetryRunning = false;
        updateConfirmedLiveMode(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setScreenSaverVisible(false);
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        try {
            unregisterReceiver(devReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void requestRuntimePermissions() {
        boolean needAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;
        boolean needCamera = checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= 33) {
            boolean needNotifications = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED;
            if (needAudio || needCamera || needNotifications) {
                requestPermissions(new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.CAMERA,
                        Manifest.permission.POST_NOTIFICATIONS
                }, REQ_PERMISSIONS);
            }
        } else if (needAudio || needCamera) {
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA
            }, REQ_PERMISSIONS);
        }
    }

    private void findDevice() {
        if (usbManager == null) {
            setStatus("UsbManagerが利用できません");
            return;
        }
        UsbDevice found = null;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getVendorId() == 0x3318 && d.getProductId() == 0x043e) {
                found = d;
                break;
            }
        }
        if (found == null) {
            device = null;
            updateConfirmedLiveMode(null);
            setStatus("XREAL 1S (3318:043e) が見つかりません");
            return;
        }
        if (device != null && !device.getDeviceName().equals(found.getDeviceName())) {
            updateConfirmedLiveMode(null);
        }
        device = found;
        if (!usbManager.hasPermission(found)) {
            setStatus("USB権限を要求しています...");
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(found, pi);
            return;
        }
        if (selectedVideoRoute == RecordService.ROUTE_TCP_HEVC) {
            setStatus("XREAL 1S 接続済み");
        } else if (hasUvc(found)) {
            setStatus("準備完了: UVC IF10 / EP 0x89 を検出");
        } else {
            setStatus("UVC interfaceがありません。「UVCを有効化」を押してください");
        }
    }

    private boolean hasUvc(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() == 14) {
                return true;
            }
        }
        return false;
    }

    /** Switch UI and camera session between photo (viewfinder) and video (recording). */
    private void applyMode() {
        if (photoCard != null) {
            photoCard.setVisibility(photoMode ? View.VISIBLE : View.GONE);
        }
        if (videoCard != null) {
            videoCard.setVisibility(photoMode ? View.GONE : View.VISIBLE);
        }
        if (recordingIndicator != null) {
            recordingIndicator.setVisibility(photoMode ? View.GONE : View.VISIBLE);
        }
        if (photoMode) {
            if (RecordService.isRecordingOrStarting()) {
                stopRecording();
            }
            startPreviewSession();
        } else {
            stopPreviewSession();
        }
    }

    /** Start a preview-only camera session via RecordService (no MP4/audio/files). */
    private void startPreviewSession() {
        findDevice();
        if (device == null || usbManager == null || !usbManager.hasPermission(device)) {
            setStatus("XREAL 1S の接続と USB 権限を確認してください");
            return;
        }
        RecordService.previewSink = hevcPreview;
        Intent intent = new Intent(this, RecordService.class)
                .setAction(RecordService.ACTION_START)
                .putExtra(RecordService.EXTRA_DEVICE, device)
                .putExtra(RecordService.EXTRA_SAVE_MODE, RecordService.SAVE_MODE_PREVIEW);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        setStatus("画角プレビューを開始中");
    }

    private void stopPreviewSession() {
        Intent intent = new Intent(this, RecordService.class)
                .setAction(RecordService.ACTION_STOP);
        startService(intent);
        RecordService.previewSink = null;
        if (hevcPreview != null && !hevcPreview.isStopped()) {
            hevcPreview.stop();
        }
    }

    private void captureStill() {
        if (hevcPreview == null || previewView == null) {
            setStatus("プレビューが利用できません");
            return;
        }
        if (hevcPreview.isStopped()) {
            setStatus("画角プレビューの準備中です。少し待ってからもう一度押してください");
            return;
        }
        byte[] png = hevcPreview.capturePng();
        if (png == null) {
            setStatus("画像を取得できませんでした");
            return;
        }
        try {
            java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir == null) {
                dir = getExternalFilesDir(null);
            }
            if (dir == null) {
                throw new java.io.IOException("保存先がありません");
            }
            dir.mkdirs();
            String name = "XREAL_STILL_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    Locale.US).format(new java.util.Date()) + ".png";
            java.io.File out = new java.io.File(dir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(png);
            }
            setStatus("静止画を保存: " + name);
        } catch (Exception e) {
            setStatus("静止画の保存に失敗: " + e.getMessage());
        }
    }

    private void startRecording() {
        if (preferences.getBoolean("live_mode_uncertain", false)) {
            setStatus("録画前に再起動なしのモード切替で本体の状態を確認してください");
            return;
        }
        if (glassesStatusPending) {
            setStatus("本体の状態を確認中です");
            return;
        }
        if (preferences.getBoolean("glasses_recovery_required", false)) {
            setStatus("録画前に本体の状態を確認してください");
            return;
        }
        if (RecordService.isRecordingOrStarting()) {
            setStatus("すでに録画中です");
            return;
        }
        findDevice();
        if (device == null || usbManager == null) {
            setStatus("XREAL 1Sが見つかりません");
            return;
        }
        if (!usbManager.hasPermission(device)) {
            setStatus("USB権限を許可してから再試行してください");
            return;
        }
        int route = selectedVideoRoute;
        if (route != RecordService.ROUTE_TCP_HEVC && !hasUvc(device)) {
            setStatus("UVCがありません。先にUVCを有効化してください");
            return;
        }
        Intent intent = new Intent(this, RecordService.class)
                .setAction(RecordService.ACTION_START)
                .putExtra(RecordService.EXTRA_DEVICE, device)
                .putExtra(RecordService.EXTRA_OUTPUT_TREE, outputTree)
                .putExtra(RecordService.EXTRA_OUTPUT_LABEL, outputLabel)
                .putExtra(RecordService.EXTRA_DIAGNOSTICS, diagnosticsCheck.isChecked())
                .putExtra(RecordService.EXTRA_VIDEO_ROUTE, route)
                .putExtra(RecordService.EXTRA_SAVE_MODE,
                        selectedSaveMode == 1 ? RecordService.SAVE_MODE_RAW
                        : selectedSaveMode == 3 ? RecordService.SAVE_MODE_RAW_ONLY
                        : selectedSaveMode == 2 ? 2 : RecordService.SAVE_MODE_MP4);
        preferences.edit().putInt("video_route", route).apply();
        RecordService.setStartPending(true);
        try {
            android.content.ComponentName started;
            if (Build.VERSION.SDK_INT >= 26) {
                started = startForegroundService(intent);
            } else {
                started = startService(intent);
            }
            if (started == null) {
                RecordService.setStartPending(false);
                setStatus("録画サービスを開始できませんでした");
                return;
            }
        } catch (RuntimeException e) {
            RecordService.setStartPending(false);
            setStatus("録画開始に失敗しました: " + e.getMessage());
            return;
        }
        setStatus("録画を開始しています...");
    }

    private void stopRecording() {
        Intent intent = new Intent(this, RecordService.class).setAction(RecordService.ACTION_STOP);
        startService(intent);
        setStatus("録画を停止しています...");
    }

    /** Ask for USB permission first, then enable UVC once it is granted. */
    private void requestUvcEnable() {
        findDevice();
        if (device == null || usbManager == null) {
            setStatus("XREAL 1S が見つかりません");
            return;
        }
        if (!usbManager.hasPermission(device)) {
            pendingUvcEnable = true;
            setStatus("USB権限を許可してください（権限ダイアログを確認）");
            return;
        }
        enableUvc();
    }

    private void enableUvc() {
        findDevice();
        if (device == null || usbManager == null) {
            setStatus("XREAL 1Sが見つかりません");
            return;
        }
        if (!usbManager.hasPermission(device)) {
            setStatus("USB権限を許可してから再試行してください");
            return;
        }
        setStatus("UVC有効化コマンドを送信しています...");
        HidUvc.enable(usbManager, device, new HidUvc.Callback() {
            @Override
            public void onResult(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            setStatus("UVC有効化を要求しました。再接続とUSB権限の確認を待ってください。\n" + message);
                        } else {
                            setStatus("UVC有効化に失敗: " + message);
                        }
                    }
                });
            }
        });
    }

    private void checkGlassesStatus() {
        if (RecordService.isRecordingOrStarting() || glassesStatusPending) {
            setStatus("録画または本体の状態確認が進行中です");
            return;
        }
        findDevice();
        if (device == null || usbManager == null || !usbManager.hasPermission(device)) {
            setStatus("XREAL 1S の接続と USB 権限を確認してください");
            return;
        }
        glassesStatusPending = true;
        setStatus("本体の状態を確認しています...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                String observedLiveMode = null;
                boolean startupConfirmed = false;
                boolean liveConfirmed = false;
                try {
                    result = waitForGlassesStatus();
                    startupConfirmed = "本体は通常設定です".equals(result)
                            || "本体は TCP 1600×1200 / 60 fps の試験設定です".equals(result);
                    if ("本体は通常設定です".equals(result)) {
                        result = "起動設定は通常です";
                    }
                    try {
                        String live = new GlassesStatusClient(getApplicationContext()).readLiveMode(liveToken());
                        observedLiveMode = live;
                        liveConfirmed = true;
                        String[] parts = live.split(" ");
                        result += "\n現在の TCP 設定: " + parts[0] + "×" + parts[1]
                                + " / " + parts[2] + "fps";
                    } catch (Exception e) {
                        result += "\n現在の TCP 設定は確認できません";
                    }
                } catch (Exception e) {
                    result = "本体の状態を確認できません: " + e.getMessage();
                }
                final String message = result;
                final boolean confirmed = startupConfirmed;
                final boolean confirmedLive = liveConfirmed;
                final String checkedLiveMode = observedLiveMode;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        glassesStatusPending = false;
                        if (confirmed) {
                            preferences.edit().putBoolean("glasses_recovery_required", false)
                                    .putBoolean("glasses_owns_preparation", false).apply();
                        }
                        if (confirmedLive) {
                            preferences.edit().putBoolean("live_mode_uncertain", false).apply();
                        }
                        updateConfirmedLiveMode(checkedLiveMode);
                        setStatus(message);
                    }
                });
            }
        }, "xreal-status-check").start();
    }

    private static String liveToken() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private void showQualityPicker() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("画質")
                .setItems(VIDEO_ROUTES, (dialog, which) -> {
                    if (RecordService.isRecordingOrStarting() || glassesStatusPending) {
                        setStatus("録画中は画質を変更できません");
                        return;
                    }
                    if (which == 3) {
                        // UVC HEVC route: guide the user to enable UVC and grant USB permission.
                        selectedVideoRoute = RecordService.ROUTE_UVC_HEVC;
                        preferences.edit().putInt("video_route", selectedVideoRoute).apply();
                        updateQualityButton();
                        findDevice();
                        if (device == null || usbManager == null) {
                            setStatus("XREAL 1S を接続してください");
                        } else if (!usbManager.hasPermission(device)) {
                            pendingUvcEnable = true;
                            setStatus("USB権限を許可してください（権限ダイアログを確認）");
                        } else if (!hasUvc(device)) {
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("UVC HEVC の準備")
                                    .setMessage("UVCを有効化します。本体の再接続後、USB権限の許可を求められます。")
                                    .setPositiveButton("UVCを有効化", (d, w) -> requestUvcEnable())
                                    .setNegativeButton("キャンセル", null)
                                    .show();
                        } else {
                            setStatus("UVC HEVC の準備完了");
                        }
                    } else {
                        // TCP route: 0 = 720p/30, 1 = 1600p/30, 2 = 1600p/60
                        String mode = which == 0 ? "1280 720 30"
                                : which == 1 ? "1600 1200 30" : "1600 1200 60";
                        selectedVideoRoute = RecordService.ROUTE_TCP_HEVC;
                        preferences.edit().putInt("video_route", selectedVideoRoute).apply();
                        switchLiveMode(mode);
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void updateQualityButton() {
        if (glassesLiveModeButton == null) {
            return;
        }
        String label;
        if (selectedVideoRoute == RecordService.ROUTE_UVC_HEVC) {
            label = "画質: UVC HEVC";
        } else if (confirmedLiveMode != null && confirmedLiveMode.equals("1600 1200 30")) {
            label = "画質: 1600p/30（高画質）";
        } else if (confirmedLiveMode != null && confirmedLiveMode.equals("1600 1200 60")) {
            label = "画質: 1600p/60（高レート）";
        } else {
            label = "画質: 720p/30（広い画角）";
        }
        glassesLiveModeButton.setText(label);
    }

    private void updateConfirmedLiveMode(String mode) {
        confirmedLiveMode = mode != null ? mode : DEFAULT_LIVE_MODE;
        updateQualityButton();
    }

    private void switchLiveMode(String target) {
        if (RecordService.isRecordingOrStarting() || glassesStatusPending
                || preferences.getBoolean("glasses_recovery_required", false)) {
            setStatus("録画または本体の状態を確認してください");
            return;
        }
        findDevice();
        if (device == null || usbManager == null || !usbManager.hasPermission(device)) {
            setStatus("XREAL 1S の接続と USB 権限を確認してください");
            return;
        }
        glassesStatusPending = true;
        updateConfirmedLiveMode(null);
        if (!preferences.edit().putBoolean("live_mode_uncertain", true).commit()) {
            glassesStatusPending = false;
            setStatus("安全状態を保存できませんでした");
            return;
        }
        setStatus("本体の録画設定を確認中...");
        new Thread(() -> {
            String message;
            boolean confirmed = false;
            try {
                GlassesStatusClient client = new GlassesStatusClient(getApplicationContext());
                String current = client.readLiveMode(liveToken());
                if (!target.equals(current)) {
                    client.setLiveMode(current, target, liveToken());
                    current = client.readLiveMode(liveToken());
                }
                if (!target.equals(current)) {
                    throw new java.io.IOException("設定の確認に失敗しました");
                }
                confirmed = true;
                String[] parts = target.split(" ");
                message = parts[0] + "×" + parts[1] + " / " + parts[2]
                        + "fps に設定しました。次の録画から有効です";
            } catch (Exception e) {
                message = "本体の録画設定を確認できません: " + e.getMessage();
            }
            final boolean stateConfirmed = confirmed;
            final String result = message;
            runOnUiThread(() -> {
                glassesStatusPending = false;
                if (stateConfirmed) {
                    preferences.edit().putBoolean("live_mode_uncertain", false).apply();
                    updateConfirmedLiveMode(target);
                }
                setStatus(result);
            });
        }, "xreal-live-mode").start();
    }

    private void prepareGlasses60() {
        if (RecordService.isRecordingOrStarting() || glassesStatusPending) {
            setStatus("録画または本体の操作が進行中です");
            return;
        }
        if (preferences.getBoolean("glasses_recovery_required", false)) {
            setStatus("先に本体の状態を確認してください");
            return;
        }
        findDevice();
        if (device == null || usbManager == null || !usbManager.hasPermission(device)) {
            setStatus("XREAL 1S の接続と USB 権限を確認してください");
            return;
        }
        glassesStatusPending = true;
        if (!preferences.edit().putBoolean("glasses_recovery_required", true).commit()) {
            glassesStatusPending = false;
            setStatus("安全状態を保存できないため本体の操作を中止しました");
            return;
        }
        setStatus("TCP 60fps を準備しています。本体が再起動します。"
                + "映像が消えた場合、USB-C の抜き差しで通常設定に戻ることがあります...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                boolean ready = false;
                boolean rebootRequested = false;
                boolean preparationStarted = false;
                try {
                    GlassesStatusClient client = new GlassesStatusClient(getApplicationContext());
                    preparationStarted = true;
                    client.prepare60(new GlassesStatusClient.OwnershipMarker() {
                        @Override
                        public void markOwned() throws java.io.IOException {
                            if (!preferences.edit().putBoolean("glasses_owns_preparation", true).commit()) {
                                throw new java.io.IOException("復旧情報を保存できません");
                            }
                        }
                    });
                    rebootRequested = true;
                    try {
                        client.rebootGlasses();
                    } catch (Exception ignored) {
                        // Reboot can close the socket before its acknowledgement is received.
                    }
                    Thread.sleep(30000);
                    result = waitForGlassesStatus();
                    if ("本体は TCP 1600×1200 / 60 fps の試験設定です".equals(result)) {
                        ready = true;
                        result += "。録画を開始できます。映像が消えた場合、USB-C の抜き差しで"
                                + "通常設定に戻ることがあります。次回の本体再起動で通常設定に戻ります。";
                    } else {
                        result = "60fps の設定を確認できません: " + result;
                    }
                } catch (Exception e) {
                    result = (rebootRequested ? "再起動後の状態を確認できません: "
                            : "準備を完了できません。録画前に本体の状態を確認してください: ")
                            + e.getMessage();
                }
                final String message = result;
                final boolean confirmed = ready;
                final boolean noPreparation = !preparationStarted;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        glassesStatusPending = false;
                        if (confirmed || noPreparation) {
                            preferences.edit().putBoolean("glasses_recovery_required", false)
                                    .putBoolean("glasses_owns_preparation", false).apply();
                        }
                        setStatus(message);
                    }
                });
            }
        }, "xreal-prepare-60fps").start();
    }

    private void restoreGlassesNormal() {
        if (RecordService.isRecordingOrStarting() || glassesStatusPending
                || preferences.getBoolean("glasses_recovery_required", false)) {
            setStatus("録画または本体の状態を確認してください");
            return;
        }
        findDevice();
        if (device == null || usbManager == null || !usbManager.hasPermission(device)) {
            setStatus("XREAL 1S の接続と USB 権限を確認してください");
            return;
        }
        glassesStatusPending = true;
        if (!preferences.edit().putBoolean("glasses_recovery_required", true).commit()) {
            glassesStatusPending = false;
            setStatus("安全状態を保存できないため本体の操作を中止しました");
            return;
        }
        setStatus("通常設定に戻しています。本体が再起動します...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                boolean confirmed = false;
                boolean reconnectStarted = false;
                try {
                    GlassesStatusClient client = new GlassesStatusClient(getApplicationContext());
                    String state = client.readStatus();
                    if ("本体は通常設定です".equals(state)) {
                        result = state;
                        confirmed = true;
                    } else if ("本体は TCP 1600×1200 / 60 fps の試験設定です".equals(state)) {
                        reconnectStarted = true;
                        try {
                            client.rebootGlasses();
                        } catch (Exception ignored) {
                            // Reboot can close the socket before its acknowledgement is received.
                        }
                        Thread.sleep(30000);
                        result = waitForGlassesStatus();
                        confirmed = "本体は通常設定です".equals(result);
                    } else {
                        result = "通常設定に戻せません: " + state;
                    }
                } catch (Exception e) {
                    result = "本体の状態を確認できません: " + e.getMessage();
                }
                final String message = result;
                final boolean ready = confirmed;
                final boolean noReconnect = !reconnectStarted;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        glassesStatusPending = false;
                        if (ready || noReconnect) {
                            preferences.edit().putBoolean("glasses_recovery_required", false).apply();
                        }
                        setStatus(message);
                    }
                });
            }
        }, "xreal-restore-normal").start();
    }

    private void recoverGlassesPreparation() {
        if (RecordService.isRecordingOrStarting() || glassesStatusPending
                || !preferences.getBoolean("glasses_recovery_required", false)
                || !preferences.getBoolean("glasses_owns_preparation", false)) {
            setStatus("このアプリが作成した未完了の準備を確認できません");
            return;
        }
        findDevice();
        if (device == null || usbManager == null || !usbManager.hasPermission(device)) {
            setStatus("XREAL 1S の接続と USB 権限を確認してください");
            return;
        }
        glassesStatusPending = true;
        setStatus("中断した本体設定を確認しています...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                boolean confirmed = false;
                try {
                    result = new GlassesStatusClient(getApplicationContext()).recoverOwnedPreparation(
                            preferences.getBoolean("glasses_owns_preparation", false));
                    confirmed = "本体は通常設定です".equals(result)
                            || "本体は TCP 1600×1200 / 60 fps の試験設定です".equals(result);
                } catch (Exception e) {
                    result = "自動復旧できません: " + e.getMessage();
                }
                final String message = result;
                final boolean ready = confirmed;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        glassesStatusPending = false;
                        if (ready) {
                            preferences.edit().putBoolean("glasses_recovery_required", false)
                                    .putBoolean("glasses_owns_preparation", false).apply();
                        }
                        setStatus(message);
                    }
                });
            }
        }, "xreal-recover-preparation").start();
    }

    private String waitForGlassesStatus() throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return new GlassesStatusClient(getApplicationContext()).readStatus();
            } catch (Exception e) {
                last = e;
                if (attempt < 3) {
                    Thread.sleep(10000);
                }
            }
        }
        throw last;
    }

    private void pollStatus() {
        String s = RecordService.getStatus();
        boolean recording = RecordService.isRecording();
        boolean recordingBusy = RecordService.isRecordingOrStarting();
        boolean stopping = RecordService.isStopping();
        boolean videoStalled = RecordService.isVideoStalled();
        int audioPeak = recording ? RecordService.getAudioPeak() : 0;
        boolean keepScreen = recording && keepScreenCheck.isChecked();
        if (keepScreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (SystemClock.elapsedRealtime() - lastTouchMs >= 30000) {
                setScreenSaverVisible(true);
            }
            long saverSeconds = RecordService.getElapsedSeconds();
            String audioState = s != null && s.startsWith("録画中: 音声停止") ? "音声停止"
                    : !RecordService.hasAudioData() ? "音声データを待機中"
                    : "音声入力あり";
            boolean videoReceived = !stopping && !videoStalled && RecordService.hasVideoData();
            SpannableString saverText = new SpannableString(stopping ? "保存中"
                    : videoStalled ? (RecordService.hasVideoData() ? "映像受信停止" : "映像を受信できません") : videoReceived
                    ? String.format(Locale.US, "● REC  %02d:%02d:%02d",
                            saverSeconds / 3600, (saverSeconds / 60) % 60, saverSeconds % 60)
                    : "映像を待機中");
            if (videoReceived) {
                saverText.setSpan(new ForegroundColorSpan(Color.rgb(255, 95, 95)), 0, 5,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (videoStalled) {
                saverText.setSpan(new ForegroundColorSpan(Color.rgb(255, 185, 80)), 0,
                        saverText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            screenSaverStatus.setText(saverText);
            screenSaverRate.setText(videoStalled ? "" :
                    RecordService.getVideoRateSummary().replace("受信平均 ", ""));
            screenSaverAudio.setText(audioState);
            screenSaverWaveform.addPeak(audioPeak);
            int position = (int) ((saverSeconds / 60) % 4);
            float shift = 24 * getResources().getDisplayMetrics().density;
            screenSaverContent.setTranslationX((position == 0 || position == 3) ? -shift : shift);
            screenSaverContent.setTranslationY(position < 2 ? -shift : shift);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setScreenSaverVisible(false);
        }
        boolean needsRecovery = !glassesStatusPending
                && preferences.getBoolean("glasses_recovery_required", false);
        if (needsRecovery && !recoveryShown) {
            deviceSettings.setVisibility(View.VISIBLE);
            deviceSettingsToggle.setText("本体・UVC 設定を閉じる");
        }
        recoveryShown = needsRecovery;
        long seconds = RecordService.getElapsedSeconds();
        boolean videoReceived = recording && !stopping && !videoStalled && RecordService.hasVideoData();
        boolean showRecordingStatus = !photoMode;
        recordingIndicator.setVisibility(showRecordingStatus ? View.VISIBLE : View.GONE);
        if (showRecordingStatus) {
            recordingIndicator.setText(!recording ? "待機中" : stopping ? "保存中"
                    : videoStalled ? (RecordService.hasVideoData() ? "映像受信停止" : "映像を受信できません") : videoReceived
                    ? String.format(Locale.US, "● REC  %02d:%02d:%02d",
                            seconds / 3600, (seconds / 60) % 60, seconds % 60) : "映像を待機中");
            recordingIndicator.setTextColor(videoReceived ? Color.rgb(255, 95, 95)
                    : videoStalled ? Color.rgb(255, 185, 80) : Color.WHITE);
        }
        phoneWaveform.setVisibility(showRecordingStatus && recording ? View.VISIBLE : View.GONE);
        if (showRecordingStatus && recording) {
            phoneWaveform.addPeak(audioPeak);
        }
        if (telemetryView != null) {
            telemetryView.setText(telemetryText);
        }
        audioIndicator.setVisibility(showRecordingStatus ? View.VISIBLE : View.GONE);
        if (showRecordingStatus) {
            audioIndicator.setText(!recording ? "" : s != null && s.startsWith("録画中: 音声停止")
                    ? "音声停止・映像のみ録画中" : !RecordService.hasAudioData()
                    ? "音声データを待機中" : "USBマイク · モノラル");
        }
        // The service's idle text must not overwrite this Activity's USB/UVC status.
        if (videoStalled) {
            statusView.setText(RecordService.hasVideoData()
                    ? "映像が3秒以上届いていません" : "開始から10秒以上、映像が届いていません");
            lastStatus = "";
        } else if (recording && s != null && s.startsWith("録画中")) {
            statusView.setText(s + "\n" + RecordService.getVideoRateSummary());
            lastStatus = s;
        } else if (s != null && !"待機中".equals(s) && !s.equals(lastStatus)) {
            lastStatus = s;
            statusView.setText(s);
        }
        startButton.setEnabled(!recordingBusy && !glassesStatusPending
                && !preferences.getBoolean("live_mode_uncertain", false)
                && !preferences.getBoolean("glasses_recovery_required", false));
        stopButton.setEnabled(recording && !stopping);
        folderButton.setEnabled(!recordingBusy);
        resetFolderButton.setEnabled(!recordingBusy);
        diagnosticsCheck.setEnabled(!recordingBusy);
        saveModeButton.setEnabled(!recordingBusy);
        glassesStatusButton.setEnabled(!recordingBusy && !glassesStatusPending);
        glassesLiveModeButton.setEnabled(!recordingBusy && !glassesStatusPending
                && !preferences.getBoolean("glasses_recovery_required", false));
        glasses60Button.setEnabled(!recordingBusy && !glassesStatusPending
                && !preferences.getBoolean("glasses_recovery_required", false));
        glassesNormalButton.setEnabled(!recordingBusy && !glassesStatusPending
                && !preferences.getBoolean("glasses_recovery_required", false));
        glassesRecoverButton.setEnabled(!recordingBusy && !glassesStatusPending
                && preferences.getBoolean("glasses_recovery_required", false)
                && preferences.getBoolean("glasses_owns_preparation", false));
        enableButton.setEnabled(!recordingBusy && !glassesStatusPending);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollStatus();
            }
        }, 500);
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
                        sb.append(String.format(Locale.US, "グラス温度 %.1f℃", snap.temperatureC));
                    }
                    if (snap.wearingState != null) {
                        String worn = snap.wearingState == 2 ? "装着" : snap.wearingState == 1 ? "非装着" : "状態" + snap.wearingState;
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append("装着状態: ").append(worn);
                    }
                    if (snap.bootCount != null) {
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append("起動回数 ").append(snap.bootCount);
                    }
                    if (snap.firmwareVersion != null) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append("FW ").append(snap.firmwareVersion);
                    }
                } catch (Exception e) {
                    sb.append("グラス情報: 取得できません (").append(e.getMessage()).append(")");
                }
                String phoneTemp = readPhoneBatteryTemperature();
                if (phoneTemp != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(phoneTemp);
                }
                String remaining = readRemainingRecordingTime();
                if (remaining != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(remaining);
                }
                telemetryText = sb.length() > 0 ? sb.toString() : "グラス情報: 取得待ち";
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "xreal-telemetry");
        telemetryThread.setDaemon(true);
        telemetryThread.start();
    }

    private String readRemainingRecordingTime() {
        try {
            StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
            long available = stat.getAvailableBytes();
            double bps = 0;
            if (RecordService.isRecording()) {
                bps = RecordService.getVideoBitrateBps();
            }
            if (bps <= 0) {
                if (selectedVideoRoute == 1) {
                    bps = 2_000_000; // UVC HEVC ~2 Mbps
                } else if (confirmedLiveMode != null) {
                    if (confirmedLiveMode.contains("1600")) {
                        bps = confirmedLiveMode.endsWith("60") ? 34_000_000 : 15_700_000;
                    } else {
                        bps = confirmedLiveMode.endsWith("60") ? 33_000_000 : 17_000_000;
                    }
                } else {
                    bps = 17_000_000; // TCP 720/30 default
                }
            }
            double totalBps = bps + 128_000; // AAC audio
            double seconds = available * 8.0 / totalBps;
            long minutes = (long) (seconds / 60);
            String mode = bps > 20_000_000 ? "60fps/34M" : bps > 10_000_000 ? "17M" : "2M";
            return String.format(Locale.US, "録画可能 約%d分 (残り%.1fGB, %s)",
                    minutes, available / 1_000_000_000.0, mode);
        } catch (Exception e) {
            return null;
        }
    }

    private String readPhoneBatteryTemperature() {
        try {
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) {
                return null;
            }
            int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenths == Integer.MIN_VALUE) {
                return null;
            }
            return String.format(Locale.US, "スマホ温度 %.1f℃", tenths / 10.0f);
        } catch (Exception e) {
            return null;
        }
    }

    private void setStatus(String s) {
        if (statusView != null) {
            statusView.setText(s);
        }
    }
}
