package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.caleb.emfattachments.client.SableFreshAnimationState;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.entity_model_features.models.animation.math.expression_tree.OldEMFAnimationHandler;

/** Mirrors the compiled evaluator's boundary when EMF's interpreter is selected. */
@Mixin(value = OldEMFAnimationHandler.class, remap = false)
public abstract class SableFreshInterpreterEvaluationMixin {
    @Inject(method = "animateInner([Lnet/minecraft/client/model/geom/ModelPart;)V",
            at = @At(value = "INVOKE", target = "Ltraben/entity_model_features/models/animation/math/expression_tree/OldEMFAnimationHandler;lines()Ljava/util/List;"),
            require = 1)
    private void emfattachments$beforeEvaluation(ModelPart[] pausedParts, CallbackInfo ci, @Local boolean skip) {
        if (!skip) ((SableFreshAnimationState.AnimationAccess) this).emfattachments$beforeFreshEvaluation();
    }
}
