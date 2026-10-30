package net.montoyo.wd.client.stream;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.montoyo.wd.client.stream.audio.PositionalAudioPlayer;
import net.montoyo.wd.client.stream.codec.EncodedPacket;
import net.montoyo.wd.net.stream.StreamPayload;
import net.montoyo.wd.utilities.data.BlockSide;

/**
 * This client watches a screen streamed by another player: no local browser runs for the screen; received frames
 * are decoded onto it and the sound plays from its position.
 */
final class ViewerStream {
    /** Network jitter allowance for audio, on top of the player's own queue. */
    private static final int AUDIO_START_BUFFER_MS = 120;

    final BlockPos pos;
    final BlockSide side;
    final java.util.UUID host;
    final StreamStats stats = new StreamStats(StreamStats.Role.VIEWER);
    private final PositionalAudioPlayer player;
    private final StreamDecoder decoder;

    // frame reassembly; only touched by the network thread
    private int seq = -1, received;
    private byte[][] parts;
    private boolean keyFrame;
    private long ptsMs;

    ViewerStream(BlockPos pos, BlockSide side, java.util.UUID host, String hostName, Vec3 center) {
        this.pos = pos;
        this.side = side;
        this.host = host;
        stats.title = "WD stream (watching)";
        stats.encoderName = "from " + hostName;
        player = new PositionalAudioPlayer(center, AUDIO_START_BUFFER_MS);
        stats.audioStatus = player::describe;
        decoder = new StreamDecoder(stats, () -> player, true);
    }

    /** Network thread. */
    void onPayload(StreamPayload p) {
        if (p.audio()) {
            stats.audioBytes.addAndGet(p.data().length);
            decoder.audio(new EncodedPacket(p.data(), p.ptsMs(), true));
            return;
        }

        stats.bytes.addAndGet(p.data().length);
        if (p.fragment() == 0) {
            seq = p.seq();
            parts = new byte[p.fragments()][];
            received = 0;
            keyFrame = p.keyFrame();
            ptsMs = p.ptsMs();
        } else if (p.seq() != seq || parts == null || p.fragment() >= parts.length) {
            return; // missed the start of this frame
        }
        if (parts[p.fragment()] == null) received++;
        parts[p.fragment()] = p.data();
        if (received < parts.length) return;

        byte[] frame;
        if (parts.length == 1) {
            frame = parts[0];
        } else {
            int len = 0;
            for (byte[] part : parts) len += part.length;
            frame = new byte[len];
            int off = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, frame, off, part.length);
                off += part.length;
            }
        }
        parts = null;
        stats.encoded.incrementAndGet();
        if (keyFrame) stats.keyFrames.incrementAndGet();
        decoder.video(new EncodedPacket(frame, ptsMs, keyFrame), 0);
    }

    /** Render thread. */
    int textureId() {
        return decoder.textureId();
    }

    /** Render thread. */
    void close() {
        decoder.close();
        player.close();
    }
}
