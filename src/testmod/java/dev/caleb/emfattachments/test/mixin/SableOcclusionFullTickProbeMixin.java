package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.SableOcclusionSequence;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class SableOcclusionFullTickProbeMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void emfattachments$fullEntityTick(CallbackInfo ci) {
        SableOcclusionSequence.fullTick((Entity)(Object)this);
    }
}
