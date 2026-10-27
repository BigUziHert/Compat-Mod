package dev.caleb.emfattachments.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.RagdollAccessoryArmScope;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Artifacts re-enables the chosen arm, overriding Sable's per-part visibility. */
@Mixin(targets = "artifacts.client.item.model.ArmsModel", remap = false)
public abstract class RagdollArtifactArmMixin {
    @Inject(method = "renderArm", at = @At("HEAD"), cancellable = true)
    private void emfattachments$onlyPhysicalArm(HumanoidArm selectedArm, PoseStack pose,
            VertexConsumer vertices, int light, int overlay, int color, CallbackInfo ci) {
        if (RagdollAccessoryArmScope.hides(selectedArm)) ci.cancel();
    }
}
