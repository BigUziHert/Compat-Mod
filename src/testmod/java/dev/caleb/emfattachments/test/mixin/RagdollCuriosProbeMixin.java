package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.RagdollRenderCounters;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

@Mixin(targets = "dev.leo.sableplayerragdoll.neoforge.client.CuriosRenderHelper", remap = false)
public abstract class RagdollCuriosProbeMixin {
    @WrapOperation(method = {"lambda$renderFromStored$0", "lambda$render$1"}, at = @At(value = "INVOKE", target = "Ltop/theillusivec4/curios/api/client/ICurioRenderer;render(Lnet/minecraft/world/item/ItemStack;Ltop/theillusivec4/curios/api/SlotContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/entity/RenderLayerParent;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"))
    private static void emfattachmentsTest$item(ICurioRenderer renderer, ItemStack stack, SlotContext ref,
            PoseStack pose, RenderLayerParent<?, ?> parent, MultiBufferSource buffers, int light, float walk, float amount,
            float partial, float age, float yaw, float pitch, Operation<Void> original) {
        String key = "curios/" + ref.identifier() + ":" + BuiltInRegistries.ITEM.getKey(stack.getItem());
        RagdollRenderCounters.item("curios/" + ref.identifier(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        original.call(renderer, stack, ref, pose, parent, RagdollRenderCounters.buffers(key, buffers), light, walk, amount, partial, age, yaw, pitch);
    }
}
