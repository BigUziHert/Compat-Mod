package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.ArtificeBodyMaskProbe;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ModelPart.class)
public abstract class ArtificeBodyGeometryProbeMixin{
    @WrapMethod(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V")
    private void count(PoseStack poses,VertexConsumer vertices,int light,int overlay,int color,Operation<Void> original){
        try(var draw=ArtificeBodyMaskProbe.draw((ModelPart)(Object)this,vertices)){
            original.call(poses,draw==null?vertices:draw,light,overlay,color);
        }
    }
}
