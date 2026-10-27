package dev.caleb.emfattachments.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.FirstPersonArmGeometry;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.entity_model_features.models.parts.EMFModelPart;

/** EMF overrides ModelPart.render after its animation/state setup has run. */
@Mixin(value = EMFModelPart.class, remap = false)
public abstract class FirstPersonEmfArmPartMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void emfattachments$omitHiddenEmfArmGeometry(PoseStack poses, VertexConsumer vertices,
            int light, int overlay, int color, CallbackInfo ci) {
        if (FirstPersonArmGeometry.hides((ModelPart) (Object) this)) ci.cancel();
    }
}
