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
import android.hardware.display.DisplayManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.Display;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "jp.xreal.recorder.USB_PERMISSION";
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
    private CheckBox sideDisplayCheck;
    private Button startButton;
    private Button stopButton;
    private Button enableButton;
    private Button glassesStatusButton;
    private Button glasses60Button;
    private Button glassesNormalButton;
    private Button glassesRecoverButton;
    private Button folderButton;
    private Button resetFolderButton;
    private CheckBox diagnosticsCheck;
    private TextView destinationView;
    private Spinner videoRouteView;
    private SharedPreferences preferences;
    private String outputTree;
    private String outputLabel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastStatus = "";
    private boolean receiverRegistered = false;
    private boolean glassesStatusPending;

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
            } else {
                setStatus("USB権限が拒否されました");
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
        requestRuntimePermissions();
        findDevice();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("XREAL 1S + Eye Recorder");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("映像は選択した経路から取得\n音声: 48kHz / モノラル AAC");
        note.setTextSize(13);
        note.setTextColor(Color.rgb(190, 190, 190));
        note.setPadding(0, 16, 0, 24);
        root.addView(note);

        recordingIndicator = new TextView(this);
        recordingIndicator.setTextSize(32);
        recordingIndicator.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        recordingIndicator.setTextColor(Color.rgb(255, 95, 95));
        root.addView(recordingIndicator);
        phoneWaveform = new RecordingPresentation.WaveformView(this);
        root.addView(phoneWaveform, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 70));
        audioIndicator = new TextView(this);
        audioIndicator.setTextColor(Color.LTGRAY);
        audioIndicator.setTextSize(16);
        root.addView(audioIndicator);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(Color.WHITE);
        statusView.setText("待機中");
        statusView.setPadding(0, 0, 0, 24);
        root.addView(statusView);

        startButton = new Button(this);
        startButton.setText("録画開始");
        styleButton(startButton, Color.rgb(145, 28, 36));
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRecording();
            }
        });
        root.addView(startButton);

        stopButton = new Button(this);
        stopButton.setText("録画停止");
        styleButton(stopButton, Color.rgb(65, 65, 65));
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopRecording();
            }
        });
        root.addView(stopButton);

        enableButton = new Button(this);
        enableButton.setText("UVCを有効化（UVCが見えない場合）");
        styleButton(enableButton, Color.rgb(45, 45, 45));
        enableButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableUvc();
            }
        });
        root.addView(enableButton);

        glassesStatusButton = new Button(this);
        glassesStatusButton.setText("本体の状態を確認（試験中）");
        styleButton(glassesStatusButton, Color.rgb(45, 45, 45));
        glassesStatusButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkGlassesStatus();
            }
        });
        root.addView(glassesStatusButton);

        glasses60Button = new Button(this);
        glasses60Button.setText(PhoneUsbReconnect.isSupported()
                ? "TCP 60fps を準備（USB 再接続・root）"
                : "TCP 60fps を準備（本体再起動・試験中）");
        styleButton(glasses60Button, Color.rgb(45, 45, 45));
        glasses60Button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prepareGlasses60();
            }
        });
        root.addView(glasses60Button);

        glassesNormalButton = new Button(this);
        glassesNormalButton.setText(PhoneUsbReconnect.isSupported()
                ? "TCP 通常設定に戻す（USB 再接続・root）"
                : "TCP 通常設定に戻す（本体再起動）");
        styleButton(glassesNormalButton, Color.rgb(45, 45, 45));
        glassesNormalButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restoreGlassesNormal();
            }
        });
        root.addView(glassesNormalButton);

        glassesRecoverButton = new Button(this);
        glassesRecoverButton.setText("中断した本体設定を復旧（試験中）");
        styleButton(glassesRecoverButton, Color.rgb(45, 45, 45));
        glassesRecoverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recoverGlassesPreparation();
            }
        });
        root.addView(glassesRecoverButton);

        sideDisplayCheck = new CheckBox(this);
        sideDisplayCheck.setText("XREAL サイド表示用（中央に拡大。オフ: 固定表示用・右上）");
        sideDisplayCheck.setTextColor(Color.WHITE);
        sideDisplayCheck.setChecked(preferences.getBoolean("display_side", false));
        sideDisplayCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                preferences.edit().putBoolean("display_side", checked).apply();
            }
        });
        root.addView(sideDisplayCheck);

        TextView routeLabel = new TextView(this);
        routeLabel.setText("画質・映像の取得経路（ビットレートは録画時の実測例）");
        routeLabel.setTextColor(Color.WHITE);
        routeLabel.setTextSize(14);
        routeLabel.setPadding(0, 24, 0, 8);
        root.addView(routeLabel);
        videoRouteView = new Spinner(this);
        String[] routes = new String[]{
                "TCP 通常720p 30fps 約17.0 Mbps（60fps 準備時は1600×1200 約34 Mbps）",
                "UVC HEVC 1512p 60fps 約2.03 Mbps",
                "UVC MJPEG 1080p 30fps 約21.16 Mbps（試験中）",
                "UVC MJPEG 1080p 60fps 約39.16 Mbps（試験中）"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, routes) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                view.setBackgroundColor(Color.rgb(35, 35, 35));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        videoRouteView.setAdapter(adapter);
        videoRouteView.setSelection(preferences.getInt("video_route", 0));
        videoRouteView.setBackgroundColor(Color.rgb(45, 45, 45));
        root.addView(videoRouteView);
        TextView rateNote = new TextView(this);
        rateNote.setText("実測値は撮影内容によって変わります。\n"
                + "TCP 60fps は通常の30fpsより左右の画角が狭い状態を確認しています。");
        rateNote.setTextColor(Color.LTGRAY);
        rateNote.setTextSize(12);
        root.addView(rateNote);

        destinationView = new TextView(this);
        destinationView.setTextColor(Color.WHITE);
        destinationView.setTextSize(14);
        destinationView.setPadding(0, 24, 0, 12);
        updateDestination();
        root.addView(destinationView);

        folderButton = new Button(this);
        folderButton.setText("保存先を選ぶ");
        styleButton(folderButton, Color.rgb(45, 45, 45));
        folderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQ_OUTPUT_FOLDER);
            }
        });
        root.addView(folderButton);

        resetFolderButton = new Button(this);
        resetFolderButton.setText("標準の保存先に戻す");
        styleButton(resetFolderButton, Color.rgb(45, 45, 45));
        resetFolderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                outputTree = null;
                outputLabel = DEFAULT_OUTPUT_LABEL;
                preferences.edit().remove("output_tree").putString("output_label", outputLabel).apply();
                updateDestination();
            }
        });
        root.addView(resetFolderButton);

        diagnosticsCheck = new CheckBox(this);
        diagnosticsCheck.setText("診断用 HEVC・WAV を別保存（容量を多く使用）");
        diagnosticsCheck.setTextColor(Color.WHITE);
        diagnosticsCheck.setChecked(preferences.getBoolean("diagnostics", false));
        diagnosticsCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                preferences.edit().putBoolean("diagnostics", checked).apply();
            }
        });
        root.addView(diagnosticsCheck);

        TextView help = new TextView(this);
        help.setText("\n使い方:\n"
                + "1. XREAL 1SをUSB-Cで接続\n"
                + "2. USB権限を許可\n"
                + "3. 録画開始 → 停止\n\n"
                + "UVCが見えない場合は「UVCを有効化」を押し、再接続とUSB権限の確認を待ってください。\n\n"
                + "診断用ファイルはアプリ専用フォルダーに保存されます。\n\n"
                + "画面消灯中もForeground Serviceで継続します。");
        help.setTextSize(12);
        help.setTextColor(Color.rgb(170, 170, 170));
        help.setPadding(0, 24, 0, 24);
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(root);
        return scroll;
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

    private void styleButton(Button button, int enabledColor) {
        GradientDrawable enabled = new GradientDrawable();
        enabled.setColor(enabledColor);
        enabled.setCornerRadius(16);
        GradientDrawable disabled = new GradientDrawable();
        disabled.setColor(Color.rgb(28, 28, 28));
        disabled.setCornerRadius(16);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        states.addState(new int[]{}, enabled);
        button.setBackground(states);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(usbReceiver, filter);
            }
            receiverRegistered = true;
        }
        findDevice();
        pollStatus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
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
            setStatus("XREAL 1S (3318:043e) が見つかりません");
            return;
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
        if (videoRouteView != null
                && videoRouteView.getSelectedItemPosition() == RecordService.ROUTE_TCP_HEVC) {
            setStatus("XREAL 1S 接続済み: TCP映像は録画開始時に確認します");
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

    private void startRecording() {
        if (glassesStatusPending) {
            setStatus("本体の状態を確認中です");
            return;
        }
        if (preferences.getBoolean("glasses_recovery_required", false)) {
            setStatus("録画前に本体の状態を確認してください");
            return;
        }
        if (RecordService.isRecording()) {
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
        int route = videoRouteView.getSelectedItemPosition();
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
                .putExtra(RecordService.EXTRA_VIDEO_ROUTE, route);
        preferences.edit().putInt("video_route", route).apply();
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        setStatus("録画を開始しています...");
    }

    private void stopRecording() {
        Intent intent = new Intent(this, RecordService.class).setAction(RecordService.ACTION_STOP);
        startService(intent);
        setStatus("録画を停止しています...");
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
        if (RecordService.isRecording() || glassesStatusPending) {
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
                try {
                    result = waitForGlassesStatus();
                } catch (Exception e) {
                    result = "本体の状態を確認できません: " + e.getMessage();
                }
                final String message = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        glassesStatusPending = false;
                        if ("本体は通常設定です".equals(message)
                                || "本体は TCP 1600×1200 / 60 fps の試験設定です".equals(message)) {
                            preferences.edit().putBoolean("glasses_recovery_required", false)
                                    .putBoolean("glasses_owns_preparation", false).apply();
                        }
                        setStatus(message);
                    }
                });
            }
        }, "xreal-status-check").start();
    }

    private void prepareGlasses60() {
        if (RecordService.isRecording() || glassesStatusPending) {
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
        setStatus("TCP 60fps を準備しています。本体が再起動します...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                boolean ready = false;
                boolean rebootRequested = false;
                boolean preparationStarted = false;
                try {
                    if (PhoneUsbReconnect.isSupported()) {
                        PhoneUsbReconnect.checkAvailable();
                    }
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
                    if (PhoneUsbReconnect.isSupported()) {
                        PhoneUsbReconnect.reconnect();
                    } else {
                        try {
                            client.rebootGlasses();
                        } catch (Exception ignored) {
                            // Reboot can close the socket before its acknowledgement is received.
                        }
                    }
                    Thread.sleep(30000);
                    result = waitForGlassesStatus();
                    if ("本体は TCP 1600×1200 / 60 fps の試験設定です".equals(result)) {
                        ready = true;
                        result += "。録画を開始できます。次回の本体再起動で通常設定に戻ります。";
                        if (PhoneUsbReconnect.isSupported() && !hasExternalDisplay()) {
                            result += " 外部画面は検出されていません（USB 2.0 接続時を含む）。";
                        }
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
        if (RecordService.isRecording() || glassesStatusPending
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
                    if (PhoneUsbReconnect.isSupported()) {
                        PhoneUsbReconnect.checkAvailable();
                    }
                    GlassesStatusClient client = new GlassesStatusClient(getApplicationContext());
                    String state = client.readStatus();
                    if ("本体は通常設定です".equals(state)) {
                        result = state;
                        confirmed = true;
                    } else if ("本体は TCP 1600×1200 / 60 fps の試験設定です".equals(state)) {
                        reconnectStarted = true;
                        if (PhoneUsbReconnect.isSupported()) {
                            PhoneUsbReconnect.reconnect();
                        } else {
                            try {
                                client.rebootGlasses();
                            } catch (Exception ignored) {
                                // Reboot can close the socket before its acknowledgement is received.
                            }
                        }
                        Thread.sleep(30000);
                        result = waitForGlassesStatus();
                        confirmed = "本体は通常設定です".equals(result);
                        if (confirmed && PhoneUsbReconnect.isSupported() && !hasExternalDisplay()) {
                            result += "。外部画面は検出されていません（USB 2.0 接続時を含む）";
                        }
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
        if (RecordService.isRecording() || glassesStatusPending
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

    private boolean hasExternalDisplay() {
        DisplayManager manager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            return false;
        }
        for (Display display : manager.getDisplays()) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY && display.isValid()) {
                return true;
            }
        }
        return false;
    }

    private void pollStatus() {
        String s = RecordService.getStatus();
        boolean recording = RecordService.isRecording();
        long seconds = RecordService.getElapsedSeconds();
        recordingIndicator.setText(recording ? String.format(Locale.US, "● REC  %02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60) : "待機中");
        phoneWaveform.setVisibility(recording ? View.VISIBLE : View.GONE);
        if (recording) {
            phoneWaveform.addPeak(RecordService.getAudioPeak());
        }
        audioIndicator.setText(!recording ? "" : s != null && s.startsWith("録画中: 音声停止")
                ? "音声停止・映像のみ録画中" : RecordService.isAudioExactZero()
                ? "音声データが完全にゼロです" : RecordService.isAudioQuiet()
                ? "音声入力が非常に小さい状態です" : "USBマイク · モノラル");
        // The service's idle text must not overwrite this Activity's USB/UVC status.
        if (recording && s != null && s.startsWith("録画中")) {
            statusView.setText(s + "\n" + RecordService.getVideoRateSummary());
            lastStatus = s;
        } else if (s != null && !"待機中".equals(s) && !s.equals(lastStatus)) {
            lastStatus = s;
            statusView.setText(s);
        }
        startButton.setEnabled(!recording && !glassesStatusPending
                && !preferences.getBoolean("glasses_recovery_required", false));
        stopButton.setEnabled(recording);
        folderButton.setEnabled(!recording);
        resetFolderButton.setEnabled(!recording);
        diagnosticsCheck.setEnabled(!recording);
        videoRouteView.setEnabled(!recording);
        glassesStatusButton.setEnabled(!recording && !glassesStatusPending);
        glasses60Button.setEnabled(!recording && !glassesStatusPending
                && !preferences.getBoolean("glasses_recovery_required", false));
        glassesNormalButton.setEnabled(!recording && !glassesStatusPending
                && !preferences.getBoolean("glasses_recovery_required", false));
        glassesRecoverButton.setEnabled(!recording && !glassesStatusPending
                && preferences.getBoolean("glasses_recovery_required", false)
                && preferences.getBoolean("glasses_owns_preparation", false));
        enableButton.setEnabled(!recording && !glassesStatusPending);
        sideDisplayCheck.setEnabled(!recording);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollStatus();
            }
        }, 500);
    }

    private void setStatus(String s) {
        if (statusView != null) {
            statusView.setText(s);
        }
    }
}
