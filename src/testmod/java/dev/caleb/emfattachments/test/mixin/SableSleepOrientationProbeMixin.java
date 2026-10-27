package dev.caleb.emfattachments.test.mixin;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.SableSleepSequence;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LivingEntityRenderer.class)
public abstract class SableSleepOrientationProbeMixin {
    @Inject(method="render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",shift=At.Shift.AFTER),require=1)
    private void sleepOrientation(LivingEntity entity,float yaw,float delta,PoseStack poses,MultiBufferSource buffers,int light,CallbackInfo ci){SableSleepSequence.oriented(entity,poses);}
}
