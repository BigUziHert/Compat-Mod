package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.RagdollAccessoryDrawScope;
import dev.caleb.emfattachments.client.RagdollAccessoryArmScope;
import dev.caleb.emfattachments.client.RagdollAccessoryVisibility;
import dev.caleb.emfattachments.client.RagdollAccessoriesSlotRouting;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import io.wispforest.accessories_compat.curios.wrapper.CuriosConversionUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

@Mixin(targets = "dev.leo.sableplayerragdoll.neoforge.client.CuriosRenderHelper", remap = false)
public abstract class RagdollCuriosDedupMixin {
    @Inject(method = "slotBelongsToPart", at = @At("RETURN"), cancellable = true, require = 1)
    private static void emfattachments$canonicalHeadAndArmSlot(String slot, BodyPart bodyPart, CallbackInfoReturnable<Boolean> result) {
        String canonical = CuriosConversionUtils.slotConvertToA(slot);
        result.setReturnValue(RagdollAccessoryVisibility.routeCanonicalSlot(
                RagdollAccessoriesSlotRouting.resolveSlot(canonical), bodyPart, result.getReturnValue()));
    }

    @WrapOperation(method = "lambda$renderFromStored$0",
            at = @At(value = "INVOKE", target = "Ltop/theillusivec4/curios/api/client/ICurioRenderer;render(Lnet/minecraft/world/item/ItemStack;Ltop/theillusivec4/curios/api/SlotContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/entity/RenderLayerParent;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"), require = 1)
    private static void emfattachments$visibleStoredCurio(ICurioRenderer renderer, ItemStack stack,
            SlotContext context, PoseStack pose, RenderLayerParent<?, ?> parent, MultiBufferSource buffers,
            int light, float walk, float amount, float partial, float age, float yaw, float pitch, Operation<Void> original) {
        if (!RagdollAccessoryVisibility.shouldRenderStored(context, stack)) return;
        emfattachments$drawCurio(renderer, stack, context, pose, parent, buffers, light, walk, amount, partial, age, yaw, pitch, original);
    }

    @WrapOperation(method = "lambda$render$1",
            at = @At(value = "INVOKE", target = "Ltop/theillusivec4/curios/api/client/ICurioRenderer;render(Lnet/minecraft/world/item/ItemStack;Ltop/theillusivec4/curios/api/SlotContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/entity/RenderLayerParent;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"), require = 1)
    private static void emfattachments$skipBridgedDuplicate(ICurioRenderer renderer, ItemStack stack,
            SlotContext context, PoseStack pose, RenderLayerParent<?, ?> parent, MultiBufferSource buffers,
            int light, float walk, float amount, float partial, float age, float yaw, float pitch, Operation<Void> original) {
        emfattachments$drawCurio(renderer, stack, context, pose, parent, buffers, light, walk, amount, partial, age, yaw, pitch, original);
    }

    private static void emfattachments$drawCurio(ICurioRenderer renderer, ItemStack stack,
            SlotContext context, PoseStack pose, RenderLayerParent<?, ?> parent, MultiBufferSource buffers,
            int light, float walk, float amount, float partial, float age, float yaw, float pitch, Operation<Void> original) {
        if (RagdollAccessoryDrawScope.isDuplicate(context, stack)) return;
        try (var arm = RagdollAccessoryArmScope.openCurrentPart()) {
            original.call(renderer, stack, context, pose, parent, buffers, light, walk, amount, partial, age, yaw, pitch);
        }
    }
}
