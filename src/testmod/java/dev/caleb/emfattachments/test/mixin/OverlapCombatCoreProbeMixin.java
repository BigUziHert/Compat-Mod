package dev.caleb.emfattachments.test.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.OverlapCombatProbe;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LivingEntityRenderer.class, priority = 1500)
public abstract class OverlapCombatCoreProbeMixin {
    @TargetHandler(mixin = "strm.emfcompat.core.mixin.SetupRotationsMixin", name = "emfcompat$beforeSetupRotations")
    @Inject(method = "@MixinSquared:Handler", at = @At("RETURN"), require = 1)
    private void emfattachments$overlapBeforeCore(LivingEntity player, float yaw, float partial, PoseStack pose,
            MultiBufferSource buffers, int light, CallbackInfo originalCi, CallbackInfo ci) { OverlapCombatProbe.coreBefore(player, pose); }
    @TargetHandler(mixin = "strm.emfcompat.core.mixin.SetupRotationsMixin", name = "emfcompat$afterSetupRotations")
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"), require = 1)
    private void emfattachments$overlapCoreEntry(LivingEntity player, float yaw, float partial, PoseStack pose,
            MultiBufferSource buffers, int light, CallbackInfo originalCi, CallbackInfo ci) { OverlapCombatProbe.coreEntry(player, pose); }
    @TargetHandler(mixin = "strm.emfcompat.core.mixin.SetupRotationsMixin", name = "emfcompat$afterSetupRotations")
    @Inject(method = "@MixinSquared:Handler", at = @At("RETURN"), require = 1)
    private void emfattachments$overlapCoreExit(LivingEntity player, float yaw, float partial, PoseStack pose,
            MultiBufferSource buffers, int light, CallbackInfo originalCi, CallbackInfo ci) { OverlapCombatProbe.coreAfter(player, pose); }
}
