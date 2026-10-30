package net.montoyo.wd.client.stream;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.montoyo.wd.client.ClientProxy;
import net.montoyo.wd.client.stream.codec.FFmpegLoader;
import net.montoyo.wd.client.stream.codec.H264Encoder;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.registry.BlockRegistry;
import net.montoyo.wd.utilities.Multiblock;
import net.montoyo.wd.utilities.browser.WDClientBrowser;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector3i;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Client side entry point of the screen streaming prototype.
 * <ul>
 *     <li>{@code /wdstream loopback [fps] [kbps] [maxHeight]} toggles loopback streaming on the screen you look at</li>
 *     <li>{@code /wdstream stop} stops every stream</li>
 *     <li>{@code /wdstream encoders} lists the H.264 encoders that work on this machine</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "webdisplays", value = Dist.CLIENT)
public final class StreamManager {
    private static final Map<ScreenData, LoopbackStream> STREAMS = new IdentityHashMap<>();

    private StreamManager() {
    }

    /** Render thread: texture to draw for this screen instead of the browser's own, or 0. */
    public static int textureFor(ScreenData screen) {
        if (STREAMS.isEmpty()) return 0;
        LoopbackStream stream = STREAMS.get(screen);
        if (stream == null) return 0;
        if (screen.browser != stream.browser) { // browser was recreated or closed
            stop(screen);
            return 0;
        }
        return stream.textureId();
    }

    private static void stop(ScreenData screen) {
        LoopbackStream stream = STREAMS.remove(screen);
        if (stream != null) stream.close();
    }

    private static void stopAll() {
        STREAMS.values().forEach(LoopbackStream::close);
        STREAMS.clear();
    }

    private static Path nativesDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("webdisplays").resolve("natives");
    }

    /** Loads FFmpeg off-thread (first use extracts ~70 MB of natives), then runs {@code then} on the client thread. */
    private static void withFFmpeg(Runnable then) {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> FFmpegLoader.ensureLoaded(nativesDir())).thenAccept(ok -> mc.execute(() -> {
            if (ok) then.run();
            else message(ChatFormatting.RED, "FFmpeg failed to load: " + FFmpegLoader.getFailure());
        }));
    }

    private static void message(ChatFormatting color, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[WD stream] " + text).withStyle(color), false);
    }

    private static ScreenData lookedAtScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;

        BlockHitResult hit = ClientProxy.raycast(64.0);
        if (hit.getType() != HitResult.Type.BLOCK || mc.level.getBlockState(hit.getBlockPos()).getBlock() != BlockRegistry.SCREEN_BLOCk.get())
            return null;

        Vector3i pos = new Vector3i(hit.getBlockPos());
        BlockSide side = BlockSide.values()[hit.getDirection().ordinal()];
        Multiblock.findOrigin(mc.level, pos, side, null);
        if (!(mc.level.getBlockEntity(pos.toBlock()) instanceof ScreenBlockEntity te)) return null;
        return te.getScreen(side);
    }

    private static int loopback(CommandContext<CommandSourceStack> ctx, int fps, int kbps, int maxHeight) {
        ScreenData screen = lookedAtScreen();
        if (screen == null) {
            message(ChatFormatting.RED, "Look at a screen first");
            return 0;
        }
        if (STREAMS.containsKey(screen)) {
            stop(screen);
            message(ChatFormatting.YELLOW, "Loopback stopped");
            return 1;
        }
        if (!(screen.browser instanceof WDClientBrowser browser)) {
            message(ChatFormatting.RED, "This screen has no browser yet");
            return 0;
        }

        message(ChatFormatting.GRAY, "Loading FFmpeg...");
        LoopbackStream.Settings settings = new LoopbackStream.Settings(fps, kbps, maxHeight * 16 / 9, maxHeight);
        withFFmpeg(() -> {
            if (screen.browser != browser || STREAMS.containsKey(screen)) return;
            STREAMS.put(screen, new LoopbackStream(screen, browser, settings));
            message(ChatFormatting.GREEN, String.format("Loopback started: %d fps, %d kbps, max %dp. The screen now shows the decoded stream.", fps, kbps, maxHeight));
        });
        return 1;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wdstream")
                .then(Commands.literal("loopback")
                        .executes(ctx -> loopback(ctx, 30, 4000, 720))
                        .then(Commands.argument("fps", IntegerArgumentType.integer(1, 60))
                                .executes(ctx -> loopback(ctx, IntegerArgumentType.getInteger(ctx, "fps"), 4000, 720))
                                .then(Commands.argument("kbps", IntegerArgumentType.integer(100, 50000))
                                        .executes(ctx -> loopback(ctx, IntegerArgumentType.getInteger(ctx, "fps"),
                                                IntegerArgumentType.getInteger(ctx, "kbps"), 720))
                                        .then(Commands.argument("maxHeight", IntegerArgumentType.integer(144, 2160))
                                                .executes(ctx -> loopback(ctx, IntegerArgumentType.getInteger(ctx, "fps"),
                                                        IntegerArgumentType.getInteger(ctx, "kbps"),
                                                        IntegerArgumentType.getInteger(ctx, "maxHeight")))))))
                .then(Commands.literal("stop").executes(ctx -> {
                    stopAll();
                    message(ChatFormatting.YELLOW, "All streams stopped");
                    return 1;
                }))
                .then(Commands.literal("encoders").executes(ctx -> {
                    message(ChatFormatting.GRAY, "Probing encoders...");
                    withFFmpeg(() -> CompletableFuture.supplyAsync(H264Encoder::available).thenAccept(list ->
                            Minecraft.getInstance().execute(() -> message(ChatFormatting.AQUA, "Working encoders (in order of preference): "
                                    + (list.isEmpty() ? "none" : list.stream().map(H264Encoder.Candidate::label).collect(Collectors.joining(", ")))))));
                    return 1;
                })));
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (STREAMS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = event.getPoseStack();
        int y = 4;
        for (LoopbackStream stream : STREAMS.values()) {
            List<String> lines = stream.stats.lines();
            mc.font.drawShadow(pose, "WD loopback stream", 4, y, 0x55FFFF);
            y += 10;
            for (String line : lines) {
                mc.font.drawShadow(pose, line, 8, y, 0xFFFFFF);
                y += 10;
            }
            y += 4;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || STREAMS.isEmpty()) return;
        if (Minecraft.getInstance().level == null) {
            stopAll();
            return;
        }
        for (Iterator<Map.Entry<ScreenData, LoopbackStream>> it = STREAMS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ScreenData, LoopbackStream> e = it.next();
            if (e.getKey().browser != e.getValue().browser) {
                e.getValue().close();
                it.remove();
            }
        }
    }
}
