package dev.caleb.emfattachments.mixin;

import artifacts.client.item.renderer.GloveArtifactRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.ArtifactsFirstPersonArmPose;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Accessories calls this after both the actual first-person skin and sleeve draws. */
@Mixin(targets = "artifacts.integration.accessories.AccessoriesRenderingHandler$ArtifactAccessoryRenderer", remap = false)
public abstract class ArtifactsFirstPersonArmSourceMixin {
    @WrapOperation(
            method = "renderOnFirstPerson",
            at = @At(value = "INVOKE", target = "Lartifacts/client/item/renderer/GloveArtifactRenderer;renderFirstPersonArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/world/entity/HumanoidArm;Z)V"),
            require = 1)
    private void emfattachments$captureRenderedArm(
            GloveArtifactRenderer renderer, PoseStack poseStack, MultiBufferSource buffers,
            int light, AbstractClientPlayer player, HumanoidArm side, boolean foil,
            Operation<Void> original, @Local(argsOnly = true) EntityModel<?> suppliedModel) {
        ArtifactsFirstPersonArmPose.withSource(player, side, suppliedModel,
                () -> original.call(renderer, poseStack, buffers, light, player, side, foil));
    }
}
