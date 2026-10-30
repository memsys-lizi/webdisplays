package net.montoyo.wd.client.stream;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
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
import net.montoyo.wd.client.stream.audio.AudioTap;
import net.montoyo.wd.client.stream.codec.FFmpegLoader;
import net.montoyo.wd.client.stream.codec.H264Encoder;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.stream.C2SStreamControl;
import net.montoyo.wd.net.stream.StreamPayload;
import net.montoyo.wd.registry.BlockRegistry;
import net.montoyo.wd.utilities.Multiblock;
import net.montoyo.wd.utilities.browser.WDClientBrowser;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector3i;
import org.cef.browser.CefBrowser;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Client side of screen streaming.
 * <ul>
 *     <li>{@code /wdstream start [fps] [kbps] [maxHeight]}: stream the screen you look at to the other players</li>
 *     <li>{@code /wdstream stop}: stop the stream of the screen you look at (and any loopback test)</li>
 *     <li>{@code /wdstream loopback [fps] [kbps] [maxHeight]}: local encode/decode test on the screen you look at</li>
 *     <li>{@code /wdstream encoders}: list the H.264 encoders that work on this machine</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "webdisplays", value = Dist.CLIENT)
public final class StreamManager {
    record ScreenKey(BlockPos pos, BlockSide side) {
    }

    private static final Map<ScreenData, LoopbackStream> LOOPBACKS = new IdentityHashMap<>();
    private static final Map<ScreenKey, HostStream> HOSTS = new ConcurrentHashMap<>();
    private static final Map<ScreenKey, ViewerStream> VIEWERS = new ConcurrentHashMap<>();
    /** Screens streamed by someone else: no local browser may be created for them. */
    private static final Set<ScreenKey> REMOTE = ConcurrentHashMap.newKeySet();
    /** Encoders by browser, for the CEF audio thread. */
    private static final Map<CefBrowser, StreamEncoder> ENCODERS = new ConcurrentHashMap<>();

    private StreamManager() {
    }

    // ---------------------------------------------------------------- queries from rendering / audio / screens

    /** Any thread: the encoder fed by this browser (loopback or hosting), or null. */
    public static StreamEncoder streamForBrowser(CefBrowser browser) {
        return ENCODERS.get(browser);
    }

    /** Whether this screen is shown from someone else's stream, so it must not get its own browser. */
    public static boolean isRemote(BlockPos pos, BlockSide side) {
        return !REMOTE.isEmpty() && REMOTE.contains(new ScreenKey(pos, side));
    }

    /** Render thread: texture to draw for this screen instead of the browser's own, or 0. */
    public static int textureFor(ScreenBlockEntity te, ScreenData screen) {
        if (!VIEWERS.isEmpty()) {
            ViewerStream viewer = VIEWERS.get(new ScreenKey(te.getBlockPos(), screen.side));
            if (viewer != null) return viewer.textureId();
        }
        if (LOOPBACKS.isEmpty()) return 0;
        LoopbackStream stream = LOOPBACKS.get(screen);
        if (stream == null) return 0;
        if (screen.browser != stream.browser) { // browser was recreated or closed
            stopLoopback(screen);
            return 0;
        }
        return stream.textureId();
    }

    // ---------------------------------------------------------------- network

    /** Main thread: the server says a screen started/stopped streaming. */
    public static void onNetworkState(BlockPos pos, BlockSide side, boolean active, UUID host, int fps, int kbps, int maxHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ScreenKey key = new ScreenKey(pos.immutable(), side);

        if (!active) {
            stopHost(key);
            stopViewer(key);
            return;
        }

        if (host.equals(mc.player.getUUID())) {
            stopViewer(key);
            if (!HOSTS.containsKey(key)) startHost(key, StreamEncoder.Settings.of(fps, kbps, maxHeight));
        } else {
            stopHost(key);
            ViewerStream viewer = VIEWERS.get(key);
            if (viewer == null || !viewer.host.equals(host)) {
                stopViewer(key);
                startViewer(key, host);
            }
        }
    }

    /** Network thread. */
    public static void onNetworkData(StreamPayload payload) {
        ViewerStream viewer = VIEWERS.get(new ScreenKey(payload.pos(), payload.side()));
        if (viewer != null) viewer.onPayload(payload);
    }

