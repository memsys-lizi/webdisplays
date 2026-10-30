package net.montoyo.wd.net.stream;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.stream.ServerStreams;

/** Host -> server: a fragment of the host's encoded stream, relayed to the viewers. */
public class C2SStreamData extends Packet {
    private final StreamPayload payload;

    public C2SStreamData(StreamPayload payload) {
        this.payload = payload;
    }

    public C2SStreamData(FriendlyByteBuf buf) {
        super(buf);
        payload = StreamPayload.read(buf);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        payload.write(buf);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        if (checkServer(ctx)) {
            // relayed straight from the network thread: no world access, and no extra tick of latency
            ServerStreams.relay(ctx.getSender(), payload);
            ctx.setPacketHandled(true);
        }
    }
}
