package dev.caleb.emfattachments.mixin;

import artifacts.client.item.model.ArmsModel;
import artifacts.client.item.renderer.GloveArtifactRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.ArtificeFirstPersonAccessories;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = GloveArtifactRenderer.class, remap = false)
public abstract class ArtificeArtifactsFirstPersonPoseMixin {
    @WrapOperation(
            method = "renderFirstPersonArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/world/entity/HumanoidArm;Z)V",
            at = @At(value = "INVOKE", target = "Lartifacts/client/item/renderer/GloveArtifactRenderer;renderFirstPersonArm(Lartifacts/client/item/model/ArmsModel;Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IZZ)V"),
            require = 1)
    private void emfattachments$followGunBone(GloveArtifactRenderer renderer, ArmsModel model,
            ModelPart arm, PoseStack poses, MultiBufferSource buffers, int light,
            boolean slim, boolean foil, Operation<Void> original) {
        ArtificeFirstPersonAccessories.renderArm(arm,
                () -> original.call(renderer, model, arm, poses, buffers, light, slim, foil));
    }
}
