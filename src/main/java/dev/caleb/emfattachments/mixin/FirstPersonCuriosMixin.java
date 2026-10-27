package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/** Native Curios also draws cosmetic stacks when persistent visibility is false. */
@Mixin(targets = "top.theillusivec4.curios.client.render.CuriosLayer", remap = false)
public abstract class FirstPersonCuriosMixin {
    @WrapOperation(
            method = "lambda$render$0",
            at = @At(value = "INVOKE", target = "Ltop/theillusivec4/curios/api/client/ICurioRenderer;render(Lnet/minecraft/world/item/ItemStack;Ltop/theillusivec4/curios/api/SlotContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/entity/RenderLayerParent;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"),
            require = 1)
    private void emfattachments$renderVisibleCurio(ICurioRenderer renderer, ItemStack stack,
            SlotContext context, PoseStack matrices, RenderLayerParent<?, ?> parent,
            MultiBufferSource buffers, int light, float limbSwing, float limbSwingAmount,
            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
            Operation<Void> original) {
        if (context != null && FirstPersonRenderPolicy.shouldHide(context.entity(), context.identifier(), stack)) return;
        original.call(renderer, stack, context, matrices, parent, buffers, light,
                limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
    }
}
