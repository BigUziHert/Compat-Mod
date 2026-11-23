package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import traben.entity_model_features.models.IEMFModel;
import traben.entity_model_features.models.animation.state.EMFState;

/** Keeps visible equipment posed when invisibility skips the player's base model draw. */
@Mixin(LivingEntityRenderer.class)
public abstract class InvisiblePlayerLayerPoseMixin {
    @Shadow protected EntityModel<?> model;

    @ModifyExpressionValue(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;"
                    + "getRenderType(Lnet/minecraft/world/entity/LivingEntity;ZZZ)Lnet/minecraft/client/renderer/RenderType;"),
            require = 1, allow = 1)
    private RenderType emfattachments$poseInvisiblePlayerLayers(RenderType type,
            @Local(argsOnly = true) LivingEntity entity) {
        if (type != null || !(entity instanceof AbstractClientPlayer player) || !player.isInvisible()
                || player.isSpectator() || !EMFState.isMainPhase || !(model instanceof IEMFModel emfModel)
                || !emfModel.emf$isEMFModel()) return type;
        var state = EMFState.state();
        if (state == null || state.emfEntity() != player || state.isFirstPersonHand()) return type;
        var root = emfModel.emf$getEMFRootModel();
        if (root != null && root.containsCustomAnims) {
            // This is the pose-only part of EMFModelPartWithState.render, before layers copy the parent.
            root.oneTimeRunnable();
            root.animate();
        }
        return type;
    }
}
