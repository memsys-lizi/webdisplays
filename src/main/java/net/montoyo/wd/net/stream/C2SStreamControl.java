package net.montoyo.wd.net.stream;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.stream.ServerStreams;
import net.montoyo.wd.utilities.data.BlockSide;

/** Client -> server: start streaming a screen (the sender becomes the host) or stop its stream. */
public class C2SStreamControl extends Packet {
    private final BlockPos pos;
    private final BlockSide side;
    private final boolean start;
    private final int fps, bitrateKbps, maxHeight;

    public C2SStreamControl(BlockPos pos, BlockSide side, boolean start, int fps, int bitrateKbps, int maxHeight) {
        this.pos = pos;
        this.side = side;
        this.start = start;
        this.fps = fps;
        this.bitrateKbps = bitrateKbps;
        this.maxHeight = maxHeight;
    }

    public C2SStreamControl(FriendlyByteBuf buf) {
        super(buf);
        pos = buf.readBlockPos();
        side = BlockSide.values()[buf.readByte()];
        start = buf.readBoolean();
        fps = buf.readVarInt();
        bitrateKbps = buf.readVarInt();
        maxHeight = buf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByte(side.ordinal());
        buf.writeBoolean(start);
        buf.writeVarInt(fps);
        buf.writeVarInt(bitrateKbps);
        buf.writeVarInt(maxHeight);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        if (checkServer(ctx)) {
            ctx.enqueueWork(() -> {
                if (start) ServerStreams.start(ctx.getSender(), pos, side, fps, bitrateKbps, maxHeight);
                else ServerStreams.stop(ctx.getSender(), pos, side);
            });
            ctx.setPacketHandled(true);
        }
    }
}