    /** Network thread. */
    public static void onKeyFrameRequest(BlockPos pos, BlockSide side) {
        HostStream host = HOSTS.get(new ScreenKey(pos, side));
        if (host != null) host.encoder.requestKeyFrame();
    }

    private static ScreenBlockEntity screenEntityAt(ScreenKey key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        return mc.level.getBlockEntity(key.pos()) instanceof ScreenBlockEntity te ? te : null;
    }

    private static ScreenData screenAt(ScreenKey key) {
        ScreenBlockEntity te = screenEntityAt(key);
        return te == null ? null : te.getScreen(key.side());
    }

    private static void startHost(ScreenKey key, StreamEncoder.Settings settings) {
        ScreenBlockEntity te = screenEntityAt(key);
        ScreenData screen = te == null ? null : te.getScreen(key.side());
        if (screen != null && screen.browser == null) screen.createBrowser(te, false);
        if (screen == null || !(screen.browser instanceof WDClientBrowser browser)) {
            message(ChatFormatting.RED, "Cannot stream: the screen has no browser here");
            sendStop(key);
            return;
        }

        withFFmpeg(() -> {
            if (screen.browser != browser || HOSTS.containsKey(key)) return;
            HostStream stream = new HostStream(key.pos(), key.side(), browser, settings);
            HOSTS.put(key, stream);
            ENCODERS.put(browser, stream.encoder);
            AudioTap.INSTANCE.startCapture(browser);
            message(ChatFormatting.GREEN, String.format("You are streaming this screen to nearby players (%d fps, %d kbps, max %dp). Playing audio pauses ~4 s while it switches.",
                    settings.fps(), settings.bitrateKbps(), settings.maxHeight()));
        }, () -> sendStop(key));
    }

    private static void stopHost(ScreenKey key) {
        HostStream stream = HOSTS.remove(key);
        if (stream == null) return;
        AudioTap.INSTANCE.stopCapture(stream.browser);
        ENCODERS.remove(stream.browser);
        stream.close();
    }

    private static void startViewer(ScreenKey key, UUID host) {
        ScreenBlockEntity te = screenEntityAt(key);
        ScreenData screen = te == null ? null : te.getScreen(key.side());
        if (screen == null) return;

        // the stream replaces this screen's browser: close it so it neither renders nor plays sound
        REMOTE.add(key);
        if (screen.browser != null) {
            screen.browser.close(true);
            screen.browser = null;
        }

        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(host);
        String hostName = info == null ? host.toString().substring(0, 8) : info.getProfile().getName();
        withFFmpeg(() -> {
            if (!REMOTE.contains(key) || VIEWERS.containsKey(key)) return;
            VIEWERS.put(key, new ViewerStream(key.pos(), key.side(), host, hostName, ScreenGeometry.center(te, screen)));
        }, () -> REMOTE.remove(key)); // no FFmpeg: fall back to a local browser
    }

    private static void stopViewer(ScreenKey key) {
        REMOTE.remove(key); // the screen renderer recreates the browser on the next frame
        ViewerStream stream = VIEWERS.remove(key);
        if (stream != null) stream.close();
    }

    private static void sendStop(ScreenKey key) {
        WDNetworkRegistry.INSTANCE.sendToServer(new C2SStreamControl(key.pos(), key.side(), false, 0, 0, 0));
    }

    // ---------------------------------------------------------------- loopback

    private static void stopLoopback(ScreenData screen) {
        LoopbackStream stream = LOOPBACKS.remove(screen);
        if (stream == null) return;
        AudioTap.INSTANCE.stopCapture(stream.browser);
        ENCODERS.remove(stream.browser);
        stream.close();
    }

    private static void stopAllLocal() {
        for (ScreenData screen : List.copyOf(LOOPBACKS.keySet())) stopLoopback(screen);
        for (ScreenKey key : List.copyOf(HOSTS.keySet())) stopHost(key);
        for (ScreenKey key : List.copyOf(VIEWERS.keySet())) stopViewer(key);
        REMOTE.clear();
    }

    // ---------------------------------------------------------------- commands

