package net.montoyo.wd.net.stream;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.net.Packet;

/** Server -> viewer: a fragment of a screen's stream. */
public class S2CStreamData extends Packet {
    private final StreamPayload payload;

    public S2CStreamData(StreamPayload payload) {
        this.payload = payload;
    }

    public S2CStreamData(FriendlyByteBuf buf) {
        super(buf);
        payload = StreamPayload.read(buf);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        payload.write(buf);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        if (checkClient(ctx)) {
            // decoder queues are thread safe; skipping the main thread hop keeps latency down
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.montoyo.wd.client.stream.StreamManager.onNetworkData(payload));
            ctx.setPacketHandled(true);
        }
    }
}
