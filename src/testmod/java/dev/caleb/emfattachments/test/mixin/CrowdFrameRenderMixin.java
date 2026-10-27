package dev.caleb.emfattachments.test.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.CrowdFrameSequence;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public abstract class CrowdFrameRenderMixin {
    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("RETURN"), require = 1)
    private void emfattachments$crowdReturned(AbstractClientPlayer entity, float yaw, float delta, PoseStack poses,
                                              MultiBufferSource buffers, int light, CallbackInfo ci) {
        CrowdFrameSequence.playerRendered(entity);
    }
}
