package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.FirstPersonArmGeometry;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LivingEntityRenderer.class)
public abstract class FirstPersonArmScopeMixin {
    @Shadow public abstract EntityModel<?> getModel();

    @WrapMethod(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void emfattachments$scopeArmGeometry(LivingEntity entity, float yaw, float partial,
            PoseStack poses, MultiBufferSource buffers, int light, Operation<Void> original) {
        try (var ignored = FirstPersonArmGeometry.open(entity, getModel(), partial)) {
            original.call(entity, yaw, partial, poses, buffers, light);
        }
    }
}
