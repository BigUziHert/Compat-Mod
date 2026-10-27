package dev.caleb.emfattachments.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.RagdollAccessoryArmScope;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Each of these native models explicitly re-enables its selected arm too. */
@Mixin(targets = {
        "com.github.L_Ender.cataclysm.client.model.item.CuriosModel.Chitin_Claw_Model",
        "com.github.L_Ender.cataclysm.client.model.item.CuriosModel.Sticky_Gloves_Model",
        "com.github.L_Ender.cataclysm.client.model.item.CuriosModel.Blazing_Grips_Model"
}, remap = false)
public abstract class RagdollCataclysmArmMixin {
    @Inject(method = "renderArm", at = @At("HEAD"), cancellable = true)
    private void emfattachments$onlyPhysicalArm(HumanoidArm selectedArm, PoseStack pose,
            VertexConsumer vertices, int light, int overlay, CallbackInfo ci) {
        if (RagdollAccessoryArmScope.hides(selectedArm)) ci.cancel();
    }
}
