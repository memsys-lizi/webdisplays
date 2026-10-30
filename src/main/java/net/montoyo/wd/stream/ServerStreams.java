package net.montoyo.wd.stream;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.montoyo.wd.core.ScreenRights;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.stream.S2CStreamData;
import net.montoyo.wd.net.stream.S2CStreamKeyFrame;
import net.montoyo.wd.net.stream.S2CStreamState;
import net.montoyo.wd.net.stream.StreamPayload;
import net.montoyo.wd.utilities.data.BlockSide;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of screen streaming: one host player encodes a screen and sends it here; the server relays it to the
 * players that have the screen's chunk loaded (the viewers), whose clients show the stream instead of running
 * their own browser.
 */
@Mod.EventBusSubscriber(modid = "webdisplays")
public final class ServerStreams {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/Stream");

    /** The host must stay within this distance (blocks) of the screen. */
    private static final double MAX_HOST_DISTANCE = 64.0;
    private static final double MAX_START_DISTANCE = 16.0;
    private static final long KEY_FRAME_REQUEST_INTERVAL_MS = 500;

    private record Key(ResourceKey<Level> dimension, BlockPos pos, BlockSide side) {
    }

    private static final class Viewer {
        final ServerPlayer player;
        /** Video is withheld until the next key frame (just joined, or frames were dropped). */
        volatile boolean waitingKeyFrame = true;
        /** Frame currently being forwarded, -1 while skipping. Only touched by the host's network thread. */
        int forwardingSeq = -1;

        Viewer(ServerPlayer player) {
            this.player = player;
        }

        boolean writable() {
            return player.connection.connection.channel().isWritable();
        }
    }

    private static final class Session {
        final Key key;
        final UUID host;
        final String hostName;
        final int fps, bitrateKbps, maxHeight;
        volatile List<Viewer> viewers = List.of();
        volatile ServerPlayer hostPlayer;
        volatile long lastKeyFrameRequest;

        Session(Key key, ServerPlayer host, int fps, int bitrateKbps, int maxHeight) {
            this.key = key;
            this.host = host.getUUID();
            this.hostName = host.getGameProfile().getName();
            this.hostPlayer = host;
            this.fps = fps;
            this.bitrateKbps = bitrateKbps;
            this.maxHeight = maxHeight;
        }

        S2CStreamState state(boolean active) {
            return new S2CStreamState(key.pos(), key.side(), active, host, fps, bitrateKbps, maxHeight);
        }

        void requestKeyFrame() {
            long now = System.currentTimeMillis();
            ServerPlayer h = hostPlayer;
            if (h == null || now - lastKeyFrameRequest < KEY_FRAME_REQUEST_INTERVAL_MS) return;
            lastKeyFrameRequest = now;
            WDNetworkRegistry.INSTANCE.send(PacketDistributor.PLAYER.with(() -> h), new S2CStreamKeyFrame(key.pos(), key.side()));
        }
    }

    private static final Map<Key, Session> SESSIONS = new ConcurrentHashMap<>();
    private static int tickCounter;

    private ServerStreams() {
    }

    private static void tell(ServerPlayer player, ChatFormatting color, String text) {
        player.sendSystemMessage(Component.literal("[WD stream] " + text).withStyle(color));
    }

