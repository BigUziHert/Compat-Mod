package dev.caleb.emfattachments.mixin;

import com.aetherteam.aether.client.renderer.accessory.model.GlovesModel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.AetherNativeAttackGloves;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.aetherteam.aether.client.renderer.accessory.GlovesRenderer", remap = false)
public abstract class AetherNativeAttackGloveVisibilityMixin {
    @WrapOperation(method = "render",
            at = @At(value = "INVOKE", target = "Lcom/aetherteam/aether/client/renderer/accessory/model/GlovesModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
            require = 1)
    private void emfattachments$nativeBaseVisibility(GlovesModel gloves, PoseStack poses,
            VertexConsumer vertices, int light, int overlay, int color, Operation<Void> original) {
        if (!AetherNativeAttackGloves.hasVisibilityOverride()) {
            original.call(gloves, poses, vertices, light, overlay, color);
            return;
        }
        AetherNativeAttackGloves.renderVisibleArms(gloves,
                () -> original.call(gloves, poses, vertices, light, overlay, color));
    }

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE", target = "Lcom/aetherteam/aether/client/renderer/accessory/model/GlovesModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"),
            require = 2)
    private void emfattachments$nativeOverlayVisibility(GlovesModel gloves, PoseStack poses,
            VertexConsumer vertices, int light, int overlay, Operation<Void> original) {
        if (!AetherNativeAttackGloves.hasVisibilityOverride()) {
            original.call(gloves, poses, vertices, light, overlay);
            return;
        }
        AetherNativeAttackGloves.renderVisibleArms(gloves,
                () -> original.call(gloves, poses, vertices, light, overlay));
    }
}
