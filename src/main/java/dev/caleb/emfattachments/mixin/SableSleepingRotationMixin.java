package dev.caleb.emfattachments.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.caleb.emfattachments.client.SableSleepingPose;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Prevents Sable 2.0.6 from rotating sleeping players once in each of two renderers. */
@Mixin(value = EntityRenderDispatcher.class, priority = 1500)
public abstract class SableSleepingRotationMixin {
    // The general dispatcher handler and dedicated sleeping renderer both
    // inherit the bed sublevel's orientation. Suppress only the former; the
    // latter also correctly rotates vanilla's head-to-foot bed translation.
    // Camera inheritance, position updates, physics and non-sleeping renders
    // never call this modified result outside the selected render handler.
    @TargetHandler(mixin = "dev.ryanhcode.sable.mixin.entity.entity_rotations_and_riding.EntityRenderDispatcherMixin",
            name = "sable$rotateEntity")
    @ModifyExpressionValue(method = "@MixinSquared:Handler", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/entity/EntitySubLevelUtil;shouldKick(Lnet/minecraft/world/entity/Entity;)Z",
            remap = false), require = 1, expect = 1, allow = 1)
    private boolean emfattachments$keepSingleSleepingRotation(boolean original,
            @Local(argsOnly = true) Entity entity) {
        return SableSleepingPose.keepsGeneralRotation(original, entity);
    }
}
