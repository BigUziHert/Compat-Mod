package dev.caleb.emfattachments.test.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.SableSleepSequence;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class SableSleepNameTagProbeMixin {
    @Inject(method="renderNameTag",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V",shift=At.Shift.AFTER),require=1)
    private void sleepNameTag(Entity entity,Component text,PoseStack poses,MultiBufferSource buffers,int light,float partialTick,CallbackInfo ci){
        SableSleepSequence.nameTag(entity,poses,text.getString());
    }
}
