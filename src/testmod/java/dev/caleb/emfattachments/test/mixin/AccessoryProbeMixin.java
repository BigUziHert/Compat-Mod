package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.AttachmentTestMod;
import dev.caleb.emfattachments.test.ModelState;
import dev.caleb.emfattachments.test.CountingVertexConsumer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.IdentityHashMap;
import java.util.Map;

/** Instruments actual accessory calls, including errors swallowed by Accessories' layer. */
@Mixin(targets = "io.wispforest.accessories.client.AccessoriesRenderLayer", remap = false)
public abstract class AccessoryProbeMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lio/wispforest/accessories/api/client/AccessoryRenderer;render(Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V", remap = false))
    private void emfattachmentsTest$observe(AccessoryRenderer renderer, ItemStack stack, SlotReference reference,
            PoseStack matrices, EntityModel<?> model, MultiBufferSource buffers, int light,
            float limbSwing, float limbSwingAmount, float partialTicks, float age, float yaw, float pitch,
            Operation<Void> original) {
        var result = AttachmentTestMod.current;
        if (result == null) {
            original.call(renderer, stack, reference, matrices, model, buffers, light, limbSwing, limbSwingAmount, partialTicks, age, yaw, pitch);
            return;
        }
        result.rendererCalls++;
        Map<ModelPart, float[]> before = new IdentityHashMap<>();
        if (model instanceof HumanoidModel<?> humanoid) {
            for (ModelPart root : new ModelPart[]{humanoid.head, humanoid.hat, humanoid.body, humanoid.leftArm, humanoid.rightArm, humanoid.leftLeg, humanoid.rightLeg})
                root.getAllParts().forEach(part -> before.put(part, ModelState.state(part)));
        }
        MultiBufferSource counted = type -> new CountingVertexConsumer(buffers.getBuffer(type), result);
        try {
            original.call(renderer, stack, reference, matrices, model, counted, light, limbSwing, limbSwingAmount, partialTicks, age, yaw, pitch);
        } catch (Throwable error) {
            result.exception = error.toString();
            throw error;
        } finally {
            for (var entry : before.entrySet()) {
                if (!ModelState.same(entry.getValue(), ModelState.state(entry.getKey()))) {
                    result.mutatedParts++;
                    if (result.mutations.size() < 8) result.mutations.add(entry.getKey().getClass().getName() + ": " + java.util.Arrays.toString(entry.getValue()) + " -> " + java.util.Arrays.toString(ModelState.state(entry.getKey())));
                }
            }
        }
    }

}
