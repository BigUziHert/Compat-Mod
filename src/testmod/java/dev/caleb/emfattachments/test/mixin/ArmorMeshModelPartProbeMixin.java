package dev.caleb.emfattachments.test.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.ArmorMeshProbe;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
public abstract class ArmorMeshModelPartProbeMixin {
    @Inject(method="translateAndRotate",at=@At("RETURN"),require=1)
    private void emfattachments$matrix(PoseStack matrices,CallbackInfo ci){ArmorMeshProbe.transformed((ModelPart)(Object)this,matrices);}
    @Inject(method="compile",at=@At("HEAD"),require=1)
    private void emfattachments$compile(PoseStack.Pose pose,VertexConsumer vertices,int light,int overlay,int color,CallbackInfo ci){ArmorMeshProbe.compiling((ModelPart)(Object)this,pose);}
}