    /** Main thread. The sender asks to become the host of this screen's stream. */
    public static void start(ServerPlayer player, BlockPos pos, BlockSide side, int fps, int bitrateKbps, int maxHeight) {
        if (player == null) return;
        ScreenData screen = screenAt(player.getLevel(), pos, side);
        if (screen == null) {
            tell(player, ChatFormatting.RED, "There is no screen there");
            return;
        }
        if ((screen.rightsFor(player) & ScreenRights.CHANGE_URL) == 0) {
            tell(player, ChatFormatting.RED, "You need the permission to change this screen's URL to stream it");
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_START_DISTANCE * MAX_START_DISTANCE) {
            tell(player, ChatFormatting.RED, "You are too far from the screen");
            return;
        }

        Key key = new Key(player.getLevel().dimension(), pos.immutable(), side);
        Session existing = SESSIONS.get(key);
        if (existing != null) {
            if (!existing.host.equals(player.getUUID())) {
                tell(player, ChatFormatting.RED, existing.hostName + " is already streaming this screen");
                return;
            }
            end(existing); // restart with the new settings
        }

        Session session = new Session(key, player,
                Math.max(1, Math.min(60, fps)),
                Math.max(300, Math.min(20000, bitrateKbps)),
                Math.max(144, Math.min(1080, maxHeight)));
        SESSIONS.put(key, session);
        WDNetworkRegistry.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), session.state(true));
        updateViewers(player.getServer(), session);
        LOG.info("{} started streaming the screen at {} {} ({} fps, {} kbps, max {}p)",
                session.hostName, pos, side, session.fps, session.bitrateKbps, session.maxHeight);
    }

    /** Main thread. The host, or anyone allowed to change the URL, can stop a stream. */
    public static void stop(ServerPlayer player, BlockPos pos, BlockSide side) {
        if (player == null) return;
        Session session = SESSIONS.get(new Key(player.getLevel().dimension(), pos, side));
        if (session == null) {
            tell(player, ChatFormatting.YELLOW, "This screen is not being streamed");
            return;
        }
        ScreenData screen = screenAt(player.getLevel(), pos, side);
        boolean allowed = session.host.equals(player.getUUID())
                || (screen != null && (screen.rightsFor(player) & ScreenRights.CHANGE_URL) != 0);
        if (!allowed) {
            tell(player, ChatFormatting.RED, "Only " + session.hostName + " or someone allowed to change the URL can stop this stream");
            return;
        }
        end(session);
        tell(player, ChatFormatting.YELLOW, "Stream stopped");
    }

    /** Host's network thread: forward one fragment to every viewer that can take it. */
    public static void relay(ServerPlayer sender, StreamPayload payload) {
        if (sender == null) return;
        Session session = SESSIONS.get(new Key(sender.getLevel().dimension(), payload.pos(), payload.side()));
        if (session == null || !session.host.equals(sender.getUUID())) return;

        S2CStreamData out = new S2CStreamData(payload);
        for (Viewer v : session.viewers) {
            if (payload.audio()) {
                if (v.writable()) send(v, out);
                continue;
            }

            if (payload.fragment() == 0) { // decide per frame, never forward half a frame
                v.forwardingSeq = -1;
                if (!v.writable()) { // this viewer cannot keep up: skip until it has drained, then resync
                    v.waitingKeyFrame = true;
                    session.requestKeyFrame();
                    continue;
                }
                if (v.waitingKeyFrame) {
                    if (!payload.keyFrame()) {
                        session.requestKeyFrame();
                        continue;
                    }
                    v.waitingKeyFrame = false;
                }
                v.forwardingSeq = payload.seq();
            }
            if (payload.seq() == v.forwardingSeq) send(v, out);
        }
    }

    private static void send(Viewer v, S2CStreamData packet) {
        WDNetworkRegistry.INSTANCE.send(PacketDistributor.PLAYER.with(() -> v.player), packet);
    }

    private static void end(Session session) {
        SESSIONS.remove(session.key);
        S2CStreamState stopped = session.state(false);
        ServerPlayer host = session.hostPlayer;
        if (host != null && !host.hasDisconnected())
            WDNetworkRegistry.INSTANCE.send(PacketDistributor.PLAYER.with(() -> host), stopped);
        for (Viewer v : session.viewers)
            if (!v.player.hasDisconnected())
                WDNetworkRegistry.INSTANCE.send(PacketDistributor.PLAYER.with(() -> v.player), stopped);
        LOG.info("Stream of the screen at {} {} ended", session.key.pos(), session.key.side());
    }

    private static ScreenData screenAt(Level level, BlockPos pos, BlockSide side) {
        return level.getBlockEntity(pos) instanceof ScreenBlockEntity te ? te.getScreen(side) : null;
    }

    /** Main thread: viewers are the players that have the screen's chunk loaded (the host excluded). */
    private static void updateViewers(MinecraftServer server, Session session) {
        ServerLevel level = server.getLevel(session.key.dimension());
        if (level == null) return;

        Map<UUID, Viewer> previous = new HashMap<>();
        for (Viewer v : session.viewers) previous.put(v.player.getUUID(), v);

        List<Viewer> now = new ArrayList<>();
        boolean joined = false;
        for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(new ChunkPos(session.key.pos()), false)) {
            if (p.getUUID().equals(session.host)) continue;
            Viewer v = previous.get(p.getUUID());
            if (v == null || v.player != p) { // new viewer (or respawned player object)
                v = new Viewer(p);
                WDNetworkRegistry.INSTANCE.send(PacketDistributor.PLAYER.with(() -> p), session.state(true));
                joined = true;
            }
            now.add(v);
        }
        session.viewers = List.copyOf(now);
        if (joined) {
            session.lastKeyFrameRequest = 0;
            session.requestKeyFrame();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SESSIONS.isEmpty() || ++tickCounter % 10 != 0) return;
        MinecraftServer server = event.getServer();

        for (Session session : List.copyOf(SESSIONS.values())) {
            ServerPlayer host = server.getPlayerList().getPlayer(session.host);
            ServerLevel level = server.getLevel(session.key.dimension());
            BlockPos pos = session.key.pos();
            String reason = null;
            if (host == null) reason = "host left";
            else if (level == null || host.getLevel() != level) reason = "host changed dimension";
            else if (host.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_HOST_DISTANCE * MAX_HOST_DISTANCE)
                reason = "host walked too far away";
            else if (!level.isLoaded(pos) || screenAt(level, pos, session.key.side()) == null) reason = "screen removed";

            if (reason != null) {
                if (host != null) tell(host, ChatFormatting.YELLOW, "Stream stopped: " + reason);
                end(session);
                continue;
            }
            session.hostPlayer = host;
            updateViewers(server, session);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        for (Session session : List.copyOf(SESSIONS.values()))
            if (session.host.equals(id)) end(session);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SESSIONS.clear();
    }
}
