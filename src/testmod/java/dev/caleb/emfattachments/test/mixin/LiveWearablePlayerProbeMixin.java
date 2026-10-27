package dev.caleb.emfattachments.test.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.LiveWearableRenderState;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public abstract class LiveWearablePlayerProbeMixin {
    @Inject(method="render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",at=@At("HEAD"))
    private void emfattachments$liveWearableBegin(AbstractClientPlayer p,float yaw,float delta,PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci){LiveWearableRenderState.player(p);}
    @Inject(method="render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",at=@At("TAIL"))
    private void emfattachments$liveWearableEnd(AbstractClientPlayer p,float yaw,float delta,PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci){LiveWearableRenderState.pose(p,((PlayerRenderer)(Object)this).getModel());}
}
