package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.RagdollModelPartProbe;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import traben.entity_model_features.models.parts.EMFModelPartWithState;

@Mixin(value = EMFModelPartWithState.class, remap = false)
public abstract class RagdollEmfModelPartProbeMixin {
    @WrapMethod(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V")
    private void emfattachmentsTest$nativeVisibleParts(PoseStack pose, VertexConsumer vertices, int light, int overlay, int color, Operation<Void> original) {
        ModelPart part = (ModelPart) (Object) this;
        String key = RagdollModelPartProbe.key(part);
        RagdollModelPartProbe.state(key, "before_animate", part);
        try { original.call(pose, RagdollModelPartProbe.vertices(key, vertices), light, overlay, color); }
        finally { RagdollModelPartProbe.state(key, "after_animate", part); }
    }
}
