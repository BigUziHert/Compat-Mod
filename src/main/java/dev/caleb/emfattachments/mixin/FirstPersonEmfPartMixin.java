package dev.caleb.emfattachments.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import dev.tr7zw.firstperson.versionless.mixinbase.ModelPartBase;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.entity_model_features.models.parts.EMFModelPart;
import traben.entity_model_features.models.animation.state.EMFState;

/** Preserves FirstPerson's visibility decision after EMF has evaluated the animation. */
@Mixin(value = EMFModelPart.class, remap = false)
public abstract class FirstPersonEmfPartMixin {
    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void emfattachments$preserveHiddenPart(
            PoseStack poseStack, VertexConsumer vertices, int light, int overlay, int color,
            CallbackInfo ci) {
        // EMFModelPartWithState has already run oneTimeRunnable()/animate() here.
        // Those may overwrite ModelPart.visible and z, but not FPM's hidden marker.
        // Checking the marker at the draw boundary also hides the custom face and
        // other children of the head without changing their saved visibility.
        var renderState = EMFState.state();
        if (renderState != null && renderState.emfEntity() instanceof LivingEntity entity
                && FirstPersonRenderPolicy.isLocalBody(entity)
                && (Object) this instanceof ModelPartBase part
                && part.isHidden()) {
            ci.cancel();
        }
    }
}
