package net.montoyo.wd.net.stream;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.montoyo.wd.utilities.data.BlockSide;

/**
 * One fragment of an encoded stream packet. Video frames larger than {@link #MAX_FRAGMENT} bytes are split, since
 * serverbound custom payloads are limited to 32767 bytes in 1.19.2.
 *
 * @param seq       frame number (video) or packet number (audio), per stream
 * @param fragment  index of this fragment in the frame
 * @param fragments total fragments of the frame
 * @param ptsMs     presentation time on the host's stream clock
 */
public record StreamPayload(BlockPos pos, BlockSide side, boolean audio, int seq, int fragment, int fragments,
                            boolean keyFrame, long ptsMs, byte[] data) {
    public static final int MAX_FRAGMENT = 30000;

    public static StreamPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockSide side = BlockSide.values()[buf.readByte()];
        byte flags = buf.readByte();
        int seq = buf.readVarInt();
        int fragment = buf.readVarInt();
        int fragments = buf.readVarInt();
        long pts = buf.readVarLong();
        byte[] data = buf.readByteArray(MAX_FRAGMENT);
        return new StreamPayload(pos, side, (flags & 1) != 0, seq, fragment, fragments, (flags & 2) != 0, pts, data);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByte(side.ordinal());
        buf.writeByte((audio ? 1 : 0) | (keyFrame ? 2 : 0));
        buf.writeVarInt(seq);
        buf.writeVarInt(fragment);
        buf.writeVarInt(fragments);
        buf.writeVarLong(ptsMs);
        buf.writeByteArray(data);
    }
}
