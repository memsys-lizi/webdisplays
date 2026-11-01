package net.montoyo.wd.net.server_bound;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.network.NetworkEvent;
import net.montoyo.wd.core.ScreenRights;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.entity.SpeakerBlockEntity;
import net.montoyo.wd.net.Packet;
import net.montoyo.wd.utilities.serialization.Util;

/** Speaker GUI → server: new volume / range / channel for a speaker. */
public class C2SMessageSpeakerSettings extends Packet {
    private final BlockPos pos;
    private final int volume;
    private final int range;
    private final SpeakerBlockEntity.Channel channel;

    public C2SMessageSpeakerSettings(BlockPos pos, int volume, int range, SpeakerBlockEntity.Channel channel) {
        this.pos = pos;
        this.volume = volume;
        this.range = range;
        this.channel = channel;
    }

    public C2SMessageSpeakerSettings(FriendlyByteBuf buf) {
        super(buf);
        pos = buf.readBlockPos();
        volume = buf.readVarInt();
        range = buf.readVarInt();
        channel = buf.readEnum(SpeakerBlockEntity.Channel.class);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof C2SMessageSpeakerSettings m && m.pos.equals(pos) && m.volume == volume
                && m.range == range && m.channel == channel;
    }

    @Override
    public int hashCode() {
        return pos.hashCode() * 31 + volume * 7 + range * 3 + channel.ordinal();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(volume);
        buf.writeVarInt(range);
        buf.writeEnum(channel);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        if (!checkServer(ctx)) return;
        ServerPlayer player = ctx.getSender();
        ctx.enqueueWork(() -> apply(player));
        ctx.setPacketHandled(true);
    }

    private void apply(ServerPlayer player) {
        if (player == null || !player.level.isLoaded(pos)) return;
        double reach = player.getAttribute(ForgeMod.REACH_DISTANCE.get()).getValue() + 2.0;
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > reach * reach) return;
        if (!(player.level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)) return;

        // a linked speaker can only be changed by players allowed to control its screen
        ScreenBlockEntity screen = speaker.isScreenChunkLoaded() ? speaker.getConnectedScreenEx() : null;
        if (screen != null) {
            ScreenData scr = screen.getScreen(speaker.getScreenSide());
            if (scr != null && (scr.rightsFor(player) & ScreenRights.CHANGE_URL) == 0) {
                Util.toast(player, "restrictions");
                return;
            }
        }
        speaker.setSettings(volume, range, channel);
    }
}
