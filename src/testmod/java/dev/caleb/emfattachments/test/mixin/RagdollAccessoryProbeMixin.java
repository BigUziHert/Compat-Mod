package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.RagdollRenderCounters;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "dev.leo.sableplayerragdoll.neoforge.client.AccessoriesRenderHelper", remap = false)
public abstract class RagdollAccessoryProbeMixin {
    @WrapOperation(method = {"renderFromStored", "render"}, at = @At(value = "INVOKE", target = "Lio/wispforest/accessories/api/client/AccessoryRenderer;render(Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"))
    private static void emfattachmentsTest$item(AccessoryRenderer renderer, ItemStack stack, SlotReference ref,
            PoseStack pose, EntityModel<?> model, MultiBufferSource buffers, int light, float walk, float amount,
            float partial, float age, float yaw, float pitch, Operation<Void> original) {
        String key = "accessories/" + ref.slotName() + ":" + BuiltInRegistries.ITEM.getKey(stack.getItem());
        RagdollRenderCounters.item("accessories/" + ref.slotName(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        original.call(renderer, stack, ref, pose, model, RagdollRenderCounters.buffers(key, buffers), light, walk, amount, partial, age, yaw, pitch);
    }
}
