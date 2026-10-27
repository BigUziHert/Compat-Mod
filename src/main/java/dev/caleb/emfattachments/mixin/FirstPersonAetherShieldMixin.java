package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.FirstPersonShieldVisibility;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.aetherteam.aether.client.renderer.accessory.ShieldOfRepulsionRenderer", remap = false)
public abstract class FirstPersonAetherShieldMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/HumanoidModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"), require = 1)
    private void emfattachments$followVisibleBodyParts(HumanoidModel<?> shield, PoseStack pose, VertexConsumer vertices,
            int light, int overlay, Operation<Void> original,
            @Local(argsOnly = true) SlotReference reference, @Local(argsOnly = true) EntityModel<?> parent) {
        FirstPersonShieldVisibility.render(shield, parent, reference.entity(), () -> original.call(shield, pose, vertices, light, overlay));
    }
}
