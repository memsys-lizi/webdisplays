package net.montoyo.wd.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.montoyo.wd.client.audio.SpeakerRegistry;
import net.montoyo.wd.registry.TileRegistry;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector3i;

import javax.annotation.Nullable;

/**
 * A speaker linked (with the linker) to a screen: plays that screen's browser audio from its own position.
 * The settings live on the server and are synced to every client.
 */
public class SpeakerBlockEntity extends AbstractPeripheralBlockEntity {
    public enum Channel {
        MIX, LEFT, RIGHT;

        public Channel next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public static final int MIN_RANGE = 4;
    public static final int MAX_RANGE = 64;
    public static final int DEFAULT_RANGE = 24;

    private int volume = 100;   // percent
    private int range = DEFAULT_RANGE;
    private Channel channel = Channel.MIX;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(TileRegistry.SPEAKER.get(), pos, state);
    }

    public int getVolume() {
        return volume;
    }

    public int getRange() {
        return range;
    }

    public Channel getChannel() {
        return channel;
    }

    /** Server side: changes the settings and syncs them to the clients. */
    public void setSettings(int volume, int range, Channel channel) {
        this.volume = Mth.clamp(volume, 0, 100);
        this.range = Mth.clamp(range, MIN_RANGE, MAX_RANGE);
        this.channel = channel;
        sync();
    }

    @Override
    public boolean connect(Level world, BlockPos blockPos, BlockState blockState, Vector3i pos, BlockSide side) {
        if (!super.connect(world, blockPos, blockState, pos, side)) return false;
        sync();
        return true;
    }

    /** Whether this speaker plays the screen at {@code origin} on {@code side}. */
    public boolean isLinkedTo(BlockPos origin, BlockSide side) {
        Vector3i p = screenPos;
        return p != null && screenSide == side && p.x == origin.getX() && p.y == origin.getY() && p.z == origin.getZ();
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        volume = tag.contains("Volume") ? Mth.clamp(tag.getInt("Volume"), 0, 100) : 100;
        range = tag.contains("Range") ? Mth.clamp(tag.getInt("Range"), MIN_RANGE, MAX_RANGE) : DEFAULT_RANGE;
        int ch = tag.getByte("Channel");
        channel = ch >= 0 && ch < Channel.values().length ? Channel.values()[ch] : Channel.MIX;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Volume", volume);
        tag.putInt("Range", range);
        tag.putByte("Channel", (byte) channel.ordinal());
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SpeakerRegistry.add(this));
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SpeakerRegistry.remove(this));
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && level.isClientSide)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SpeakerRegistry.remove(this));
    }
}
