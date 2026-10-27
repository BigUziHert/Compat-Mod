package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps the Curios goggles on the already animated player head. */
@Mixin(targets = "com.titammods.aeronautics_curios_compat.client.AviatorsGogglesCurioRenderer", remap = false)
public abstract class AviatorGogglesMixin {
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/HumanoidModel;setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V"),
            require = 1)
    private void emfattachments$restoreAnimatedHead(
            HumanoidModel<LivingEntity> armor, LivingEntity entity,
            float limbSwing, float limbSwingAmount, float ageInTicks,
            float netHeadYaw, float headPitch, Operation<Void> original,
            @Local(argsOnly = true) RenderLayerParent<?, ?> parent) {
        original.call(armor, entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        // The original renderer copies the parent pose BEFORE setupAnim, which
        // then overwrites it. Restore the final animated transform immediately
        // afterwards, including translation and scale as well as rotation.
        if (parent.getModel() instanceof HumanoidModel<?> player) {
            armor.head.copyFrom(player.head);
            armor.hat.copyFrom(player.head);
        }
    }
}
