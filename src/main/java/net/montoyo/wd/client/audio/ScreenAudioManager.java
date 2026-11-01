package net.montoyo.wd.client.audio;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client glue for screen audio: keeps the routes in sync with the speakers, pauses Minecraft's background music
 * while a screen is playing within earshot, and provides {@code /wdaudio}.
 */
@Mod.EventBusSubscriber(modid = "webdisplays", value = Dist.CLIENT)
public final class ScreenAudioManager {
    private static boolean screenAudible;
    private static int tick;

    private ScreenAudioManager() {
    }

    public static void init() {
        AudioSettings.load();
        AudioTap.INSTANCE.register();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            screenAudible = false;
            return;
        }

        if (++tick % 100 == 0) AudioTap.INSTANCE.pruneDead();

        long now = System.currentTimeMillis();
        boolean audible = false;
        for (ScreenAudioRoute route : AudioTap.INSTANCE.routes()) {
            route.refresh();
            if (!audible && route.isAudibleFrom(mc.player.getEyePosition(), now)) audible = true;
        }
        screenAudible = audible;

        if (screenAudible && AudioSettings.isPauseMusic()) mc.getMusicManager().stopPlaying();
    }

    /** Keeps the background music from starting again while a screen is playing nearby. */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent ev) {
        if (ev.getSound() != null && ev.getSound().getSource() == SoundSource.MUSIC
                && screenAudible && AudioSettings.isPauseMusic())
            ev.setSound(null);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload ev) {
        if (ev.getLevel().isClientSide()) {
            SpeakerRegistry.clear();
            AudioTap.INSTANCE.closeAll();
            screenAudible = false;
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent ev) {
        ev.getDispatcher().register(Commands.literal("wdaudio")
                .executes(ctx -> status())
                .then(Commands.literal("volume")
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> {
                                    AudioSettings.setVolume(IntegerArgumentType.getInteger(ctx, "percent"));
                                    return status();
                                })))
                .then(Commands.literal("pausemusic")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    AudioSettings.setPauseMusic(BoolArgumentType.getBool(ctx, "enabled"));
                                    return status();
                                })))
                .then(Commands.literal("screenrange")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(4, 64))
                                .executes(ctx -> {
                                    AudioSettings.setScreenRange(IntegerArgumentType.getInteger(ctx, "blocks"));
                                    return status();
                                }))));
    }

    private static int status() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.displayClientMessage(Component.translatable("webdisplays.audio.status",
                    AudioSettings.getVolume(),
                    Component.translatable(AudioSettings.isPauseMusic() ? "webdisplays.audio.on" : "webdisplays.audio.off"),
                    AudioSettings.getScreenRange()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
