package net.montoyo.wd.client.stream;

import net.montoyo.wd.client.stream.codec.EncodedPacket;
import net.montoyo.wd.client.stream.codec.H264Encoder;
import net.montoyo.wd.client.stream.codec.OpusEncoder;
import net.montoyo.wd.utilities.browser.WDClientBrowser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Source side of a screen stream: captures the browser's frames and audio and turns them into H.264 / Opus packets
 * for a {@link Sink} (the local decoder in loopback mode, the network when hosting).
 */
public final class StreamEncoder {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/Stream");

    public record Settings(int fps, int bitrateKbps, int maxWidth, int maxHeight, int audioBitrateKbps) {
        public static Settings of(int fps, int bitrateKbps, int maxHeight) {
            return new Settings(fps, bitrateKbps, maxHeight * 16 / 9, maxHeight, 64);
        }
    }

    /** Receives encoded packets; called from the encoder thread (video) and CEF's audio thread (audio). */
    public interface Sink {
        void video(EncodedPacket packet, long captureNanos);

        void audio(EncodedPacket packet);
    }

    final WDClientBrowser browser;
    final Settings settings;
    final StreamStats stats;
    private final Sink sink;
    /** Whether the captured audio should also be played locally as-is (true when hosting). */
    final boolean monitorAudio;

    private final long startNanos = System.nanoTime();
    private final long frameIntervalNanos;
    private long lastCaptureNanos;

    private final Object captureLock = new Object();
    private ByteBuffer captureBuf, encodeBuf;
    private int capW, capH;
    private long capNanos;
    private boolean capReady;
    private volatile boolean keyFrameRequested;

    private final Object audioLock = new Object();
    private OpusEncoder opusEncoder;
    private float[] opusFrame;
    private int opusFill;

    private volatile boolean running = true;
    private final Thread encodeThread;

    StreamEncoder(WDClientBrowser browser, Settings settings, StreamStats stats, Sink sink, boolean monitorAudio) {
        this.browser = browser;
        this.settings = settings;
        this.stats = stats;
        this.sink = sink;
        this.monitorAudio = monitorAudio;
        this.frameIntervalNanos = 1_000_000_000L / settings.fps();

        encodeThread = new Thread(this::encodeLoop, "WD stream encoder");
        encodeThread.setDaemon(true);
        encodeThread.start();

        browser.setFrameListener(this::onPaint);
        browser.requestFullRepaint();
    }

    public boolean monitorsAudio() {
        return monitorAudio;
    }

    /** The next video frame will be a key frame (a viewer joined or lost data). */
    public void requestKeyFrame() {
        keyFrameRequested = true;
        browser.requestFullRepaint(); // make sure a frame comes even if the page is static
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
                if (keyFrameRequested) {
                    keyFrameRequested = false;
                    encoder.requestKeyFrame();
                }

                long t0 = System.nanoTime();
                List<EncodedPacket> packets = encoder.encode(encodeBuf, w, h, (captured - startNanos) / 1_000_000L);
                stats.encodeNanos.addAndGet(System.nanoTime() - t0);
                stats.encoded.incrementAndGet();

                for (EncodedPacket p : packets) {
                    stats.bytes.addAndGet(p.data().length);
                    if (p.keyFrame()) stats.keyFrames.incrementAndGet();
                    sink.video(p, captured);
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

    /** CEF audio thread: mono 48 kHz samples captured from the browser. */
    public void audioIn(float[] samples, int count) {
        synchronized (audioLock) {
            if (!running) return;
            try {
                if (opusEncoder == null) {
                    opusEncoder = new OpusEncoder(settings.audioBitrateKbps() * 1000);
                    opusFrame = new float[opusEncoder.getFrameSize()];
                }
                int i = 0;
                while (i < count) {
                    int n = Math.min(count - i, opusFrame.length - opusFill);
                    System.arraycopy(samples, i, opusFrame, opusFill, n);
                    opusFill += n;
                    i += n;
                    if (opusFill == opusFrame.length) {
                        opusFill = 0;
                        for (EncodedPacket p : opusEncoder.encode(opusFrame, 0)) {
                            stats.audioBytes.addAndGet(p.data().length);
                            sink.audio(p);
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.error("Audio encoder failed", t);
                stats.error = "audio: " + t.getMessage();
            }
        }
    }

    /** Scales the browser size down to fit the configured maximum, keeping the aspect ratio. */
    private int[] fit(int w, int h) {
        double scale = Math.min(1.0, Math.min(settings.maxWidth() / (double) w, settings.maxHeight() / (double) h));
        return new int[]{Math.max(2, (int) (w * scale)), Math.max(2, (int) (h * scale))};
    }

    void close() {
        running = false;
        browser.setFrameListener(null);
        synchronized (captureLock) {
            captureLock.notifyAll();
        }
        try {
            encodeThread.join(2000);
        } catch (InterruptedException ignored) {
        }
        synchronized (audioLock) {
            if (opusEncoder != null) {
                opusEncoder.close();
                opusEncoder = null;
            }
        }
    }
}
