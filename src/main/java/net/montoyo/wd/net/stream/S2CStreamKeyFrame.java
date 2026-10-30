package net.montoyo.wd.net.stream;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.utilities.data.BlockSide;

/** Server -> host: a viewer joined or lost data, send a key frame as soon as possible. */
public class S2CStreamKeyFrame extends Packet {
    private final BlockPos pos;
    private final BlockSide side;

    public S2CStreamKeyFrame(BlockPos pos, BlockSide side) {
        this.pos = pos;
        this.side = side;
    }

    public S2CStreamKeyFrame(FriendlyByteBuf buf) {
        super(buf);
        pos = buf.readBlockPos();
        side = BlockSide.values()[buf.readByte()];
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByte(side.ordinal());
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        if (checkClient(ctx)) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.montoyo.wd.client.stream.StreamManager.onKeyFrameRequest(pos, side));
            ctx.setPacketHandled(true);
        }
    }
}
