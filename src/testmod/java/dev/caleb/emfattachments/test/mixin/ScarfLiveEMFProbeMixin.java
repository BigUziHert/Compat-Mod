package dev.caleb.emfattachments.test.mixin;
import dev.caleb.emfattachments.test.ScarfRenderTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.entity_model_features.models.parts.EMFModelPartRoot;

@Mixin(value=EMFModelPartRoot.class,remap=false)
public abstract class ScarfLiveEMFProbeMixin {
    @Inject(method="animate",at=@At("HEAD"))
    private void emfattachments$animate(CallbackInfo ci){ScarfRenderTrace.root((EMFModelPartRoot)(Object)this);}
}
