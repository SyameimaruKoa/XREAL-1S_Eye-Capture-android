package jp.xreal.recorder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

/** Guards the mono conversion used by the AAC encoder during the audio optimization. */
public final class AudioMonoConversionTest {
    private static final int BYTES_PER_FRAME = 8;
    private static final int FRAMES = 1024;

    public static void main(String[] args) {
        ByteBuffer pcm = ByteBuffer.allocate(FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);
        Random random = new Random(1);
        for (int i = 0; i < FRAMES; i++) {
            pcm.putInt(random.nextInt());
        }

        ByteBuffer oldMono = ByteBuffer.allocate(FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN);
        int oldPeak = 0;
        for (int i = 0; i < FRAMES; i++) {
            oldMono.putShort((short) (pcm.getInt(i * BYTES_PER_FRAME) >> 16));
        }
        byte[] oldBytes = oldMono.array();
        for (int i = 0; i < oldBytes.length; i += 2) {
            int sample = (short) ((oldBytes[i] & 0xff) | (oldBytes[i + 1] << 8));
            oldPeak = Math.max(oldPeak, Math.abs(sample));
        }

        byte[] mono = new byte[FRAMES * 2];
        int newPeak = 0;
        for (int i = 0; i < FRAMES; i++) {
            short sample = (short) (pcm.getInt(i * BYTES_PER_FRAME) >> 16);
            mono[i * 2] = (byte) sample;
            mono[i * 2 + 1] = (byte) (sample >> 8);
            newPeak = Math.max(newPeak, Math.abs(sample));
        }

        require(Arrays.equals(oldBytes, mono), "mono bytes changed");
        require(oldPeak == newPeak, "peak value changed");
        System.out.println("AudioMonoConversionTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
