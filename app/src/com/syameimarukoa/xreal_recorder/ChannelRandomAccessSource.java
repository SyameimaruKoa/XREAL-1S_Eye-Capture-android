package com.syameimarukoa.xreal_recorder;

import org.mp4parser.muxer.RandomAccessSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Random access view of a FileChannel for mp4parser sample reads. */
final class ChannelRandomAccessSource implements RandomAccessSource {
    private final FileChannel channel;

    ChannelRandomAccessSource(FileChannel channel) {
        this.channel = channel;
    }

    @Override
    public ByteBuffer get(long offset, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        long position = offset;
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position);
            if (count < 0) {
                break;
            }
            position += count;
        }
        buffer.flip();
        return buffer;
    }

    @Override
    public void close() throws IOException {
    }
}
