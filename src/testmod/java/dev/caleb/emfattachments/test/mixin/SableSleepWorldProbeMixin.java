package dev.caleb.emfattachments.test.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.SableSleepSequence;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(LevelRenderer.class)
public abstract class SableSleepWorldProbeMixin {
    @WrapMethod(method="renderEntity")
    private void sleepWitness(Entity entity,double x,double y,double z,float delta,PoseStack poses,MultiBufferSource buffers,Operation<Void> original){
        try(var scope=SableSleepSequence.enter(entity,poses)){original.call(entity,x,y,z,delta,poses,buffers);}
    }
}
