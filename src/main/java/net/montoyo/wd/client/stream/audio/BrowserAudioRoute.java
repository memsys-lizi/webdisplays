package net.montoyo.wd.client.stream.audio;

import net.minecraft.world.phys.Vec3;
import net.montoyo.wd.client.stream.LoopbackStream;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.browser.WDClientBrowser;
import org.cef.misc.DataPointer;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * One captured CEF audio stream: downmixes to mono, resamples to 48 kHz and hands the samples either to the
 * screen's stream (encoded, then played after decoding) or straight to the positional player.
 */
public final class BrowserAudioRoute implements AutoCloseable {
    private final int channels;
    private final LinearResampler resampler;
    private final PositionalAudioPlayer player;

    private float[] mono = new float[1024];

    BrowserAudioRoute(WDClientBrowser browser, int sampleRate, int channels) {
        this.channels = Math.max(1, channels);
        this.resampler = new LinearResampler(sampleRate, PositionalAudioPlayer.SAMPLE_RATE);
        this.player = new PositionalAudioPlayer(screenCenter(browser));
    }

    public PositionalAudioPlayer getPlayer() {
        return player;
    }

    /** CEF audio thread. {@code data} points to {@code channels} planar float arrays of {@code frames} samples. */
    void onPacket(DataPointer data, int frames, LoopbackStream stream) {
        if (mono.length < frames) mono = new float[frames];
        java.util.Arrays.fill(mono, 0, frames, 0f);

        data.forCapacity(channels * 8);
        for (int c = 0; c < channels; c++) {
            FloatBuffer ch = MemoryUtil.memFloatBuffer(data.getLong(c), frames);
            for (int i = 0; i < frames; i++) mono[i] += ch.get(i);
        }
        float scale = 1f / channels;
        for (int i = 0; i < frames; i++) mono[i] *= scale;

        int n = resampler.process(mono, frames);
        if (stream != null) stream.audioIn(resampler.output(), n);
        else player.feed(resampler.output(), n);
    }

    private static Vec3 screenCenter(WDClientBrowser browser) {
        ScreenBlockEntity be = browser.getBe();
        if (be == null) return Vec3.ZERO;
        ScreenData scr = be.getScreen(browser.getSide());
        if (scr == null) return Vec3.atCenterOf(be.getBlockPos());
        // same anchor WebDisplays uses for screen distance checks
        return new Vec3(
                be.getBlockPos().getX() + scr.side.right.x * scr.size.x / 2.0 + scr.side.up.x * scr.size.y / 2.0,
                be.getBlockPos().getY() + scr.side.right.y * scr.size.x / 2.0 + scr.side.up.y * scr.size.y / 2.0,
                be.getBlockPos().getZ() + scr.side.right.z * scr.size.x / 2.0 + scr.side.up.z * scr.size.y / 2.0);
    }

    @Override
    public void close() {
        player.close();
    }
}
