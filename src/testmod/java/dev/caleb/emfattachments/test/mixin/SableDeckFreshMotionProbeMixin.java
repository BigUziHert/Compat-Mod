package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.SableDeckSequence;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bounded opt-in fixture evidence; no production telemetry. */
@Pseudo
@Mixin(targets = "dev.caleb.emfattachments.client.SableFreshAnimationHistory", remap = false)
public abstract class SableDeckFreshMotionProbeMixin {
    @Inject(method = "update(Ljava/util/Map;Ljava/lang/Object;FFDZ)V", at = @At("HEAD"), require = 1)
    private void emfattachments_test$motion(Map<String, Float> variables, Object support,
                                          float frame, float worldY, double ownVerticalMotion, boolean grounded, CallbackInfo ci) {
        SableDeckSequence.freshMotion(ownVerticalMotion, worldY, frame);
    }
}
