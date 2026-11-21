package com.syameimarukoa.xreal_recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

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
            if (device == null || manager == null || !manager.hasPermission(device)) {
                return;
            }
            Intent start = new Intent(context, RecordService.class)
                    .setAction(RecordService.ACTION_START)
                    .putExtra(RecordService.EXTRA_DEVICE, device);
            context.startForegroundService(start);
        } else if ("stop".equals(cmd)) {
            Intent stop = new Intent(context, RecordService.class)
                    .setAction(RecordService.ACTION_STOP);
            context.startService(stop);
        }
    }
}
