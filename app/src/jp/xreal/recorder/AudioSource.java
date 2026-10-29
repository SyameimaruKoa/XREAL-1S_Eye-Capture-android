package jp.xreal.recorder;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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
        void onSilenceChanged(boolean silent);
        void onAudioLevel(int peak, boolean exactZero);
    }

    private final Context context;
    private final long t0Ns;
    private final RecordingMuxer muxer;
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
    private volatile long encodedBytes;
    private volatile long encodedSamples;
    private volatile long firstEncodedPtsUs = -1;
    private volatile long lastEncodedPtsUs = -1;

    public AudioSource(Context context, long t0Ns, RecordingMuxer muxer,
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
        AudioSilenceDetector silence = new AudioSilenceDetector(3L * RATE * 2);
        boolean silenceWarning = false;
        long nextDiagnosticsNs = 0;
        String previousDiagnostics = null;

        try {
            if (wavFile != null) {
                wav = new RandomAccessFile(wavFile, "rw");
                writeWavHeader(wav);
            }

            while (running) {
                pcm.clear();
                int n = record.read(pcm, readSize, AudioRecord.READ_BLOCKING);
                if (n < 0) {
                    throw new IllegalStateException("AudioRecord.read failed: " + n);
                }
                if (n == 0) {
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
                if (wav != null) {
                    wav.write(raw);
                }

                long nowNs = SystemClock.elapsedRealtimeNanos();
                if (nowNs >= nextDiagnosticsNs) {
                    String diagnostics = recordingDiagnostics();
                    if (!diagnostics.equals(previousDiagnostics)) {
                        Log.i(TAG, "audio recording state: " + diagnostics);
                        writeDiagnostics("recording state: " + diagnostics);
                        previousDiagnostics = diagnostics;
                    }
                    nextDiagnosticsNs = nowNs + 5_000_000_000L;
                }

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

                    byte[] encodedInput = mono.array();
                    int peak = 0;
                    for (int i = 0; i < encodedInput.length; i += 2) {
                        int sample = (short) ((encodedInput[i] & 0xff) | (encodedInput[i + 1] << 8));
                        peak = Math.max(peak, Math.abs(sample));
                    }
                    boolean currentWarning = silence.update(encodedInput);
                    listener.onAudioLevel(peak, silence.isExactZero());
                    if (currentWarning != silenceWarning) {
                        silenceWarning = currentWarning;
                        writeDiagnostics("encoded-input low-level warning=" + currentWarning + " "
                                + recordingDiagnostics());
                        listener.onSilenceChanged(currentWarning);
                    }
                    long ptsUs = (audioStartNs - t0Ns) / 1000
                            + framesWritten * 1_000_000L / RATE;
                    queueEncoder(encodedInput, ptsUs);
                    framesWritten += frames;
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
            } catch (Throwable t) {
                if (!failed) {
                    error = "AudioRecord stop failed: " + t.getClass().getSimpleName()
                            + ": " + String.valueOf(t.getMessage());
                    failed = true;
                    listener.onError(error);
                }
            }
            try {
                if (encoder != null) {
                    long eosPtsUs = (audioStartNs - t0Ns) / 1000
                            + framesWritten * 1_000_000L / RATE;
                    queueEos(eosPtsUs, info);
                    drainEncoder(info, true);
                }
            } catch (Throwable t) {
                if (!failed) {
                    error = "AAC finalization failed: " + t.getClass().getSimpleName()
                            + ": " + String.valueOf(t.getMessage());
                    failed = true;
                    listener.onError(error);
                }
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
                }
            } catch (Throwable t) {
                if (!failed) {
                    error = "WAV finalization failed: " + t.getClass().getSimpleName()
                            + ": " + String.valueOf(t.getMessage());
                    failed = true;
                    listener.onError(error);
                }
            }
            try {
                if (wav != null) {
                    wav.close();
                }
            } catch (Throwable t) {
                if (!failed) {
                    error = "WAV close failed: " + t.getClass().getSimpleName()
                            + ": " + String.valueOf(t.getMessage());
                    failed = true;
                    listener.onError(error);
                }
            }
            record = null;
            encoder = null;
        }
    }

    private String recordingDiagnostics() {
        try {
            AudioDeviceInfo routed = record.getRoutedDevice();
            AudioRecordingConfiguration config = record.getActiveRecordingConfiguration();
            String route = routed == null ? "none"
                    : routed.getType() + "/" + routed.getId() + "/" + routed.getProductName();
            String silenced = config == null ? "unknown"
                    : Boolean.toString(config.isClientSilenced());
            return "route=" + route + " systemSilenced=" + silenced;
        } catch (RuntimeException e) {
            return "unavailable=" + e.getClass().getSimpleName();
        }
    }

    private void writeDiagnostics(String message) {
        if (wavFile == null) {
            return;
        }
        File logFile = new File(wavFile.getAbsolutePath() + ".audio.log");
        try (FileWriter out = new FileWriter(logFile, true)) {
            out.write("elapsedRealtimeMs=" + SystemClock.elapsedRealtime() + " " + message + "\n");
        } catch (IOException e) {
            Log.w(TAG, "could not write audio diagnostics", e);
        }
    }

    private void queueEncoder(byte[] data, long ptsUs) throws Exception {
        int index = -1;
        for (int attempt = 0; attempt < 100; attempt++) {
            index = encoder.dequeueInputBuffer(10000);
            if (index >= 0) {
                break;
            }
            if (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
                throw new IllegalStateException("AAC input dequeue failed: " + index);
            }
            drainEncoder(new MediaCodec.BufferInfo(), false);
        }
        if (index < 0) {
            throw new IllegalStateException("AAC input unavailable after retries");
        }
        ByteBuffer input = encoder.getInputBuffer(index);
        if (input == null) {
            throw new IllegalStateException("AAC input buffer is null");
        }
        input.clear();
        if (data.length > input.remaining()) {
            throw new IllegalStateException("AAC input buffer too small: "
                    + input.remaining() + " < " + data.length);
        }
        input.put(data);
        encoder.queueInputBuffer(index, 0, data.length, ptsUs, 0);
    }

    private void queueEos(long ptsUs, MediaCodec.BufferInfo info) {
        for (int attempt = 0; attempt < 100; attempt++) {
            int index = encoder.dequeueInputBuffer(10000);
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                return;
            }
            if (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
                throw new IllegalStateException("AAC EOS input dequeue failed: " + index);
            }
            drainEncoder(info, false);
        }
        throw new IllegalStateException("AAC EOS input unavailable after retries");
    }

    private void drainEncoder(MediaCodec.BufferInfo info, boolean untilEos) {
        int emptyPolls = 0;
        while (true) {
            int index = encoder.dequeueOutputBuffer(info, untilEos ? 10000 : 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                emptyPolls = 0;
                muxer.addAudioTrack(encoder.getOutputFormat());
            } else if (index >= 0) {
                emptyPolls = 0;
                try {
                    ByteBuffer output = encoder.getOutputBuffer(index);
                    if (output == null && info.size > 0) {
                        throw new IllegalStateException("AAC output buffer is null");
                    }
                    // MediaCodec may emit csd-0 as a 2-byte codec-config buffer.
                    // Writing that as an AAC sample corrupts the first MP4 packet.
                    if (output != null && info.size > 2
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        byte[] data = new byte[info.size];
                        output.position(info.offset);
                        output.get(data);
                        muxer.writeAudio(data, info.presentationTimeUs);
                        if (firstEncodedPtsUs < 0) {
                            firstEncodedPtsUs = info.presentationTimeUs;
                        }
                        lastEncodedPtsUs = info.presentationTimeUs;
                        encodedBytes += data.length;
                        encodedSamples++;
                    }
                } finally {
                    encoder.releaseOutputBuffer(index, false);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return;
                }
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) {
                    return;
                }
                if (++emptyPolls >= 100) {
                    throw new IllegalStateException("AAC output EOS unavailable after retries");
                }
            } else if (index != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                throw new IllegalStateException("AAC output dequeue failed: " + index);
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
            if (t.isAlive() && !failed) {
                error = "Audio receiver did not stop within 3 seconds";
                failed = true;
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

    public long getEncodedBytes() {
        return encodedBytes;
    }

    public long getEncodedSamples() {
        return encodedSamples;
    }

    public long getFirstEncodedPtsUs() {
        return firstEncodedPtsUs;
    }

    public long getLastEncodedPtsUs() {
        return lastEncodedPtsUs;
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
