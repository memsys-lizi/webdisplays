package net.montoyo.wd.client.compat;

import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;

/**
 * Stand-in for the 1.19.3+ {@code com.mojang.math.Axis}, backed by the 1.19.2 {@link Vector3f} axis constants.
 */
@FunctionalInterface
public interface Axis {
    Axis XN = deg -> Vector3f.XN.rotationDegrees(deg);
    Axis XP = deg -> Vector3f.XP.rotationDegrees(deg);
    Axis YN = deg -> Vector3f.YN.rotationDegrees(deg);
    Axis YP = deg -> Vector3f.YP.rotationDegrees(deg);
    Axis ZN = deg -> Vector3f.ZN.rotationDegrees(deg);
    Axis ZP = deg -> Vector3f.ZP.rotationDegrees(deg);

    Quaternion rotationDegrees(float degrees);

    default Quaternion rotation(float radians) {
        return rotationDegrees((float) Math.toDegrees(radians));
    }
}
