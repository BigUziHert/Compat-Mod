package dev.caleb.emfattachments.test.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.NativeHandAccessoryProbe;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
public abstract class NativeHandAccessoryPartProbeMixin {
    @Inject(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",at=@At("HEAD"),require=1)
    private void emfattachments$nativeHandPart(PoseStack poses,VertexConsumer buffer,int light,int overlay,int color,CallbackInfo ci) { NativeHandAccessoryProbe.part((ModelPart)(Object)this); }
}
