package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.FirstPersonArmGeometry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Geometry hiding never reaches actual held items, including custom models. */
@Mixin(ItemInHandLayer.class)
public abstract class FirstPersonHeldItemGeometryMixin {
    @WrapMethod(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V")
    private void emfattachments$preserveHeldGeometry(PoseStack poses, MultiBufferSource buffers, int light,
            LivingEntity entity, float limbSwing, float limbAmount, float partial, float age, float yaw, float pitch,
            Operation<Void> original) {
        try (var ignored = FirstPersonArmGeometry.suspendForHeldItems()) {
            original.call(poses, buffers, light, entity, limbSwing, limbAmount, partial, age, yaw, pitch);
        }
    }
}
