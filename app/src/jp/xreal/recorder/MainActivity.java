package jp.xreal.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "jp.xreal.recorder.USB_PERMISSION";
    private static final int REQ_PERMISSIONS = 1001;

    private UsbManager usbManager;
    private UsbDevice device;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private Button enableButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastStatus = "";
    private boolean receiverRegistered = false;

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
        setContentView(buildUi());
        requestRuntimePermissions();
        findDevice();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("XREAL 1S + Eye Recorder");
        title.setTextSize(22);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("UVC HEVCパススルー + USBマイク 1ch AAC\n"
                + "映像: 2048x1512 / 60fps / HEVC\n"
                + "音声: 48kHz / 2ch取得 → LのみAAC 1ch\n"
                + "保存先: Android/data/jp.xreal.recorder/files/Movies");
        note.setTextSize(13);
        note.setPadding(0, 16, 0, 24);
        root.addView(note);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setText("待機中");
        statusView.setPadding(0, 0, 0, 24);
        root.addView(statusView);

        startButton = new Button(this);
        startButton.setText("録画開始");
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRecording();
            }
        });
        root.addView(startButton);

        stopButton = new Button(this);
        stopButton.setText("録画停止");
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
        enableButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableUvc();
            }
        });
        root.addView(enableButton);

        TextView help = new TextView(this);
        help.setText("\n使い方:\n"
                + "1. XREAL 1SをUSB-Cで接続\n"
                + "2. USB権限を許可\n"
                + "3. 録画開始 → 停止\n\n"
                + "UVCが見えない場合は「UVCを有効化」を押し、再接続とUSB権限の確認を待ってください。\n\n"
                + "画面消灯中もForeground Serviceで継続します。");
        help.setTextSize(12);
        help.setPadding(0, 24, 0, 24);
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
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
        if (hasUvc(found)) {
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
        if (!hasUvc(device)) {
            setStatus("UVCがありません。先にUVCを有効化してください");
            return;
        }
        Intent intent = new Intent(this, RecordService.class)
                .setAction(RecordService.ACTION_START)
                .putExtra(RecordService.EXTRA_DEVICE, device);
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

    private void pollStatus() {
        String s = RecordService.getStatus();
        // The service's idle text must not overwrite this Activity's USB/UVC status.
        if (s != null && !"待機中".equals(s) && !s.equals(lastStatus)) {
            lastStatus = s;
            statusView.setText(s);
        }
        boolean recording = RecordService.isRecording();
        startButton.setEnabled(!recording);
        stopButton.setEnabled(recording);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollStatus();
            }
        }, 500);
    }

    private void setStatus(String s) {
        lastStatus = s;
        if (statusView != null) {
            statusView.setText(s);
        }
    }
}
