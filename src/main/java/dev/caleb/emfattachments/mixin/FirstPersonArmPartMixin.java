package dev.caleb.emfattachments.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.FirstPersonArmGeometry;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
public abstract class FirstPersonArmPartMixin {
    @Inject(method = "copyFrom", at = @At("RETURN"), require = 1)
    private void emfattachments$copyGeometryDecision(ModelPart source, CallbackInfo ci) {
        FirstPersonArmGeometry.copied(source, (ModelPart) (Object) this);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void emfattachments$omitHiddenArmGeometry(PoseStack poses, VertexConsumer vertices,
            int light, int overlay, int color, CallbackInfo ci) {
        if (FirstPersonArmGeometry.hides((ModelPart) (Object) this)) ci.cancel();
    }
}
