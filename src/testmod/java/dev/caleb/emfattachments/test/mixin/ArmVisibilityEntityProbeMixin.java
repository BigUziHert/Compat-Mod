package dev.caleb.emfattachments.test.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.ArmVisibilityMatrixProbe;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
@Mixin(LivingEntityRenderer.class)
public abstract class ArmVisibilityEntityProbeMixin {
    @Shadow public abstract EntityModel<?> getModel();
    @WrapMethod(method="render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void emfattachments$matrixEntity(LivingEntity entity,float yaw,float partial,PoseStack poses,MultiBufferSource buffers,int light,Operation<Void> original){
        try(var scope=ArmVisibilityMatrixProbe.entity(entity,getModel())){original.call(entity,yaw,partial,poses,buffers,light);}
    }
}
