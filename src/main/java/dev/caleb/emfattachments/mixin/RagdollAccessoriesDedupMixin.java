package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.RagdollAccessoryDrawScope;
import dev.caleb.emfattachments.client.RagdollAccessoryArmScope;
import dev.caleb.emfattachments.client.RagdollAccessoryVisibility;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "dev.leo.sableplayerragdoll.neoforge.client.AccessoriesRenderHelper", remap = false)
public abstract class RagdollAccessoriesDedupMixin {
    @WrapOperation(method = "renderFromStored",
            at = @At(value = "INVOKE", target = "Lio/wispforest/accessories/api/client/AccessoryRenderer;render(Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"), require = 1)
    private static void emfattachments$visibleStoredAccessory(AccessoryRenderer renderer, ItemStack stack,
            SlotReference reference, PoseStack pose, EntityModel<?> model, MultiBufferSource buffers,
            int light, float walk, float amount, float partial, float age, float yaw, float pitch, Operation<Void> original) {
        if (!RagdollAccessoryVisibility.shouldRenderStored(renderer, reference)) return;
        emfattachments$drawAccessory(renderer, stack, reference, pose, model, buffers, light, walk, amount, partial, age, yaw, pitch, original);
    }

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE", target = "Lio/wispforest/accessories/api/client/AccessoryRenderer;render(Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"), require = 1)
    private static void emfattachments$recordCompletedAccessory(AccessoryRenderer renderer, ItemStack stack,
            SlotReference reference, PoseStack pose, EntityModel<?> model, MultiBufferSource buffers,
            int light, float walk, float amount, float partial, float age, float yaw, float pitch, Operation<Void> original) {
        // This path already checked the native renderer's visibility policy.
        emfattachments$drawAccessory(renderer, stack, reference, pose, model, buffers, light, walk, amount, partial, age, yaw, pitch, original);
    }

    private static void emfattachments$drawAccessory(AccessoryRenderer renderer, ItemStack stack,
            SlotReference reference, PoseStack pose, EntityModel<?> model, MultiBufferSource buffers,
            int light, float walk, float amount, float partial, float age, float yaw, float pitch, Operation<Void> original) {
        try (var arm = RagdollAccessoryArmScope.openCurrentPart()) {
            original.call(renderer, stack, reference, pose, model, buffers, light, walk, amount, partial, age, yaw, pitch);
        }
        // A failing renderer is swallowed by Sable's helper. Recording only
        // after successful completion leaves its Curios fallback available.
        RagdollAccessoryDrawScope.completed(reference, stack);
    }
}
