package net.montoyo.wd.client.audio;

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
 * Plays a live mono 48 kHz PCM stream from a point in the world through Minecraft's OpenAL context, with linear
 * distance attenuation.
 * <p>
 * The volume is independent of Minecraft's sound sliders: Minecraft puts its master volume on the OpenAL listener,
 * so the source gain divides it back out. All AL calls happen on the player's own pump thread.
 */
public final class PositionalAudioPlayer implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/Audio");

    public static final int SAMPLE_RATE = 48000;
    private static final int CHUNK = 960;               // 20 ms per AL buffer
    private static final int BUFFERS = 16;
    private static final float MAX_GAIN = 16.0f;        // enough to undo a master volume down to ~6 %
    private static final int START_QUEUED = 3;          // 60 ms buffered before (re)starting playback
    private static final int MAX_QUEUED = START_QUEUED + 2;
    private static final int MAX_BACKLOG = 310 * SAMPLE_RATE / 1000; // older audio is dropped to bound the latency

    private final Object lock = new Object();
    private final ArrayDeque<short[]> pending = new ArrayDeque<>();
    private short[] partial = new short[CHUNK];
    private int partialLen, backlog;

    private volatile Vec3 position;
    private volatile float volume = 1.0f;
    private volatile float maxDistance = 32.0f;
    private volatile boolean running = true;
    private final Thread pump;

    public PositionalAudioPlayer(Vec3 position) {
        this.position = position;
        pump = new Thread(this::pumpLoop, "WD audio player");
        pump.setDaemon(true);
        pump.start();
    }

    public void setPosition(Vec3 position) {
        this.position = position;
    }

    public Vec3 getPosition() {
        return position;
    }

    /** Linear volume, 1 = as loud as the page itself. */
    public void setVolume(float volume) {
        this.volume = volume;
    }

    public float getVolume() {
        return volume;
    }

    /** Distance (in blocks) at which the sound fades out completely. */
    public void setMaxDistance(float maxDistance) {
        this.maxDistance = maxDistance;
    }

    public float getMaxDistance() {
        return maxDistance;
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
            while (backlog > MAX_BACKLOG) { // producer ahead of playback: drop the oldest audio to keep latency low
                pending.poll();
                backlog -= CHUNK;
            }
        }
    }

    private float sourceGain() {
        float master = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER);
        if (master <= 0.0f) return 0.0f; // the listener gain is 0, nothing can be heard anyway
        return Math.min(MAX_GAIN, volume / master);
    }

    private void applyParams(int source) {
        Vec3 p = position;
        float max = Math.max(1.0f, maxDistance);
        AL10.alSource3f(source, AL10.AL_POSITION, (float) p.x, (float) p.y, (float) p.z);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, max);
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, Math.min(2.0f, max / 4.0f)); // full volume up close
        AL10.alSourcef(source, AL10.AL_GAIN, sourceGain());
    }

    private void pumpLoop() {
        int source = 0;
        int[] buffers = new int[BUFFERS];
        ArrayDeque<Integer> free = new ArrayDeque<>();
        ShortBuffer upload = MemoryUtil.memAllocShort(CHUNK);
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
                    AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0f);
                    AL10.alSourcef(source, AL10.AL_MAX_GAIN, MAX_GAIN);
                    AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                    lastParams = 0;
                    if (AL10.alGetError() != AL10.AL_NO_ERROR) LOG.warn("OpenAL error while creating a WebDisplays audio source");
                }

                long now = System.currentTimeMillis();
                if (now - lastParams > 50) {
                    applyParams(source);
                    lastParams = now;
                }

                int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
                for (int i = 0; i < processed; i++) free.add(AL10.alSourceUnqueueBuffers(source));

                int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
                while (queued < MAX_QUEUED && !free.isEmpty()) {
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

                boolean playing = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
                if (!playing && queued >= START_QUEUED) AL10.alSourcePlay(source);

                Thread.sleep(5);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            LOG.error("WebDisplays audio player failed", t);
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
    }
}
