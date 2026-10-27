package dev.caleb.emfattachments.test.mixin;
import dev.caleb.emfattachments.test.ArmVisibilityMatrixProbe;
import net.minecraft.client.model.HumanoidModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(HumanoidModel.class)
public abstract class ArmVisibilityCopyProbeMixin {
    @Inject(method="copyPropertiesTo(Lnet/minecraft/client/model/HumanoidModel;)V",at=@At("RETURN"))
    private void emfattachments$matrixCopied(HumanoidModel<?> model,CallbackInfo ci){ArmVisibilityMatrixProbe.copied((HumanoidModel<?>)(Object)this,model);}
}
