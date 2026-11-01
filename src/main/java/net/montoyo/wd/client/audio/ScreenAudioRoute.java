package net.montoyo.wd.client.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.entity.SpeakerBlockEntity;
import net.montoyo.wd.utilities.browser.WDClientBrowser;
import org.cef.misc.DataPointer;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The captured audio of one screen browser, played from the screen's speakers (one positional source each), or
 * from the middle of the screen when no speaker is linked to it.
 */
final class ScreenAudioRoute implements AutoCloseable {
    /** Below this the page counts as silent (used to pause the background music). */
    private static final float SILENCE = 0.003f; // AudioKeepAlive's tone peaks at 0.0015
    private static final Object SCREEN_KEY = new Object();

    private record Output(Object key, PositionalAudioPlayer player, SpeakerBlockEntity.Channel channel) {
    }

    private final WDClientBrowser browser;
    private final int channels;
    private final LinearResampler left, right;
    private float[] l = new float[1024], r = new float[1024], mix = new float[1200];

    private volatile Output[] outputs = new Output[0];
    private volatile long lastSound;
    private boolean closed;

    ScreenAudioRoute(WDClientBrowser browser, int sampleRate, int channels) {
        this.browser = browser;
        this.channels = Math.max(1, channels);
        this.left = new LinearResampler(sampleRate, PositionalAudioPlayer.SAMPLE_RATE);
        this.right = new LinearResampler(sampleRate, PositionalAudioPlayer.SAMPLE_RATE);
        refresh();
    }

    WDClientBrowser getBrowser() {
        return browser;
    }

    /** CEF audio thread. {@code data} points to {@code channels} planar float arrays of {@code frames} samples. */
    void onPacket(DataPointer data, int frames) {
        if (l.length < frames) {
            l = new float[frames];
            r = new float[frames];
        }
        data.forCapacity(channels * 8);
        FloatBuffer ch0 = MemoryUtil.memFloatBuffer(data.getLong(0), frames);
        FloatBuffer ch1 = channels > 1 ? MemoryUtil.memFloatBuffer(data.getLong(1), frames) : ch0;
        ch0.get(0, l, 0, frames);
        ch1.get(0, r, 0, frames);

        int n = left.process(l, frames);
        right.process(r, frames); // same rate and state as the left one, so it yields n samples too
        float[] lo = left.output(), ro = right.output();
        if (mix.length < n) mix = new float[n];

        boolean loud = false;
        for (int i = 0; i < n; i++) {
            float m = (lo[i] + ro[i]) * 0.5f;
            mix[i] = m;
            if (!loud && Math.abs(m) > SILENCE) loud = true;
        }
        if (loud) lastSound = System.currentTimeMillis();

        for (Output o : outputs) {
            float[] src = switch (o.channel) {
                case LEFT -> lo;
                case RIGHT -> ro;
                default -> mix;
            };
            o.player.feed(src, n);
        }
    }

    /** Client thread: follows the speakers linked to the screen and their settings. */
    synchronized void refresh() {
        if (closed) return;
        ScreenBlockEntity be = browser.getBe();
        if (be == null) return;
        BlockPos origin = be.getBlockPos();
        ScreenData scr = be.getScreen(browser.getSide());
        float master = AudioSettings.getVolume() / 100.0f;

        Map<Object, Output> old = new HashMap<>();
        for (Output o : outputs) old.put(o.key, o);

        List<SpeakerBlockEntity> speakers = SpeakerRegistry.linkedTo(origin, browser.getSide());
        Output[] next;
        if (speakers.isEmpty()) {
            Output o = reuse(old, SCREEN_KEY, screenCenter(be, scr), SpeakerBlockEntity.Channel.MIX);
            o.player.setVolume(master);
            o.player.setMaxDistance(AudioSettings.getScreenRange());
            next = new Output[]{o};
        } else {
            next = new Output[speakers.size()];
            for (int i = 0; i < next.length; i++) {
                SpeakerBlockEntity s = speakers.get(i);
                SpeakerRegistry.Settings set = SpeakerRegistry.settingsOf(s);
                Output o = reuse(old, s.getBlockPos(), Vec3.atCenterOf(s.getBlockPos()), set.channel());
                o.player.setVolume(master * set.volume() / 100.0f);
                o.player.setMaxDistance(set.range());
                next[i] = o;
            }
        }
        outputs = next;
        for (Output o : old.values()) o.player.close(); // speakers that went away
    }

    private static Output reuse(Map<Object, Output> old, Object key, Vec3 pos, SpeakerBlockEntity.Channel channel) {
        Output o = old.remove(key);
        if (o == null) return new Output(key, new PositionalAudioPlayer(pos), channel);
        o.player.setPosition(pos);
        return o.channel == channel ? o : new Output(key, o.player, channel);
    }

    /** Whether the page made a sound lately and the local player is within earshot of one of its outputs. */
    boolean isAudibleFrom(Vec3 listener, long now) {
        if (now - lastSound > 1500) return false;
        for (Output o : outputs) {
            if (o.player.getVolume() <= 0.0f) continue;
            double d = o.player.getMaxDistance();
            if (o.player.getPosition().distanceToSqr(listener) < d * d) return true;
        }
        return false;
    }

    private static Vec3 screenCenter(ScreenBlockEntity be, ScreenData scr) {
        if (scr == null) return Vec3.atCenterOf(be.getBlockPos());
        return new Vec3(
                be.getBlockPos().getX() + scr.side.right.x * scr.size.x / 2.0 + scr.side.up.x * scr.size.y / 2.0,
                be.getBlockPos().getY() + scr.side.right.y * scr.size.x / 2.0 + scr.side.up.y * scr.size.y / 2.0,
                be.getBlockPos().getZ() + scr.side.right.z * scr.size.x / 2.0 + scr.side.up.z * scr.size.y / 2.0);
    }

    @Override
    public synchronized void close() {
        closed = true;
        Output[] o = outputs;
        outputs = new Output[0];
        for (Output out : o) out.player.close();
    }
}
