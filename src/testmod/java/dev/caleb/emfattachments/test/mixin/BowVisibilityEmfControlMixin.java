package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.BowVisibilityControls;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import strm.emfcompat.core.EMFCompatAnimationHook;
import traben.entity_model_features.EMFAnimationApi.EMFAnimationHook.AnimationContext;

@Mixin(value=EMFCompatAnimationHook.class,remap=false,priority=500)
public abstract class BowVisibilityEmfControlMixin {
    @Inject(method="onAnimationEnd",at=@At("RETURN"),require=1)
    private void emfattachments$diagnosticArms(AnimationContext context,boolean cancelled,CallbackInfo ci) {
        BowVisibilityControls.afterBridge(context);
    }
}
