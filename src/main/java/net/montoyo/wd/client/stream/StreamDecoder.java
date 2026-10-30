package net.montoyo.wd.client.stream;

import net.montoyo.wd.client.stream.audio.PositionalAudioPlayer;
import net.montoyo.wd.client.stream.codec.EncodedPacket;
import net.montoyo.wd.client.stream.codec.H264Decoder;
import net.montoyo.wd.client.stream.codec.OpusDecoder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Sink side of a screen stream: decodes H.264 / Opus packets into a texture for the screen and audio for a
 * positional player. Packets can be pushed from any thread.
 */
public final class StreamDecoder {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/Stream");

    private record VideoIn(EncodedPacket packet, long captureNanos) {
    }

    final StreamStats stats;
    private final Supplier<PositionalAudioPlayer> audioOut;
    /** Report the decoded size in the stats (viewers; the encoder side reports it otherwise). */
    private final boolean trackResolution;
    private int shownW, shownH;

    private final LinkedBlockingQueue<VideoIn> videoQueue = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<EncodedPacket> audioQueue = new LinkedBlockingQueue<>();
    private final FrameMailbox mailbox = new FrameMailbox();
    private final StreamTexture texture = new StreamTexture();

    private volatile boolean running = true;
    private final Thread videoThread, audioThread;

    /**
     * @param audioOut where decoded audio goes; may return null while there is nowhere to play it
     */
    StreamDecoder(StreamStats stats, Supplier<PositionalAudioPlayer> audioOut, boolean trackResolution) {
        this.stats = stats;
        this.audioOut = audioOut;
        this.trackResolution = trackResolution;
        videoThread = new Thread(this::videoLoop, "WD stream decoder");
        audioThread = new Thread(this::audioLoop, "WD stream audio");
        videoThread.setDaemon(true);
        audioThread.setDaemon(true);
        videoThread.start();
        audioThread.start();
    }

    /** @param captureNanos local capture time if known (loopback), 0 otherwise */
    public void video(EncodedPacket packet, long captureNanos) {
        videoQueue.offer(new VideoIn(packet, captureNanos));
    }

    public void audio(EncodedPacket packet) {
        audioQueue.offer(packet);
    }

    private void videoLoop() {
        try (H264Decoder decoder = new H264Decoder()) {
            while (running) {
                VideoIn in = videoQueue.poll(100, TimeUnit.MILLISECONDS);
                if (in == null) continue;
                mailbox.pendingCaptureNanos = in.captureNanos();
                long t0 = System.nanoTime();
                int frames = decoder.decode(in.packet(), mailbox);
                stats.decodeNanos.addAndGet(System.nanoTime() - t0);
                stats.decodedFrames.addAndGet(frames);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            LOG.error("Stream decoder failed", t);
            stats.error = t.getMessage();
        }
    }

    private void audioLoop() {
        try (OpusDecoder decoder = new OpusDecoder()) {
            while (running) {
                EncodedPacket p = audioQueue.poll(100, TimeUnit.MILLISECONDS);
                if (p == null) continue;
                PositionalAudioPlayer player = audioOut.get();
                if (player == null) continue;
                decoder.decode(p, player::feed);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            LOG.error("Audio decoder failed", t);
            stats.error = "audio: " + t.getMessage();
        }
    }

    /** Render thread: uploads the newest decoded frame and returns the texture to draw (0 = nothing yet). */
    int textureId() {
        FrameMailbox.Frame f = mailbox.take();
        if (f != null) {
            texture.upload(f.bgra(), f.width(), f.height());
            if (trackResolution && (f.width() != shownW || f.height() != shownH)) {
                shownW = f.width();
                shownH = f.height();
                stats.resolution = shownW + "x" + shownH;
            }
            if (f.captureNanos() != 0) stats.latencyNanos.addAndGet(System.nanoTime() - f.captureNanos());
            stats.displayed.incrementAndGet();
            mailbox.release(f);
        }
        return texture.getId();
    }

    /** Render thread. */
    void close() {
        running = false;
        try {
            videoThread.join(2000);
            audioThread.join(2000);
        } catch (InterruptedException ignored) {
        }
        texture.delete();
    }
}
