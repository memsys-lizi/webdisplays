package net.montoyo.wd.client.stream;

import net.montoyo.wd.client.stream.codec.H264Decoder;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Hands decoded frames from the decoder thread to the render thread, keeping only the newest one.
 * Buffers are recycled so steady-state decoding allocates nothing.
 */
final class FrameMailbox implements H264Decoder.FrameSink {
    record Frame(ByteBuffer bgra, int width, int height, long ptsMs, long captureNanos) {
    }

    private final ArrayDeque<ByteBuffer> free = new ArrayDeque<>();
    private Frame latest;
    /** capture time of the frame currently being decoded, set by the decoder thread */
    volatile long pendingCaptureNanos;

    @Override
    public synchronized ByteBuffer acquire(int width, int height) {
        int size = width * height * 4;
        ByteBuffer buf;
        while ((buf = free.poll()) != null)
            if (buf.capacity() >= size) return buf.clear();
        return ByteBuffer.allocateDirect(size);
    }

    @Override
    public synchronized void publish(ByteBuffer bgra, int width, int height, long ptsMs) {
        if (latest != null) free.push(latest.bgra()); // never shown, recycle
        latest = new Frame(bgra, width, height, ptsMs, pendingCaptureNanos);
    }

    /** @return the newest frame, or null; give it back with {@link #release} after uploading */
    synchronized Frame take() {
        Frame f = latest;
        latest = null;
        return f;
    }

    synchronized void release(Frame frame) {
        free.push(frame.bgra());
    }
}
