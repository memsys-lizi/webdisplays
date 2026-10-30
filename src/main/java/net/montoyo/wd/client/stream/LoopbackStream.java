package net.montoyo.wd.client.stream;

import net.montoyo.wd.client.stream.codec.EncodedPacket;
import net.montoyo.wd.client.stream.codec.H264Decoder;
import net.montoyo.wd.client.stream.codec.H264Encoder;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.browser.WDClientBrowser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Prototype step 1: captures a screen's browser frames, encodes them, decodes them again and shows the decoded
 * picture on the same screen. The queue between the encoder and decoder threads stands in for the network.
 */
public final class LoopbackStream {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/Stream");

    public record Settings(int fps, int bitrateKbps, int maxWidth, int maxHeight) {
    }

    private record InFlight(EncodedPacket packet, long captureNanos) {
    }

    final ScreenData screen;
    final WDClientBrowser browser;
    final Settings settings;
    final StreamStats stats = new StreamStats();

    private final long startNanos = System.nanoTime();
    private final long frameIntervalNanos;
    private long lastCaptureNanos;

    // capture -> encoder hand-off (latest frame wins)
    private final Object captureLock = new Object();
    private ByteBuffer captureBuf, encodeBuf;
    private int capW, capH;
    private long capNanos;
    private boolean capReady;

    private final LinkedBlockingQueue<InFlight> wire = new LinkedBlockingQueue<>();
    private final FrameMailbox mailbox = new FrameMailbox();
    private final StreamTexture texture = new StreamTexture();

    private volatile boolean running = true;
    private final Thread encodeThread, decodeThread;

    LoopbackStream(ScreenData screen, WDClientBrowser browser, Settings settings) {
        this.screen = screen;
        this.browser = browser;
        this.settings = settings;
        this.frameIntervalNanos = 1_000_000_000L / settings.fps();

        encodeThread = new Thread(this::encodeLoop, "WD stream encoder");
        decodeThread = new Thread(this::decodeLoop, "WD stream decoder");
        encodeThread.setDaemon(true);
        decodeThread.setDaemon(true);
        encodeThread.start();
        decodeThread.start();

        browser.setFrameListener(this::onPaint);
        browser.requestFullRepaint();
    }

    /** CEF paint callback (render thread): copy the frame for the encoder, throttled to the target fps. */
    private void onPaint(ByteBuffer bgra, int width, int height) {
        long now = System.nanoTime();
        // allow some jitter so a 30 fps page is not throttled down to 15 fps
        if (now - lastCaptureNanos < frameIntervalNanos * 3 / 4) return;
        lastCaptureNanos = now;

        int size = width * height * 4;
        synchronized (captureLock) {
            if (captureBuf == null || captureBuf.capacity() < size) captureBuf = ByteBuffer.allocateDirect(size);
            ByteBuffer src = bgra.duplicate();
            src.position(0).limit(size);
            captureBuf.clear();
            captureBuf.put(src);
            captureBuf.flip();
            capW = width;
            capH = height;
            capNanos = now;
            capReady = true;
            captureLock.notifyAll();
        }
        stats.captured.incrementAndGet();
    }

    private void encodeLoop() {
        H264Encoder encoder = null;
        try {
            while (running) {
                int w, h;
                long captured;
                synchronized (captureLock) {
                    while (running && !capReady) captureLock.wait(100);
                    if (!running) break;
                    ByteBuffer tmp = encodeBuf;
                    encodeBuf = captureBuf;
                    captureBuf = tmp;
                    capReady = false;
                    w = capW;
                    h = capH;
                    captured = capNanos;
                }

                int[] target = fit(w, h);
                if (encoder == null || encoder.getWidth() != (target[0] & ~1) || encoder.getHeight() != (target[1] & ~1)) {
                    if (encoder != null) encoder.close();
                    encoder = H264Encoder.openBest(target[0], target[1], settings.fps(), settings.bitrateKbps());
                    stats.encoderName = encoder.getCandidate().label();
                    stats.resolution = w + "x" + h + " -> " + encoder.getWidth() + "x" + encoder.getHeight();
                    LOG.info("Streaming screen with {} at {}", stats.encoderName, stats.resolution);
                }

                long t0 = System.nanoTime();
                List<EncodedPacket> packets = encoder.encode(encodeBuf, w, h, (captured - startNanos) / 1_000_000L);
                stats.encodeNanos.addAndGet(System.nanoTime() - t0);
                stats.encoded.incrementAndGet();

                for (EncodedPacket p : packets) {
                    stats.bytes.addAndGet(p.data().length);
                    if (p.keyFrame()) stats.keyFrames.incrementAndGet();
                    wire.offer(new InFlight(p, captured));
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            LOG.error("Stream encoder failed", t);
            stats.error = t.getMessage();
        } finally {
            if (encoder != null) encoder.close();
        }
    }

    private void decodeLoop() {
        try (H264Decoder decoder = new H264Decoder()) {
            while (running) {
                InFlight f = wire.poll(100, TimeUnit.MILLISECONDS);
                if (f == null) continue;
                mailbox.pendingCaptureNanos = f.captureNanos();
                long t0 = System.nanoTime();
                int frames = decoder.decode(f.packet(), mailbox);
                stats.decodeNanos.addAndGet(System.nanoTime() - t0);
                stats.decodedFrames.addAndGet(frames);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            LOG.error("Stream decoder failed", t);
            stats.error = t.getMessage();
        }
    }

    /** Scales the browser size down to fit the configured maximum, keeping the aspect ratio. */
    private int[] fit(int w, int h) {
        double scale = Math.min(1.0, Math.min(settings.maxWidth() / (double) w, settings.maxHeight() / (double) h));
        return new int[]{Math.max(2, (int) (w * scale)), Math.max(2, (int) (h * scale))};
    }

    /** Render thread: uploads the newest decoded frame and returns the texture to draw (0 = nothing yet). */
    int textureId() {
        FrameMailbox.Frame f = mailbox.take();
        if (f != null) {
            texture.upload(f.bgra(), f.width(), f.height());
            stats.latencyNanos.addAndGet(System.nanoTime() - f.captureNanos());
            stats.displayed.incrementAndGet();
            mailbox.release(f);
        }
        return texture.getId();
    }

    /** Render thread. */
    void close() {
        running = false;
        browser.setFrameListener(null);
        synchronized (captureLock) {
            captureLock.notifyAll();
        }
        try {
            encodeThread.join(2000);
            decodeThread.join(2000);
        } catch (InterruptedException ignored) {
        }
        texture.delete();
    }
}
