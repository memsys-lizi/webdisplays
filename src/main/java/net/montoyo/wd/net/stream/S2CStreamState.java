package net.montoyo.wd.net.stream;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.UUID;

/** Server -> client: a screen started or stopped being streamed, and by whom. */
public class S2CStreamState extends Packet {
    private final BlockPos pos;
    private final BlockSide side;
    private final boolean active;
    private final UUID host;
    private final int fps, bitrateKbps, maxHeight;

    public S2CStreamState(BlockPos pos, BlockSide side, boolean active, UUID host, int fps, int bitrateKbps, int maxHeight) {
        this.pos = pos;
        this.side = side;
        this.active = active;
        this.host = host;
        this.fps = fps;
        this.bitrateKbps = bitrateKbps;
        this.maxHeight = maxHeight;
    }

    public S2CStreamState(FriendlyByteBuf buf) {
        super(buf);
        pos = buf.readBlockPos();
        side = BlockSide.values()[buf.readByte()];
        active = buf.readBoolean();
        host = buf.readUUID();
        fps = buf.readVarInt();
        bitrateKbps = buf.readVarInt();
        maxHeight = buf.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByte(side.ordinal());
        buf.writeBoolean(active);
        buf.writeUUID(host);
        buf.writeVarInt(fps);
        buf.writeVarInt(bitrateKbps);
        buf.writeVarInt(maxHeight);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        if (checkClient(ctx)) {
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    net.montoyo.wd.client.stream.StreamManager.onNetworkState(pos, side, active, host, fps, bitrateKbps, maxHeight)));
            ctx.setPacketHandled(true);
        }
    }
}
