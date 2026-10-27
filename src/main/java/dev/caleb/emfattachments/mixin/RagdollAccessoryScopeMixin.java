package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.RagdollAccessoryDrawScope;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;

/** Separate physical parts, players, shadow passes and nested draws never share a ledger. */
@Mixin(targets = "dev.leo.sableplayerragdoll.neoforge.client.RagdollPartBlockEntityRenderer", remap = false)
public abstract class RagdollAccessoryScopeMixin {
    @WrapMethod(method = "render(Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V")
    private void emfattachments$scopeAccessories(RagdollPartBlockEntity blockEntity, float partialTick,
            PoseStack pose, MultiBufferSource buffers, int light, int overlay, Operation<Void> original) {
        try (var scope = RagdollAccessoryDrawScope.open()) {
            original.call(blockEntity, partialTick, pose, buffers, light, overlay);
        }
    }
}
