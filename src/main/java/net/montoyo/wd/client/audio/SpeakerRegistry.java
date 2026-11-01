package net.montoyo.wd.client.audio;

import net.minecraft.core.BlockPos;
import net.montoyo.wd.entity.SpeakerBlockEntity;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Client side: the speakers currently loaded in the world. */
public final class SpeakerRegistry {
    private static final Set<SpeakerBlockEntity> SPEAKERS = ConcurrentHashMap.newKeySet();

    /** Settings of one speaker as heard by this client. */
    public record Settings(int volume, int range, SpeakerBlockEntity.Channel channel) {
    }

    /**
     * Settings being edited in the speaker GUI: heard right away, before the server has confirmed them (and while
     * older updates from the server are still on their way).
     */
    private record Preview(BlockPos pos, Settings settings, long until) {
    }

    private static volatile Preview preview;

    private SpeakerRegistry() {
    }

    public static void add(SpeakerBlockEntity speaker) {
        SPEAKERS.add(speaker);
    }

    public static void remove(SpeakerBlockEntity speaker) {
        SPEAKERS.remove(speaker);
    }

    public static void clear() {
        SPEAKERS.clear();
        preview = null;
    }

    public static void setPreview(BlockPos pos, Settings settings) {
        preview = new Preview(pos, settings, Long.MAX_VALUE);
    }

    /** The GUI closed: keep the preview until the server's copy matches it (or a couple of seconds have passed). */
    public static void endPreview() {
        Preview p = preview;
        if (p != null) preview = new Preview(p.pos, p.settings, System.currentTimeMillis() + 2000);
    }

    public static Settings settingsOf(SpeakerBlockEntity speaker) {
        Settings actual = new Settings(speaker.getVolume(), speaker.getRange(), speaker.getChannel());
        Preview p = preview;
        if (p == null || !p.pos.equals(speaker.getBlockPos())) return actual;
        if (p.until != Long.MAX_VALUE && (actual.equals(p.settings) || System.currentTimeMillis() > p.until)) {
            preview = null;
            return actual;
        }
        return p.settings;
    }

    /** The loaded speakers playing the screen at {@code origin} on {@code side}. */
    public static List<SpeakerBlockEntity> linkedTo(BlockPos origin, BlockSide side) {
        List<SpeakerBlockEntity> ret = new ArrayList<>();
        for (SpeakerBlockEntity s : SPEAKERS)
            if (!s.isRemoved() && s.isLinkedTo(origin, side)) ret.add(s);
        return ret;
    }
}
