package dev.caleb.emfattachments.client;

import net.minecraft.world.phys.Vec3;

/** FPM's original body translation is in world units, unlike the scaled model. */
public final class FirstPersonOffsets {
    private FirstPersonOffsets() {}

    public static Vec3 scaleOriginalBodyOffset(Vec3 original, double scale) {
        if (!Double.isFinite(scale) || scale <= 0 || scale == 1) return original;
        // The native swimming Y displacement uses the same 0.6-block body
        // dimension as its horizontal displacement. Scale both before FPM
        // invokes external PlayerOffsetHandlers, whose results remain untouched.
        return original.scale(scale);
    }

    /** Smooth visual clearance at steep downward camera angles, in world units. */
    public static Vec3 addDownwardClearance(Vec3 original, double pitch, double yaw,
                                           double scale, double maximum) {
        if (!Double.isFinite(pitch) || !Double.isFinite(yaw) || !Double.isFinite(scale)
                || scale <= 0 || !Double.isFinite(maximum) || maximum <= 0 || pitch <= 40) return original;
        double progress = Math.min(1, (pitch - 40) / 50);
        double distance = maximum * scale * progress * progress * (3 - 2 * progress);
        double angle = Math.toRadians(yaw);
        return original.add(Math.sin(angle) * distance, 0, -Math.cos(angle) * distance);
    }
}
