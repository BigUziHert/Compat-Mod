package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.RagdollHeadwearRouting;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollPartBlockEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/** Curios filters by slot before it selects the individual stack. */
@Mixin(targets = "dev.leo.sableplayerragdoll.neoforge.client.CuriosRenderHelper", remap = false)
public abstract class RagdollCuriosHeadwearRoutingMixin {
    @WrapOperation(method = "renderFromStored(Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity$BodyPart;Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/RenderLayerParent;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V", at = @At(value = "INVOKE",
            target = "Ldev/leo/sableplayerragdoll/neoforge/client/CuriosRenderHelper;slotBelongsToPart(Ljava/lang/String;Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity$BodyPart;)Z"), require = 1)
    private static boolean emfattachments$storedHeadItems(String slot, BodyPart part, Operation<Boolean> original,
            @Local(argsOnly = true) RagdollPartBlockEntity stored) {
        return original.call(slot, part) || part == BodyPart.HEAD
                && RagdollHeadwearRouting.storedCurioHasHeadModel(stored, slot);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Ldev/leo/sableplayerragdoll/neoforge/client/CuriosRenderHelper;slotBelongsToPart(Ljava/lang/String;Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity$BodyPart;)Z"), require = 1)
    private static boolean emfattachments$liveHeadItems(String slot, BodyPart part, Operation<Boolean> original,
            @Local(argsOnly = true) LivingEntity entity) {
        return original.call(slot, part) || part == BodyPart.HEAD
                && RagdollHeadwearRouting.liveCurioHasHeadModel(entity, slot);
    }

    @WrapOperation(method = {"lambda$renderFromStored$0", "lambda$render$1"},
            at = @At(value = "INVOKE", target = "Ltop/theillusivec4/curios/api/client/ICurioRenderer;render(Lnet/minecraft/world/item/ItemStack;Ltop/theillusivec4/curios/api/SlotContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/entity/RenderLayerParent;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"), require = 2)
    private static void emfattachments$onlySelectedPhysicalPart(ICurioRenderer renderer, ItemStack stack,
            SlotContext context, PoseStack pose, RenderLayerParent<?, ?> parent, MultiBufferSource buffers,
            int light, float walk, float amount, float partial, float age, float yaw, float pitch, Operation<Void> original) {
        BodyPart part = RagdollPartBlockEntityRenderer.activeBodyPart();
        if (part != null && !RagdollHeadwearRouting.belongsToPart(RagdollHeadwearRouting.isHeadModel(stack),
                part, RagdollHeadwearRouting.curioBelongsToPart(context.identifier(), part))) return;
        // The original chain still checks visibility and exact bridged duplicates.
        original.call(renderer, stack, context, pose, parent, buffers, light, walk, amount, partial, age, yaw, pitch);
    }
}
