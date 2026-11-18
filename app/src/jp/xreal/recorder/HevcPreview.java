package jp.xreal.recorder;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

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
    private int sourceWidth;
    private int sourceHeight;
    private byte[] sourceCsd;
    private final LinkedBlockingQueue<byte[]> recentAu = new LinkedBlockingQueue<>(2);

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
        sourceWidth = width;
        sourceHeight = height;
        sourceCsd = csd == null ? null : csd.clone();
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
        // Keep a recent key frame first: a fresh decoder needs an IDR to start.
        if (UvcSource.isKeyFrame(annexB)) {
            recentAu.clear();
            recentAu.offer(annexB.clone());
        }
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

    /** Decode a recent access unit at the camera's full resolution and return PNG. */
    byte[] capturePng() {
        byte[] au = recentAu.peek();
        android.util.Log.d("XrealPreview", "capture: au=" + (au == null ? -1 : au.length)
                + " source=" + sourceWidth + "x" + sourceHeight
                + " csd=" + (sourceCsd == null ? -1 : sourceCsd.length));
        if (au == null || sourceWidth <= 0) {
            return captureViewBitmap();
        }
        MediaCodec stillDecoder = null;
        android.media.ImageReader reader = null;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_HEVC, sourceWidth, sourceHeight);
            if (sourceCsd != null && sourceCsd.length > 0) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sourceCsd));
            }
            reader = android.media.ImageReader.newInstance(
                    sourceWidth, sourceHeight, ImageFormat.YUV_420_888, 1);
            stillDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            stillDecoder.configure(format, reader.getSurface(), null, 0);
            stillDecoder.start();
            int inIndex = stillDecoder.dequeueInputBuffer(2_000_000);
            if (inIndex < 0) {
                return captureViewBitmap();
            }
            // Prepend CSD (VPS/SPS/PPS) so the fresh decoder can decode this AU.
            byte[] inputData = au;
            if (sourceCsd != null && sourceCsd.length > 0) {
                inputData = new byte[sourceCsd.length + au.length];
                System.arraycopy(sourceCsd, 0, inputData, 0, sourceCsd.length);
                System.arraycopy(au, 0, inputData, sourceCsd.length, au.length);
            }
            ByteBuffer input = stillDecoder.getInputBuffer(inIndex);
            input.clear();
            input.put(inputData);
            stillDecoder.queueInputBuffer(inIndex, 0, inputData.length, 0, 0);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            Image image = null;
            long deadline = System.currentTimeMillis() + 2000;
            while (image == null && System.currentTimeMillis() < deadline) {
                int outIndex = stillDecoder.dequeueOutputBuffer(info, 100_000);
                if (outIndex >= 0) {
                    stillDecoder.releaseOutputBuffer(outIndex, false);
                    image = reader.acquireLatestImage();
                }
            }
            if (image == null) {
                android.util.Log.w("XrealPreview", "no decoded image from still decoder");
                return captureViewBitmap();
            }
            android.util.Log.d("XrealPreview", "decoded image " + image.getWidth() + "x" + image.getHeight()
                    + " format=" + image.getFormat());
            try {
                Bitmap frame = yuvToBitmap(image);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                frame.compress(Bitmap.CompressFormat.PNG, 100, out);
                frame.recycle();
                return out.toByteArray();
            } finally {
                image.close();
            }
        } catch (Exception e) {
            android.util.Log.w("XrealPreview", "full-res capture failed: " + e.getMessage());
            return captureViewBitmap();
        } finally {
            if (stillDecoder != null) {
                try {
                    stillDecoder.stop();
                    stillDecoder.release();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            if (reader != null) {
                reader.close();
            }
        }
    }

    private byte[] captureViewBitmap() {
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

    private static Bitmap yuvToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int uRow = planes[1].getRowStride();
        int vRow = planes[2].getRowStride();
        int uPixel = planes[1].getPixelStride();
        int vPixel = planes[2].getPixelStride();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width];
        for (int row = 0; row < height; row++) {
            yBuf.position(row * yRow);
            uBuf.position((row / 2) * uRow);
            vBuf.position((row / 2) * vRow);
            for (int col = 0; col < width; col++) {
                int y = yBuf.get(col) & 0xff;
                int u = uBuf.get((col / 2) * uPixel) & 0xff;
                int v = vBuf.get((col / 2) * vPixel) & 0xff;
                int c = y - 16;
                int d = u - 128;
                int e = v - 128;
                int r = clamp((298 * c + 409 * e + 128) >> 8);
                int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = clamp((298 * c + 516 * d + 128) >> 8);
                pixels[col] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
            bitmap.setPixels(pixels, 0, width, 0, row, width, 1);
        }
        return bitmap;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    boolean isStopped() {
        return !running;
    }

    void stop() {
        running = false;
        recentAu.clear();
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
