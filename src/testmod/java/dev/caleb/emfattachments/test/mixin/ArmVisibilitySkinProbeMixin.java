package dev.caleb.emfattachments.test.mixin;
import dev.caleb.emfattachments.test.ArmVisibilityMatrixProbe;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(AbstractClientPlayer.class)
public abstract class ArmVisibilitySkinProbeMixin {
    @Inject(method="getSkin",at=@At("RETURN"),cancellable=true)
    private void emfattachments$matrixSkin(CallbackInfoReturnable<PlayerSkin> cir){cir.setReturnValue(ArmVisibilityMatrixProbe.skin((AbstractClientPlayer)(Object)this,cir.getReturnValue()));}
}
