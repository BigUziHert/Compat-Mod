package dev.caleb.emfattachments.compat;

import net.minecraft.world.phys.Vec3;

/** Numeric zero treats signed zero equally, without discarding small valid axes. */
public final class CollisionAxes {
    private CollisionAxes() {}

    public static boolean isZero(Vec3 axis) {
        // A length-squared epsilon would also discard tiny nonzero axes and can
        // underflow. Only an exactly degenerate separating axis is ignored.
        return axis.x == 0.0 && axis.y == 0.0 && axis.z == 0.0;
    }
}
