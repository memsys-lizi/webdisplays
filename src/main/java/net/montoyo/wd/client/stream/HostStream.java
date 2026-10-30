package net.montoyo.wd.client.stream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.montoyo.wd.client.stream.audio.AudioTap;
import net.montoyo.wd.client.stream.audio.BrowserAudioRoute;
import net.montoyo.wd.client.stream.codec.EncodedPacket;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.stream.C2SStreamData;
import net.montoyo.wd.net.stream.StreamPayload;
import net.montoyo.wd.utilities.browser.WDClientBrowser;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.Arrays;

/**
 * This client hosts a screen's stream: its own browser keeps showing and playing as usual, while the picture and
 * sound are encoded and sent to the server for the other players.
 */
final class HostStream implements StreamEncoder.Sink {
    final BlockPos pos;
    final BlockSide side;
    final WDClientBrowser browser;
    final StreamStats stats = new StreamStats(StreamStats.Role.HOST);
    final StreamEncoder encoder;

    private int videoSeq, audioSeq;

    HostStream(BlockPos pos, BlockSide side, WDClientBrowser browser, StreamEncoder.Settings settings) {
        this.pos = pos;
        this.side = side;
        this.browser = browser;
        stats.title = "WD stream (hosting)";
        stats.audioStatus = () -> {
            BrowserAudioRoute route = AudioTap.INSTANCE.routeFor(browser);
            return route == null ? "waiting for sound" : route.getPlayer().describe();
        };
        encoder = new StreamEncoder(browser, settings, stats, this, true);
    }

    private static boolean canSend() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener != null && listener.getConnection().isConnected() && listener.getConnection().channel().isWritable();
    }

    /** Encoder thread. */
    @Override
    public void video(EncodedPacket packet, long captureNanos) {
        if (!canSend()) {
            // upload cannot keep up: skip this frame; the next one must be a key frame since viewers miss this one
            stats.dropped.incrementAndGet();
            encoder.requestKeyFrame();
            return;
        }

        byte[] data = packet.data();
        int seq = videoSeq++;
        int fragments = Math.max(1, (data.length + StreamPayload.MAX_FRAGMENT - 1) / StreamPayload.MAX_FRAGMENT);
        for (int i = 0; i < fragments; i++) {
            int from = i * StreamPayload.MAX_FRAGMENT;
            byte[] chunk = fragments == 1 ? data : Arrays.copyOfRange(data, from, Math.min(data.length, from + StreamPayload.MAX_FRAGMENT));
            WDNetworkRegistry.INSTANCE.sendToServer(new C2SStreamData(
                    new StreamPayload(pos, side, false, seq, i, fragments, packet.keyFrame(), packet.ptsMs(), chunk)));
        }
    }

    /** CEF audio thread. */
    @Override
    public void audio(EncodedPacket packet) {
        if (!canSend()) return;
        WDNetworkRegistry.INSTANCE.sendToServer(new C2SStreamData(
                new StreamPayload(pos, side, true, audioSeq++, 0, 1, false, packet.ptsMs(), packet.data())));
    }

    void close() {
        encoder.close();
    }
}
