package jp.xreal.recorder;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * USB microphone capture.
 *
 * AudioRecord is opened as 48 kHz / 2ch / PCM_32BIT and must be routed to the
 * XREAL USB audio device. Only the left channel is fed to the AAC encoder
 * (D: 1ch). The raw stereo stream is written to a 32-bit WAV sidecar for
 * later L/R analysis.
 */
public final class AudioSource {
    private static final String TAG = "XrealRec";
    private static final int RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int BYTES_PER_FRAME = 8; // 2ch * 32-bit
    private static final int ENCODER_CHUNK_BYTES = 8192; // 1024 frames * 8 bytes

    public interface Listener {
        void onError(String message);
    }

    private final Context context;
    private final long t0Ns;
    private final MuxerSink muxer;
    private final File wavFile;
    private final Listener listener;

    private AudioRecord record;
    private MediaCodec encoder;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean failed;
    private volatile String error = "";
    private long audioStartNs;
    private long framesWritten;
    private long zeroReads;

    public AudioSource(Context context, long t0Ns, MuxerSink muxer,
                       File wavFile, Listener listener) {
        this.context = context;
        this.t0Ns = t0Ns;
        this.muxer = muxer;
        this.wavFile = wavFile;
        this.listener = listener;
    }

    public void start() throws Exception {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo usb = findUsbInput(am);
        if (usb == null) {
            throw new IllegalStateException("USB microphone not found");
        }

        int minBuf = AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_32BIT);
        if (minBuf <= 0) {
            throw new IllegalStateException("getMinBufferSize failed: " + minBuf);
        }
        // Match the p16-verified probe: 192000-byte buffer and READ_BLOCKING.
        int bufSize = Math.max(minBuf, RATE * BYTES_PER_FRAME / 2);

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_32BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        record = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufSize)
                .build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord init failed");
        }
        if (!record.setPreferredDevice(usb)) {
            throw new IllegalStateException("setPreferredDevice(USB) failed");
        }

        MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
        encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
        encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        audioStartNs = SystemClock.elapsedRealtimeNanos();
        record.startRecording();

        AudioDeviceInfo routed = null;
        for (int i = 0; i < 10; i++) {
            routed = record.getRoutedDevice();
            if (routed != null && isUsb(routed.getType())) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (routed == null || !isUsb(routed.getType())) {
            throw new IllegalStateException("AudioRecord is not routed to USB: "
                    + (routed == null ? "null" : routed.getType() + "/" + routed.getProductName()));
        }
        Log.i(TAG, "audio routed id=" + routed.getId() + " type=" + routed.getType()
                + " name=" + routed.getProductName() + " minBuf=" + minBuf + " bufSize=" + bufSize);

        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "xreal-audio");
        thread.start();
    }

    private static AudioDeviceInfo findUsbInput(AudioManager am) {
        if (am == null) {
            return null;
        }
        AudioDeviceInfo fallback = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return d;
            }
            if (isUsb(d.getType()) && fallback == null) {
                fallback = d;
            }
        }
        return fallback;
    }

    private static boolean isUsb(int type) {
        return type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    private void loop() {
        RandomAccessFile wav = null;
        int readSize = Math.max(AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_32BIT),
                RATE * BYTES_PER_FRAME / 2);
        ByteBuffer pcm = ByteBuffer.allocateDirect(readSize).order(ByteOrder.LITTLE_ENDIAN);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastPtsUs = 0;

        try {
            wav = new RandomAccessFile(wavFile, "rw");
            writeWavHeader(wav);

            while (running) {
                pcm.clear();
                int n = record.read(pcm, readSize, AudioRecord.READ_BLOCKING);
                if (n <= 0) {
                    zeroReads++;
                    if (zeroReads > 1000) {
                        throw new IllegalStateException("AudioRecord.read returned 0 repeatedly");
                    }
                    continue;
                }
                zeroReads = 0;
                pcm.position(0);
                pcm.limit(n);

                byte[] raw = new byte[n];
                pcm.get(raw);
                wav.write(raw);

                // Split the 0.5 s read into chunks small enough for the AAC input buffer.
                for (int off = 0; off < n; off += ENCODER_CHUNK_BYTES) {
                    int chunk = Math.min(ENCODER_CHUNK_BYTES, n - off);
                    int frames = chunk / BYTES_PER_FRAME;
                    if (frames <= 0) {
                        continue;
                    }
                    ByteBuffer mono = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < frames; i++) {
                        int left = pcm.getInt(off + i * BYTES_PER_FRAME);
                        mono.putShort((short) (left >> 16));
                    }

                    long ptsUs = (audioStartNs - t0Ns) / 1000
                            + framesWritten * 1_000_000L / RATE;
                    framesWritten += frames;
                    lastPtsUs = ptsUs;

                    queueEncoder(mono.array(), ptsUs);
                    drainEncoder(info, false);
                }
            }
        } catch (Throwable t) {
            if (running || record != null) {
                failed = true;
                error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
                listener.onError(error);
            }
        } finally {
            running = false;
            try {
                if (record != null) {
                    record.stop();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (encoder != null) {
                    queueEos(lastPtsUs);
                    drainEncoder(info, true);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (record != null) {
                    record.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (wav != null) {
                    patchWav(wav);
                    wav.close();
                }
            } catch (Throwable ignored) {
            }
            record = null;
            encoder = null;
        }
    }

    private void queueEncoder(byte[] data, long ptsUs) throws Exception {
        int index = encoder.dequeueInputBuffer(10000);
        if (index < 0) {
            return;
        }
        ByteBuffer input = encoder.getInputBuffer(index);
        if (input == null) {
            return;
        }
        input.clear();
        int copy = Math.min(data.length, input.capacity());
        input.put(data, 0, copy);
        encoder.queueInputBuffer(index, 0, copy, ptsUs, 0);
    }

    private void queueEos(long ptsUs) {
        try {
            int index = encoder.dequeueInputBuffer(100000);
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        } catch (Throwable ignored) {
        }
    }

    private void drainEncoder(MediaCodec.BufferInfo info, boolean untilEos) {
        while (true) {
            int index;
            try {
                index = encoder.dequeueOutputBuffer(info, untilEos ? 10000 : 0);
            } catch (Throwable t) {
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxer.addAudioTrack(encoder.getOutputFormat());
            } else if (index >= 0) {
                try {
                    ByteBuffer output = encoder.getOutputBuffer(index);
                    // MediaCodec may emit csd-0 as a 2-byte codec-config buffer.
                    // Writing that as an AAC sample corrupts the first MP4 packet.
                    if (output != null && info.size > 2
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        byte[] data = new byte[info.size];
                        output.position(info.offset);
                        output.get(data);
                        muxer.writeAudio(data, info.presentationTimeUs);
                    }
                } finally {
                    encoder.releaseOutputBuffer(index, false);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return;
                }
            } else {
                return;
            }
        }
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isFailed() {
        return failed;
    }

    public String getError() {
        return error;
    }

    public long getFramesWritten() {
        return framesWritten;
    }

    private static void writeWavHeader(RandomAccessFile f) throws Exception {
        f.setLength(0);
        f.writeBytes("RIFF");
        writeLe32(f, 36);
        f.writeBytes("WAVEfmt ");
        writeLe32(f, 16);
        writeLe16(f, 1);
        writeLe16(f, CHANNELS);
        writeLe32(f, RATE);
        writeLe32(f, RATE * CHANNELS * 4);
        writeLe16(f, CHANNELS * 4);
        writeLe16(f, 32);
        f.writeBytes("data");
        writeLe32(f, 0);
    }

    private static void patchWav(RandomAccessFile f) throws Exception {
        long dataSize = f.length() - 44;
        f.seek(4);
        writeLe32(f, (int) (36 + dataSize));
        f.seek(40);
        writeLe32(f, (int) dataSize);
    }

    private static void writeLe16(RandomAccessFile f, int v) throws Exception {
        f.write(v & 0xff);
        f.write((v >> 8) & 0xff);
    }

    private static void writeLe32(RandomAccessFile f, int v) throws Exception {
        f.write(v & 0xff);
        f.write((v >> 8) & 0xff);
        f.write((v >> 16) & 0xff);
        f.write((v >> 24) & 0xff);
    }
}
