package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.SableFreshAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.entity_model_features.models.animation.math.asm.ASMAnimationHandler;
import traben.entity_model_features.models.animation.state.EMFEntityRenderState;

/** A cached LOD pose must not advance the deck's animation history. */
@Mixin(value = ASMAnimationHandler.class, remap = false)
public abstract class SableFreshAsmEvaluationMixin {
    @Inject(method = "animateInnerNoLOD(Ltraben/entity_model_features/models/animation/state/EMFEntityRenderState;)V",
            at = @At("HEAD"), require = 1)
    private void emfattachments$beforeEvaluation(EMFEntityRenderState state, CallbackInfo ci) {
        ((SableFreshAnimationState.AnimationAccess) this).emfattachments$beforeFreshEvaluation();
    }
}
