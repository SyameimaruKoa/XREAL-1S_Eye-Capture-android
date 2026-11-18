package jp.xreal.recorder;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Decodes the live HEVC stream to a TextureView and grabs still frames. */
final class HevcPreview {
    interface Listener {
        void onError(String message);
    }

    private final TextureView view;
    private final Listener listener;
    private MediaCodec decoder;
    private Thread thread;
    private volatile boolean running;
    private final Object lock = new Object();
    private long lastPtsUs;

    HevcPreview(TextureView view, Listener listener) {
        this.view = view;
        this.listener = listener;
    }

    void start(int width, int height, byte[] csd) {
        stop();
        SurfaceTexture texture = view.getSurfaceTexture();
        if (texture == null) {
            listener.onError("プレビュー用のSurfaceが未準備です");
            return;
        }
        Surface surface = new Surface(texture);
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height);
            if (csd != null && csd.length > 0) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
            }
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            decoder.configure(format, surface, null, 0);
            decoder.start();
            running = true;
            thread = new Thread(() -> drain(), "xreal-preview-decoder");
            thread.start();
        } catch (Exception e) {
            listener.onError("プレビュー開始失敗: " + e.getMessage());
            stop();
        }
    }

    /** Feed one Annex-B access unit from the camera. */
    void feed(byte[] annexB, long timestampNs) {
        if (!running || decoder == null) {
            return;
        }
        long ptsUs = timestampNs / 1000;
        if (ptsUs <= lastPtsUs) {
            ptsUs = lastPtsUs + 1;
        }
        lastPtsUs = ptsUs;
        try {
            int index = decoder.dequeueInputBuffer(50000);
            if (index < 0) {
                return;
            }
            ByteBuffer input = decoder.getInputBuffer(index);
            input.clear();
            input.put(annexB);
            decoder.queueInputBuffer(index, 0, annexB.length, ptsUs, 0);
        } catch (Exception ignored) {
            // Preview is best-effort; recording must not be affected.
        }
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int index = decoder.dequeueOutputBuffer(info, 30000);
                if (index >= 0) {
                    decoder.releaseOutputBuffer(index, true);
                }
            } catch (Exception e) {
                break;
            }
        }
    }

    /** Grab the currently displayed frame as a PNG byte array. */
    byte[] capturePng() {
        Bitmap bitmap = view.getBitmap();
        if (bitmap == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    boolean isStopped() {
        return !running;
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
            } catch (Exception ignored) {
                // ignore
            }
            decoder = null;
        }
    }
}
