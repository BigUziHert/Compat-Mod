package dev.caleb.emfattachments.test.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.ArmorMeshProbe;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.entity_model_features.models.parts.EMFModelPart;

@Mixin(value=EMFModelPart.class,remap=false)
public abstract class ArmorMeshEmfCompileProbeMixin {
    @Inject(method="compile",at=@At("HEAD"),require=1)
    private void emfattachments$compile(PoseStack.Pose pose,VertexConsumer vertices,int light,int overlay,int color,CallbackInfo ci){ArmorMeshProbe.compiling((ModelPart)(Object)this,pose);}
}
