package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Covers native Accessories, its Curios bridge, and the selected cosmetic stack. */
@Mixin(targets = "io.wispforest.accessories.client.AccessoriesRenderLayer", remap = false)
public abstract class FirstPersonAccessoriesMixin {
    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lio/wispforest/accessories/api/client/AccessoryRenderer;render(Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"),
            require = 1)
    private void emfattachments$renderVisibleAccessory(AccessoryRenderer renderer, ItemStack stack,
            SlotReference reference, PoseStack matrices, EntityModel<?> model,
            MultiBufferSource buffers, int light, float limbSwing, float limbSwingAmount,
            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
            Operation<Void> original) {
        if (reference != null && FirstPersonRenderPolicy.shouldHide(reference.entity(), reference.slotName(), stack)) return;
        original.call(renderer, stack, reference, matrices, model, buffers, light,
                limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
    }
}
