package dev.caleb.emfattachments.test.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.NecklacePoseProbe;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
public abstract class NecklaceModelPartProbeMixin {
    @Inject(method = "translateAndRotate", at = @At("RETURN"), require = 1)
    private void emfattachments$matrix(PoseStack matrices, CallbackInfo ci) {
        NecklacePoseProbe.transformed((ModelPart)(Object)this, matrices);
    }
}
