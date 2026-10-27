package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Attaches the scarf's separate geometry to the torso while retaining its authored offsets. */
@Mixin(targets = "io.github.razordevs.deep_aether.client.renderer.accessory.FloatyScarfRenderer", remap = false)
public abstract class DeepAetherScarfMixin {
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lio/wispforest/accessories/api/client/AccessoryRenderer;followBodyRotations(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/model/HumanoidModel;)V"),
            require = 1)
    private void emfattachments$anchorScarf(
            LivingEntity entity, HumanoidModel<LivingEntity> scarf, Operation<Void> original,
            @Local(argsOnly = true) PoseStack matrices,
            @Local(argsOnly = true) EntityModel<?> parent) {
        original.call(entity, scarf);
        // ScarfModel draws head2 and body_0..3, rather than the humanoid head/body
        // that followBodyRotations copies. Their offsets belong to the torso's
        // local space, so move the entire scarf instead of overwriting its parts.
        if (parent instanceof HumanoidModel<?> humanoid) {
            humanoid.body.translateAndRotate(matrices);
        }
    }

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V"),
            require = 1)
    private void emfattachments$avoidSecondCrouchOffset(
            PoseStack matrices, double x, double y, double z, Operation<Void> original,
            @Local(argsOnly = true) EntityModel<?> parent) {
        if (!(parent instanceof HumanoidModel<?>)) {
            original.call(matrices, x, y, z);
        }
    }
}
