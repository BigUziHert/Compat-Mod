package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.NecklacePoseProbe;
import net.minecraft.client.model.HumanoidModel;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "artifacts.client.item.model.NecklaceModel", remap = false)
public abstract class ArtifactsNecklaceDrawProbeMixin {
    @WrapMethod(method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V")
    private void emfattachments$draw(PoseStack matrices, VertexConsumer vertices, int light, int overlay, int color, Operation<Void> original) {
        try (var scope = NecklacePoseProbe.begin((HumanoidModel<?>)(Object)this, matrices)) {
            original.call(matrices, vertices, light, overlay, color);
        }
    }
}
