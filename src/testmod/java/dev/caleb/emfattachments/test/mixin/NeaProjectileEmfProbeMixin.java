package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.NeaProjectilePoseProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import strm.emfcompat.core.EMFCompatAnimationHook;
import traben.entity_model_features.EMFAnimationApi.EMFAnimationHook.AnimationContext;
import traben.entity_model_features.models.animation.state.EMFBipedPose;
import net.minecraft.client.model.HumanoidModel;

@Mixin(value=EMFCompatAnimationHook.class,remap=false)
public abstract class NeaProjectileEmfProbeMixin {
    @Inject(method="onAnimationEnd",at=@At("HEAD"),require=1)
    private void emfattachments$before(AnimationContext context,boolean cancelled,CallbackInfo ci) {
        NeaProjectilePoseProbe.emf(context,false);
    }
    @Inject(method="onAnimationEnd",at=@At("RETURN"),require=1)
    private void emfattachments$after(AnimationContext context,boolean cancelled,CallbackInfo ci) {
        NeaProjectilePoseProbe.emf(context,true);
    }
    @Inject(method="onBipedPoseCopyEnd",at=@At("HEAD"),require=1)
    private void emfattachments$armorBefore(EMFBipedPose pose,HumanoidModel<?> model,boolean cancelled,CallbackInfo ci) {
        NeaProjectilePoseProbe.armor(model,false);
    }
    @Inject(method="onBipedPoseCopyEnd",at=@At("RETURN"),require=1)
    private void emfattachments$armorAfter(EMFBipedPose pose,HumanoidModel<?> model,boolean cancelled,CallbackInfo ci) {
        NeaProjectilePoseProbe.armor(model,true);
    }
}
