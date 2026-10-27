package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.SableOcclusionSequence;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class SableOcclusionSkinProbeMixin {
    @Inject(method = "getRenderType", at = @At("RETURN"))
    private void emfattachments$ownedSkinDecision(LivingEntity entity, boolean bodyVisible, boolean translucent,
                                                boolean glowing, CallbackInfoReturnable<RenderType> cir) {
        SableOcclusionSequence.skinRenderType(entity, cir.getReturnValue());
    }
}
