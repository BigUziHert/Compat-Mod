package dev.caleb.emfattachments.test.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.ArmVisibilityMatrixProbe;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ModelPart.class)
public abstract class ArmVisibilityPartProbeMixin {
    @WrapMethod(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V")
    private void emfattachments$matrixAttempt(PoseStack poses,VertexConsumer vertices,int light,int overlay,int color,Operation<Void> original){ArmVisibilityMatrixProbe.attempted((ModelPart)(Object)this);original.call(poses,vertices,light,overlay,color);}
    @Inject(method="compile",at=@At("HEAD"))
    private void emfattachments$matrixCompile(PoseStack.Pose pose,VertexConsumer vertices,int light,int overlay,int color,CallbackInfo ci){ArmVisibilityMatrixProbe.compiled((ModelPart)(Object)this);}
}
