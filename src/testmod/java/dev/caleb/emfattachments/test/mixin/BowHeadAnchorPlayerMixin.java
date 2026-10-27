package dev.caleb.emfattachments.test.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.BowHeadAnchorControl;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=PlayerRenderer.class,priority=1500)
public abstract class BowHeadAnchorPlayerMixin {
    @WrapMethod(method="render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void emfattachments$entity(AbstractClientPlayer entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light,Operation<Void> original) {
        BowHeadAnchorControl.render(entity,pose,()->original.call(entity,yaw,partial,pose,buffers,light));
    }
    @TargetHandler(mixin="dev.kosmx.playerAnim.mixin.PlayerRendererMixin",name="applyBodyTransforms")
    @Inject(method="@MixinSquared:Handler",at=@At("HEAD"),require=1)
    private void emfattachments$beforeRoot(AbstractClientPlayer entity,PoseStack pose,float age,float yaw,float partial,float scale,CallbackInfo originalCi,CallbackInfo ci) {
        BowHeadAnchorControl.beforeRoot(entity,pose);
    }
    @TargetHandler(mixin="dev.kosmx.playerAnim.mixin.PlayerRendererMixin",name="applyBodyTransforms")
    @Inject(method="@MixinSquared:Handler",at=@At("RETURN"),require=1)
    private void emfattachments$afterRoot(AbstractClientPlayer entity,PoseStack pose,float age,float yaw,float partial,float scale,CallbackInfo originalCi,CallbackInfo ci) {
        BowHeadAnchorControl.afterRoot(entity,pose);
    }
}
