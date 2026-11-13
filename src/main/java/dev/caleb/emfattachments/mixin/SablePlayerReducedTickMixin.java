package dev.caleb.emfattachments.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import dev.caleb.emfattachments.client.SableReducedPlayerTick;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restore only missing Sable work before the native reduced tick performs remote interpolation. */
@Mixin(value = ClientLevel.class, priority = 1500)
public abstract class SablePlayerReducedTickMixin {
    @TargetHandler(mixin = "dev.tr7zw.entityculling.mixin.ClientWorldMixin", name = "basicTick")
    @Inject(method = "@MixinSquared:Handler", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;aiStep()V"), require = 1, expect = 1, allow = 1)
    private void emfattachments$maintainSableBeforeReducedAiStep(Entity entity, CallbackInfo ci) {
        SableReducedPlayerTick.beforeAiStep(entity);
    }
}
