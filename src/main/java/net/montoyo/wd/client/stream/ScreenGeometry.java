package net.montoyo.wd.client.stream;

import net.minecraft.world.phys.Vec3;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;

public final class ScreenGeometry {
    private ScreenGeometry() {
    }

    /** Where a screen's sound comes from: the same anchor WebDisplays uses for screen distance checks. */
    public static Vec3 center(ScreenBlockEntity be, ScreenData scr) {
        if (scr == null) return Vec3.atCenterOf(be.getBlockPos());
        return new Vec3(
                be.getBlockPos().getX() + scr.side.right.x * scr.size.x / 2.0 + scr.side.up.x * scr.size.y / 2.0,
                be.getBlockPos().getY() + scr.side.right.y * scr.size.x / 2.0 + scr.side.up.y * scr.size.y / 2.0,
                be.getBlockPos().getZ() + scr.side.right.z * scr.size.x / 2.0 + scr.side.up.z * scr.size.y / 2.0);
    }
}
