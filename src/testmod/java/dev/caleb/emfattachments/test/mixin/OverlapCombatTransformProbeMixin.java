package dev.caleb.emfattachments.test.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import dev.caleb.emfattachments.test.OverlapCombatProbe;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.impl.AnimationProcessor;
import dev.kosmx.playerAnim.core.util.Vec3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Suppresses only our root rotation channel for a diagnostic negative control. */
@Mixin(value = AnimationProcessor.class, priority = 1500, remap = false)
public abstract class OverlapCombatTransformProbeMixin {
    @Shadow private float tickDelta;
    @TargetHandler(mixin = "dev.caleb.emfattachments.mixin.FirstPersonCombatAnimationMixin", name = "emfattachments$cameraRelativeAttack")
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"), cancellable = true, require = 1)
    private void emfattachments$overlapTransform(String part, TransformType type, Vec3f initial,
            CallbackInfoReturnable<Vec3f> nativeResult, CallbackInfo ci) {
        if (OverlapCombatProbe.transformBefore(this, part, type, nativeResult.getReturnValue(), tickDelta)) ci.cancel();
    }
    @TargetHandler(mixin = "dev.caleb.emfattachments.mixin.FirstPersonCombatAnimationMixin", name = "emfattachments$cameraRelativeAttack")
    @Inject(method = "@MixinSquared:Handler", at = @At("RETURN"), require = 1)
    private void emfattachments$overlapAfterTransform(String part, TransformType type, Vec3f initial,
            CallbackInfoReturnable<Vec3f> nativeResult, CallbackInfo ci) {
        OverlapCombatProbe.transformAfter(this, part, type, nativeResult.getReturnValue());
    }
}
