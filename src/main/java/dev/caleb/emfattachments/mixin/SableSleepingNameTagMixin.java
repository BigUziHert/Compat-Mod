package dev.caleb.emfattachments.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.caleb.emfattachments.client.SableSleepingPose;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps sleeping name tags facing the camera after the redundant body rotation is removed. */
@Mixin(value = EntityRenderer.class, priority = 1500)
public abstract class SableSleepingNameTagMixin {
    // Sable's name-tag correction cancels its general dispatcher rotation. A
    // sleeper now rotates only inside LivingEntityRenderer's pushed body pose,
    // which is popped before the name tag renders. Use the same predicate as
    // the dispatcher correction so no unmatched inverse rotation is applied.
    @TargetHandler(mixin = "dev.ryanhcode.sable.mixin.entity.entity_rotations_and_riding.EntityRendererMixin",
            name = "sable$renderNameTag")
    @ModifyExpressionValue(method = "@MixinSquared:Handler", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/entity/EntitySubLevelUtil;shouldKick(Lnet/minecraft/world/entity/Entity;)Z",
            remap = false), require = 1, expect = 1, allow = 1)
    private boolean emfattachments$keepSleepingNameTagCameraFacing(boolean original,
            @Local(argsOnly = true) Entity entity) {
        return SableSleepingPose.keepsGeneralRotation(original, entity);
    }
}
