package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Artifacts' double-resolution necklaces attached to the copied torso pose. */
@Mixin(targets = "artifacts.client.item.model.NecklaceModel", remap = false)
public abstract class ArtifactsNecklacePoseMixin extends HumanoidModel<LivingEntity> {
    protected ArtifactsNecklacePoseMixin(ModelPart root) {
        super(root);
    }

    @WrapOperation(
            method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V", ordinal = 1),
            require = 1)
    private void emfattachments$preserveTorsoTranslation(
            PoseStack poseStack, float x, float y, float z, Operation<Void> original) {
        // The native uniform 0.51 scale belongs to the authored geometry. It
        // must not also halve the animated body's copied pivot translation.
        // T((1-s)p) S(s) T(p) R = T(p) R S(s), without changing model state.
        poseStack.translate(body.x * (1.0f - x) / 16.0f,
                body.y * (1.0f - y) / 16.0f,
                body.z * (1.0f - z) / 16.0f);
        original.call(poseStack, x, y, z);
    }
}
