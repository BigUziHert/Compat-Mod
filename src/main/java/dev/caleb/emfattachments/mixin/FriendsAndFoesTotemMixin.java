package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Replaces the totem renderer's vanilla crouch approximation with its actual torso anchor. */
@Mixin(targets = "com.faboslav.friendsandfoes.neoforge.modcompat.curios.CuriosTotemRenderer", remap = false)
public abstract class FriendsAndFoesTotemMixin {
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Ltop/theillusivec4/curios/api/client/ICurioRenderer;translateIfSneaking(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/LivingEntity;)V"),
            require = 1)
    private void emfattachments$followTorso(
            PoseStack matrices, LivingEntity entity, Operation<Void> original,
            @Local(argsOnly = true) RenderLayerParent<?, ?> parent) {
        if (parent.getModel() instanceof HumanoidModel<?> humanoid) {
            humanoid.body.translateAndRotate(matrices);
        } else {
            original.call(matrices, entity);
        }
    }

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Ltop/theillusivec4/curios/api/client/ICurioRenderer;rotateIfSneaking(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/LivingEntity;)V"),
            require = 1)
    private void emfattachments$avoidSecondCrouchRotation(
            PoseStack matrices, LivingEntity entity, Operation<Void> original,
            @Local(argsOnly = true) RenderLayerParent<?, ?> parent) {
        if (!(parent.getModel() instanceof HumanoidModel<?>)) {
            original.call(matrices, entity);
        }
    }
}
