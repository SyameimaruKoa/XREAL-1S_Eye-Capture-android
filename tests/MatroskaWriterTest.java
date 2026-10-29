package jp.xreal.recorder;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Creates an MJPEG Matroska file from a real JPEG for inspection by ffprobe. */
public final class MatroskaWriterTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected JPEG, AAC ADTS input, and Matroska output paths");
        }
        byte[] jpeg = Files.readAllBytes(Paths.get(args[0]));
        byte[] aac = Files.readAllBytes(Paths.get(args[1]));
        MatroskaWriter writer = new MatroskaWriter(new FileOutputStream(args[2]), 1920, 1080, 30,
                new byte[]{0x11, (byte) 0x88});
        int offset = 0;
        int audioIndex = 0;
        int videoIndex = 0;
        while (offset + 7 <= aac.length) {
            int frameLength = ((aac[offset + 3] & 3) << 11)
                    | ((aac[offset + 4] & 255) << 3) | ((aac[offset + 5] & 0xe0) >> 5);
            if (frameLength < 7 || offset + frameLength > aac.length) {
                throw new IllegalArgumentException("Invalid ADTS frame");
            }
            long audioPts = audioIndex * 1024L * 1_000_000 / 48000;
            while (videoIndex < 30 && videoIndex * 1_000_000L / 30 <= audioPts) {
                writer.writeSample(1, jpeg, videoIndex * 1_000_000L / 30, true);
                videoIndex++;
            }
            byte[] packet = java.util.Arrays.copyOfRange(aac, offset + 7, offset + frameLength);
            writer.writeSample(2, packet, audioPts, true);
            offset += frameLength;
            audioIndex++;
        }
        writer.finish();
    }
}
