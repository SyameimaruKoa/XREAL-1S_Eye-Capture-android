package jp.xreal.recorder;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

public final class TcpReadProgressTest {
    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", server.getLocalPort());
             Socket peer = server.accept()) {
            client.setSoTimeout(40);
            Thread sender = new Thread(() -> {
                try {
                    peer.getOutputStream().write(new byte[]{1, 2});
                    Thread.sleep(80);
                    peer.getOutputStream().write(new byte[]{3, 4});
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            sender.start();
            byte[] received = TcpReadProgress.read(client.getInputStream(), 4, 200, () -> true);
            if (!Arrays.equals(received, new byte[]{1, 2, 3, 4})) {
                throw new AssertionError("Partial reads were not assembled");
            }
            sender.join();
            long start = System.nanoTime();
            try {
                TcpReadProgress.read(client.getInputStream(), 1, 160, () -> true);
                throw new AssertionError("Stalled video did not fail");
            } catch (IOException expected) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                if (!expected.getMessage().contains("no data") || elapsedMs < 160 || elapsedMs > 1500) {
                    throw new AssertionError("Incorrect timeout: " + elapsedMs, expected);
                }
            }
        }
        System.out.println("TcpReadProgressTest passed");
    }
}
