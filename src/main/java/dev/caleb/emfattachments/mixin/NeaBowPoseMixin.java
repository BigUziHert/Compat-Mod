package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.tr7zw.notenoughanimations.animations.hands.ClampCrossbowAnimations;
import dev.tr7zw.notenoughanimations.animations.hands.CustomBowAnimation;
import dev.tr7zw.notenoughanimations.animations.vanilla.VanillaTwoHandedAnimation;
import dev.tr7zw.notenoughanimations.api.BasicAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import strm.neaemfcompat.compat.EMFCompat;

/** Preserve selected native bow/crossbow poses through EMF evaluation. */
@Mixin(value = EMFCompat.class, remap = false)
public abstract class NeaBowPoseMixin {
    @ModifyReturnValue(method = "shouldPauseForAnimation", at = @At("RETURN"), require = 1)
    private static boolean emfattachments$preserveSelectedBow(boolean original, BasicAnimation animation) {
        // The bridge calls this with the actual per-arm priority winner. Its normal
        // enabled/config checks, pose cleanup, interpolation, and sleeve sync still apply.
        // Native crossbow loading and charged holding use VanillaTwoHandedAnimation;
        // its apply() intentionally leaves the vanilla setupAnim pose in place.
        // The optional clamp setting selects ClampCrossbowAnimations instead.
        return original || animation instanceof CustomBowAnimation
                || animation instanceof VanillaTwoHandedAnimation
                || animation instanceof ClampCrossbowAnimations;
    }
}
