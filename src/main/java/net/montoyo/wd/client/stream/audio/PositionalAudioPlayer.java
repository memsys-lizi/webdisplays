package net.montoyo.wd.client.stream.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.ArrayDeque;

/**
 * Plays a live mono 48 kHz PCM stream from a point in the world through Minecraft's OpenAL context, attenuated
 * like Minecraft's own sounds (the listener gain already carries the master volume; the source gain follows the
 * "Jukebox/Note Blocks" slider). All AL calls happen on the player's own pump thread.
 */
public final class PositionalAudioPlayer implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/Audio");

    public static final int SAMPLE_RATE = 48000;
    private static final int CHUNK = 960;               // 20 ms per AL buffer
    private static final int BUFFERS = 16;
    private static final float MAX_DISTANCE = 32.0f;

    private final Object lock = new Object();
    private final ArrayDeque<short[]> pending = new ArrayDeque<>();
    private short[] partial = new short[CHUNK];
    private int partialLen, backlog;

    private volatile Vec3 position;
    private volatile boolean running = true;
    private final Thread pump;

    // stats
    private volatile int queuedChunks;
    private volatile long underruns, dropped;

    private final int startQueued;  // start (or restart) playback once this many chunks are queued
    private final int maxQueued;    // keep at most this many chunks inside OpenAL
    private final int maxBacklog;   // samples; older audio is dropped to bound the latency

    /** Low-latency player for local audio (60 ms start buffer). */
    public PositionalAudioPlayer(Vec3 position) {
        this(position, 60);
    }

    /** @param startBufferMs audio buffered before playback starts; more absorbs more network jitter */
    public PositionalAudioPlayer(Vec3 position, int startBufferMs) {
        this.position = position;
        this.startQueued = Math.max(2, startBufferMs * SAMPLE_RATE / 1000 / CHUNK);
        this.maxQueued = startQueued + 2;
        this.maxBacklog = (startBufferMs + 250) * SAMPLE_RATE / 1000;
        pump = new Thread(this::pumpLoop, "WD audio player");
        pump.setDaemon(true);
        pump.start();
    }

    public void setPosition(Vec3 position) {
        this.position = position;
    }

    /** Queues mono samples in [-1, 1]. Any thread. */
    public void feed(float[] samples, int count) {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                float s = Math.max(-1f, Math.min(1f, samples[i]));
                partial[partialLen++] = (short) (s * 32767f);
                if (partialLen == CHUNK) {
                    pending.add(partial);
                    backlog += CHUNK;
                    partial = new short[CHUNK];
                    partialLen = 0;
                }
            }
            while (backlog > maxBacklog) { // producer ahead of playback: drop the oldest audio to keep latency low
                pending.poll();
                backlog -= CHUNK;
                dropped++;
            }
        }
    }

    /** Audio waiting to be heard, in ms. */
    public int getBufferedMs() {
        int pend;
        synchronized (lock) {
            pend = backlog + partialLen;
        }
        return (queuedChunks * CHUNK + pend) * 1000 / SAMPLE_RATE;
    }

    public long getUnderruns() {
        return underruns;
    }

    public long getDropped() {
        return dropped;
    }

    /** One-line status for the stream HUD. */
    public String describe() {
        return String.format("buffer %d ms  restarts %d  dropped %d", getBufferedMs(), underruns, dropped);
    }

    private void pumpLoop() {
        int source = 0;
        int[] buffers = new int[BUFFERS];
        ArrayDeque<Integer> free = new ArrayDeque<>();
        ShortBuffer upload = MemoryUtil.memAllocShort(CHUNK);
        boolean started = false; // played at least once since the source was created
        long lastParams = 0;

        try {
            while (running) {
                if (ALC10.alcGetCurrentContext() == 0) { // sound engine not ready / reloading
                    source = 0;
                    Thread.sleep(100);
                    continue;
                }
                if (source == 0 || !AL10.alIsSource(source)) {
                    source = AL10.alGenSources();
                    AL10.alGenBuffers(buffers);
                    free.clear();
                    for (int b : buffers) free.add(b);
                    AL10.alSourcei(source, 0xD000 /* AL_DISTANCE_MODEL */, 0xD003 /* AL_LINEAR_DISTANCE */);
                    AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, MAX_DISTANCE);
                    AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0f);
                    AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 0.0f);
                    AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                    started = false;
                    lastParams = 0;
                    if (AL10.alGetError() != AL10.AL_NO_ERROR) LOG.warn("OpenAL error while creating the screen audio source");
                }

                long now = System.currentTimeMillis();
                if (now - lastParams > 100) {
                    Vec3 p = position;
                    AL10.alSource3f(source, AL10.AL_POSITION, (float) p.x, (float) p.y, (float) p.z);
                    AL10.alSourcef(source, AL10.AL_GAIN, Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.RECORDS));
                    lastParams = now;
                }

                int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
                for (int i = 0; i < processed; i++) free.add(AL10.alSourceUnqueueBuffers(source));

                int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
                while (queued < maxQueued && !free.isEmpty()) {
                    short[] chunk;
                    synchronized (lock) {
                        chunk = pending.poll();
                        if (chunk != null) backlog -= CHUNK;
                    }
                    if (chunk == null) break;
                    int buf = free.poll();
                    upload.clear();
                    upload.put(chunk).flip();
                    AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, upload, SAMPLE_RATE);
                    AL10.alSourceQueueBuffers(source, buf);
                    queued++;
                }
                queuedChunks = queued;

                boolean playing = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
                if (!playing && queued >= startQueued) {
                    if (started) underruns++; // ran dry (or the page paused its audio) and is resuming
                    AL10.alSourcePlay(source);
                    started = true;
                }

                Thread.sleep(5);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            LOG.error("Screen audio player failed", t);
        } finally {
            if (source != 0 && ALC10.alcGetCurrentContext() != 0 && AL10.alIsSource(source)) {
                AL10.alSourceStop(source);
                AL10.alSourcei(source, AL10.AL_BUFFER, 0);
                AL10.alDeleteSources(source);
                AL10.alDeleteBuffers(buffers);
                AL10.alGetError();
            }
            MemoryUtil.memFree(upload);
        }
    }

    @Override
    public void close() {
        running = false;
        pump.interrupt();
        try {
            pump.join(1000);
        } catch (InterruptedException ignored) {
        }
    }
}
