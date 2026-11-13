package dev.caleb.emfattachments.test.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.OverlapCombatProbe;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlayerRenderer.class, priority = 1500)
public abstract class OverlapCombatPalProbeMixin {
    @TargetHandler(mixin = "dev.kosmx.playerAnim.mixin.PlayerRendererMixin", name = "applyBodyTransforms")
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"), require = 1)
    private void emfattachments$overlapBeforePal(AbstractClientPlayer player, PoseStack pose, float age, float yaw,
            float partial, float scale, CallbackInfo originalCi, CallbackInfo ci) { OverlapCombatProbe.palBefore(player, pose); }
    @TargetHandler(mixin = "dev.kosmx.playerAnim.mixin.PlayerRendererMixin", name = "applyBodyTransforms")
    @Inject(method = "@MixinSquared:Handler", at = @At("RETURN"), require = 1)
    private void emfattachments$overlapAfterPal(AbstractClientPlayer player, PoseStack pose, float age, float yaw,
            float partial, float scale, CallbackInfo originalCi, CallbackInfo ci) { OverlapCombatProbe.palAfter(player, pose); }
}
