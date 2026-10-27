package dev.caleb.emfattachments.perf.mixin;

import dev.caleb.emfattachments.perf.EquippedCrowdBenchmark;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class PerfEntityTickMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void emfattachmentsPerf$nativeFullTick(CallbackInfo ci) {
        EquippedCrowdBenchmark.entityFullyTicked((Entity)(Object)this);
    }
}
