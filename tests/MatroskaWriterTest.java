package jp.xreal.recorder;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Creates an MJPEG Matroska file from a real JPEG for inspection by ffprobe. */
public final class MatroskaWriterTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected JPEG input and Matroska output paths");
        }
        byte[] jpeg = Files.readAllBytes(Paths.get(args[0]));
        MatroskaWriter writer = new MatroskaWriter(new FileOutputStream(args[1]), 1920, 1080, 30, null);
        for (int i = 0; i < 30; i++) {
            writer.writeSample(1, jpeg, i * 1_000_000L / 30, true);
        }
        writer.finish();
    }
}
