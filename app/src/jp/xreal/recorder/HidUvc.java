package jp.xreal.recorder;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * Optional UVC enable over the 1S HID control interface (IF0).
 *
 * 0xD2 reads the USB config; only uvc0 (bits 12-13) and enable (bits 16-17)
 * are set to 1, then 0xD3 is sent. The glasses may re-enumerate; wait for
 * device detection and USB permission instead of unplugging them immediately.
 */
public final class HidUvc {
    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private HidUvc() {
    }

    public static void enable(final UsbManager usbManager, final UsbDevice device,
                              final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                UsbDeviceConnection conn = null;
                try {
                    UsbEndpoint out = null;
                    UsbEndpoint in = null;
                    UsbInterface hid = null;
                    for (int i = 0; i < device.getInterfaceCount(); i++) {
                        UsbInterface itf = device.getInterface(i);
                        if (itf.getInterfaceClass() != UsbConstants.USB_CLASS_HID) {
                            continue;
                        }
                        for (int j = 0; j < itf.getEndpointCount(); j++) {
                            UsbEndpoint ep = itf.getEndpoint(j);
                            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {
                                continue;
                            }
                            if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                                out = ep;
                            } else if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                                in = ep;
                            }
                        }
                        if (out != null && in != null) {
                            hid = itf;
                            break;
                        }
                    }
                    if (hid == null || out == null || in == null) {
                        callback.onResult(false, "HID control interface not found");
                        return;
                    }

                    conn = usbManager.openDevice(device);
                    if (conn == null) {
                        callback.onResult(false, "openDevice failed");
                        return;
                    }
                    if (!conn.claimInterface(hid, true)) {
                        callback.onResult(false, "claimInterface(HID) failed");
                        return;
                    }

                    byte[] resp = new byte[1024];
                    int n = transfer(conn, out, in, buildFrame(0xD2, null, 0), resp);
                    if (n < 27 || resp[0] != (byte) 0xFD || resp[22] != 0) {
                        callback.onResult(false, "USB configuration read failed or response invalid");
                        return;
                    }
                    // Byte 22 is the command status; the 4-byte payload starts at 23.
                    int raw = (resp[23] & 0xff)
                            | ((resp[24] & 0xff) << 8)
                            | ((resp[25] & 0xff) << 16)
                            | ((resp[26] & 0xff) << 24);

                    int next = (raw & ~(0x3 << 12) & ~(0x3 << 16))
                            | (1 << 12) | (1 << 16);
                    byte[] payload = new byte[]{
                            (byte) (next & 0xff),
                            (byte) ((next >> 8) & 0xff),
                            (byte) ((next >> 16) & 0xff),
                            (byte) ((next >> 24) & 0xff)
                    };
                    byte[] frame = buildFrame(0xD3, payload, payload.length);
                    java.util.Arrays.fill(resp, (byte) 0);
                    n = transfer(conn, out, in, frame, resp);
                    if (n < 23 || resp[0] != (byte) 0xFD || resp[22] != 0) {
                        callback.onResult(false, "0xD3 transfer failed or response invalid");
                        return;
                    }
                    callback.onResult(true, String.format(java.util.Locale.US,
                            "old=0x%08X next=0x%08X", raw, next));
                } catch (Throwable t) {
                    callback.onResult(false, t.getClass().getSimpleName()
                            + ": " + String.valueOf(t.getMessage()));
                } finally {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "xreal-hid-uvc").start();
    }

    private static int transfer(UsbDeviceConnection conn, UsbEndpoint out,
                                UsbEndpoint in, byte[] frame, byte[] resp) {
        int sent = conn.bulkTransfer(out, frame, frame.length, 1000);
        if (sent < 0) {
            sent = request(conn, out, frame, frame.length);
        }
        if (sent != frame.length) {
            return -1;
        }
        int received = conn.bulkTransfer(in, resp, resp.length, 2000);
        if (received < 0) {
            received = request(conn, in, resp, resp.length);
        }
        return received;
    }

    private static int request(UsbDeviceConnection conn, UsbEndpoint ep,
                               byte[] data, int length) {
        UsbRequest request = new UsbRequest();
        try {
            if (!request.initialize(conn, ep)) {
                return -1;
            }
            ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
            if (!request.queue(buffer, length)) {
                return -1;
            }
            UsbRequest done = conn.requestWait();
            if (done != request) {
                return -1;
            }
            return buffer.position();
        } catch (Throwable t) {
            return -1;
        } finally {
            try {
                request.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] buildFrame(int cmd, byte[] payload, int plen) {
        byte[] frame = new byte[22 + plen];
        frame[0] = (byte) 0xFD;
        int len = 17 + plen;
        frame[5] = (byte) (len & 0xff);
        frame[6] = (byte) ((len >> 8) & 0xff);
        int reqId = 1;
        frame[7] = (byte) (reqId & 0xff);
        frame[8] = (byte) ((reqId >> 8) & 0xff);
        frame[9] = (byte) ((reqId >> 16) & 0xff);
        frame[10] = (byte) ((reqId >> 24) & 0xff);
        frame[15] = (byte) (cmd & 0xff);
        frame[16] = (byte) ((cmd >> 8) & 0xff);
        if (payload != null && plen > 0) {
            System.arraycopy(payload, 0, frame, 22, plen);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 5, len);
        int value = (int) crc.getValue();
        frame[1] = (byte) (value & 0xff);
        frame[2] = (byte) ((value >> 8) & 0xff);
        frame[3] = (byte) ((value >> 16) & 0xff);
        frame[4] = (byte) ((value >> 24) & 0xff);
        return frame;
    }
}
