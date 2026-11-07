package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.caleb.emfattachments.client.RagdollHeadwearRouting;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "dev.leo.sableplayerragdoll.neoforge.client.AccessoriesRenderHelper", remap = false)
public abstract class RagdollHeadwearRoutingMixin {
    @WrapOperation(method = "renderFromStored(Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity$BodyPart;Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/RenderLayerParent;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V", at = @At(value = "INVOKE",
            target = "Ldev/leo/sableplayerragdoll/neoforge/client/AccessoriesRenderHelper;slotIndexBelongsToPart(Ljava/lang/String;ILdev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity$BodyPart;)Z"), require = 1)
    private static boolean emfattachments$storedHeadItem(String slot, int index, BodyPart part,
            Operation<Boolean> original, @Local(argsOnly = true) RagdollPartBlockEntity stored) {
        return RagdollHeadwearRouting.belongsToPart(
                RagdollHeadwearRouting.isHeadModel(RagdollHeadwearRouting.storedAccessory(stored, slot, index)),
                part, original.call(slot, index, part));
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Ldev/leo/sableplayerragdoll/neoforge/client/AccessoriesRenderHelper;slotIndexBelongsToPart(Ljava/lang/String;ILdev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity$BodyPart;)Z"), require = 1)
    private static boolean emfattachments$liveHeadItem(String slot, int index, BodyPart part,
            Operation<Boolean> original, @Local(argsOnly = true) LivingEntity entity) {
        return RagdollHeadwearRouting.belongsToPart(
                RagdollHeadwearRouting.isHeadModel(RagdollHeadwearRouting.liveAccessory(entity, slot, index)),
                part, original.call(slot, index, part));
    }
}
