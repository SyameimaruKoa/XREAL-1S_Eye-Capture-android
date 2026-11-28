package com.syameimarukoa.xreal_recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

/** Controls recording from a shell broadcast without launching any UI. */
public final class HeadlessReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) {
            return;
        }
        if ("start".equals(cmd)) {
            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            UsbDevice device = null;
            if (manager != null) {
                for (UsbDevice candidate : manager.getDeviceList().values()) {
                    if (candidate.getVendorId() == 0x3318
                            && candidate.getProductId() == 0x043e) {
                        device = candidate;
                        break;
                    }
                }
            }
            boolean permitted = device != null && manager != null
                    && manager.hasPermission(device);
            Log.i("XrealRec", "headless start device=" + (device != null)
                    + " permitted=" + permitted);
            if (!permitted) {
                return;
            }
            SharedPreferences prefs = context.getSharedPreferences(
                    "recording_options", Context.MODE_PRIVATE);
            int route = prefs.getInt("video_route", RecordService.ROUTE_TCP_HEVC);
            Log.i("XrealRec", "headless start route=" + route);
            Intent start = new Intent(context, RecordService.class)
                    .setAction(RecordService.ACTION_START)
                    .putExtra(RecordService.EXTRA_DEVICE, device)
                    .putExtra(RecordService.EXTRA_VIDEO_ROUTE, route)
                    .putExtra(RecordService.EXTRA_DIAGNOSTICS,
                            intent.getBooleanExtra(RecordService.EXTRA_DIAGNOSTICS, false));
            context.startForegroundService(start);
        } else if ("stop".equals(cmd)) {
            Intent stop = new Intent(context, RecordService.class)
                    .setAction(RecordService.ACTION_STOP);
            context.startService(stop);
        }
    }
}
