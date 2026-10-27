package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.NecklacePoseProbe;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "artifacts.client.item.renderer.ArtifactRenderer", remap = false)
public interface ArtifactsNecklaceCopyProbeMixin {
    @Inject(method = "followBodyRotations", at = @At("RETURN"), require = 1)
    private static void emfattachments$copy(LivingEntity entity, HumanoidModel<LivingEntity> model, CallbackInfo ci) {
        NecklacePoseProbe.copied(entity, model);
    }
}