    private static Path nativesDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("webdisplays").resolve("natives");
    }

    /** Loads FFmpeg off-thread (first use extracts ~70 MB of natives), then runs one of the callbacks on the client thread. */
    private static void withFFmpeg(Runnable then, Runnable failed) {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            // pool threads use the app class loader; Forge's event bus transformer needs the mod's to resolve classes
            Thread.currentThread().setContextClassLoader(StreamManager.class.getClassLoader());
            return FFmpegLoader.ensureLoaded(nativesDir());
        }).thenAccept(ok -> mc.execute(() -> {
            if (ok) {
                then.run();
            } else {
                message(ChatFormatting.RED, "FFmpeg failed to load: " + FFmpegLoader.getFailure());
                failed.run();
            }
        }));
    }

    private static void message(ChatFormatting color, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[WD stream] " + text).withStyle(color), false);
    }

    private record LookedAt(ScreenBlockEntity te, ScreenData screen) {
    }

    private static LookedAt lookedAtScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;

        BlockHitResult hit = ClientProxy.raycast(64.0);
        if (hit.getType() != HitResult.Type.BLOCK || mc.level.getBlockState(hit.getBlockPos()).getBlock() != BlockRegistry.SCREEN_BLOCk.get())
            return null;

        Vector3i pos = new Vector3i(hit.getBlockPos());
        BlockSide side = BlockSide.values()[hit.getDirection().ordinal()];
        Multiblock.findOrigin(mc.level, pos, side, null);
        if (!(mc.level.getBlockEntity(pos.toBlock()) instanceof ScreenBlockEntity te) || te.getScreen(side) == null) return null;
        return new LookedAt(te, te.getScreen(side));
    }

    private static int start(int fps, int kbps, int maxHeight) {
        LookedAt at = lookedAtScreen();
        if (at == null) {
            message(ChatFormatting.RED, "Look at a screen first");
            return 0;
        }
        message(ChatFormatting.GRAY, "Asking the server to stream this screen...");
        WDNetworkRegistry.INSTANCE.sendToServer(new C2SStreamControl(at.te().getBlockPos(), at.screen().side, true, fps, kbps, maxHeight));
        return 1;
    }

    private static int stop() {
        boolean any = !LOOPBACKS.isEmpty();
        for (ScreenData screen : List.copyOf(LOOPBACKS.keySet())) stopLoopback(screen);

        LookedAt at = lookedAtScreen();
        if (at != null) {
            ScreenKey key = new ScreenKey(at.te().getBlockPos(), at.screen().side);
            if (HOSTS.containsKey(key) || VIEWERS.containsKey(key) || REMOTE.contains(key)) {
                sendStop(key);
                return 1;
            }
        }
        for (ScreenKey key : HOSTS.keySet()) { // not looking at it: stop what we host
            sendStop(key);
            any = true;
        }
        message(ChatFormatting.YELLOW, any ? "Stopping" : "Nothing to stop here");
        return 1;
    }

    private static int loopback(int fps, int kbps, int maxHeight) {
        LookedAt at = lookedAtScreen();
        if (at == null) {
            message(ChatFormatting.RED, "Look at a screen first");
            return 0;
        }
        ScreenData screen = at.screen();
        if (LOOPBACKS.containsKey(screen)) {
            stopLoopback(screen);
            message(ChatFormatting.YELLOW, "Loopback stopped");
            return 1;
        }
        if (!(screen.browser instanceof WDClientBrowser browser)) {
            message(ChatFormatting.RED, "This screen has no browser here");
            return 0;
        }
        if (ENCODERS.containsKey(browser)) {
            message(ChatFormatting.RED, "This screen is already being streamed");
            return 0;
        }

        message(ChatFormatting.GRAY, "Loading FFmpeg...");
        StreamEncoder.Settings settings = StreamEncoder.Settings.of(fps, kbps, maxHeight);
        withFFmpeg(() -> {
            if (screen.browser != browser || LOOPBACKS.containsKey(screen) || ENCODERS.containsKey(browser)) return;
            LoopbackStream stream = new LoopbackStream(screen, browser, settings);
            LOOPBACKS.put(screen, stream);
            ENCODERS.put(browser, stream.encoder);
            AudioTap.INSTANCE.startCapture(browser);
            message(ChatFormatting.GREEN, String.format("Loopback started: %d fps, %d kbps, max %dp. Playing audio pauses ~4 s while it switches.", fps, kbps, maxHeight));
        }, () -> {
        });
        return 1;
    }

    @FunctionalInterface
    private interface SettingsAction {
        int run(int fps, int kbps, int maxHeight);
    }

    /** A literal taking optional {@code [fps] [kbps] [maxHeight]} arguments. */
    private static LiteralArgumentBuilder<CommandSourceStack> withSettings(String name, int defKbps, int defHeight, SettingsAction action) {
        return Commands.literal(name)
                .executes(ctx -> action.run(30, defKbps, defHeight))
                .then(Commands.argument("fps", IntegerArgumentType.integer(1, 60))
                        .executes(ctx -> action.run(arg(ctx, "fps"), defKbps, defHeight))
                        .then(Commands.argument("kbps", IntegerArgumentType.integer(300, 20000))
                                .executes(ctx -> action.run(arg(ctx, "fps"), arg(ctx, "kbps"), defHeight))
                                .then(Commands.argument("maxHeight", IntegerArgumentType.integer(144, 1080))
                                        .executes(ctx -> action.run(arg(ctx, "fps"), arg(ctx, "kbps"), arg(ctx, "maxHeight"))))));
    }

    private static int arg(CommandContext<CommandSourceStack> ctx, String name) {
        return IntegerArgumentType.getInteger(ctx, name);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wdstream")
                .then(withSettings("start", 8000, 1080, StreamManager::start))
                .then(withSettings("loopback", 4000, 720, StreamManager::loopback))
                .then(Commands.literal("stop").executes(ctx -> stop()))
                .then(Commands.literal("encoders").executes(ctx -> {
                    message(ChatFormatting.GRAY, "Probing encoders...");
                    withFFmpeg(() -> CompletableFuture.supplyAsync(() -> {
                        Thread.currentThread().setContextClassLoader(StreamManager.class.getClassLoader());
                        return H264Encoder.available();
                    }).thenAccept(list -> Minecraft.getInstance().execute(() -> message(ChatFormatting.AQUA,
                            "Working encoders (in order of preference): " + (list.isEmpty() ? "none"
                                    : list.stream().map(H264Encoder.Candidate::label).collect(Collectors.joining(", ")))))), () -> {
                    });
                    return 1;
                })));
    }

    // ---------------------------------------------------------------- HUD and housekeeping

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (LOOPBACKS.isEmpty() && HOSTS.isEmpty() && VIEWERS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = event.getPoseStack();
        int[] y = {4};
        Consumer<StreamStats> draw = stats -> {
            mc.font.drawShadow(pose, stats.title, 4, y[0], 0x55FFFF);
            y[0] += 10;
            for (String line : stats.lines()) {
                mc.font.drawShadow(pose, line, 8, y[0], 0xFFFFFF);
                y[0] += 10;
            }
            y[0] += 4;
        };
        LOOPBACKS.values().forEach(s -> draw.accept(s.stats));
        HOSTS.values().forEach(s -> draw.accept(s.stats));
        VIEWERS.values().forEach(s -> draw.accept(s.stats));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (LOOPBACKS.isEmpty() && HOSTS.isEmpty() && VIEWERS.isEmpty() && REMOTE.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { // disconnected
            stopAllLocal();
            return;
        }
        for (Map.Entry<ScreenData, LoopbackStream> e : List.copyOf(LOOPBACKS.entrySet()))
            if (e.getKey().browser != e.getValue().browser) stopLoopback(e.getKey());
        for (Map.Entry<ScreenKey, HostStream> e : List.copyOf(HOSTS.entrySet())) {
            ScreenData screen = screenAt(e.getKey());
            if (screen == null || screen.browser != e.getValue().browser) { // screen unloaded or browser replaced
                stopHost(e.getKey());
                sendStop(e.getKey());
            }
        }
        for (ScreenKey key : List.copyOf(REMOTE)) // screen unloaded: the server re-sends the state when it is back in range
            if (screenAt(key) == null) stopViewer(key);
    }
}
