package dev.caleb.emfattachments.test.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.SableSleepSequence;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(targets="artifacts.client.item.renderer.GenericArtifactRenderer",remap=false)
public abstract class SableSleepArtifactProbeMixin {
    @WrapMethod(method="render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;ILcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V")
    private void sleepArtifact(ItemStack stack,LivingEntity entity,int slot,PoseStack poses,MultiBufferSource buffers,int light,float limb,float amount,float delta,float age,float yaw,float pitch,Operation<Void> original){
        SableSleepSequence.artifact(stack,entity,poses,buffers,counted->original.call(stack,entity,slot,poses,counted,light,limb,amount,delta,age,yaw,pitch));
    }
}
