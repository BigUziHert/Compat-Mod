package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.RagdollRenderCounters;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.leo.sableplayerragdoll.neoforge.client.RagdollPartBlockEntityRenderer", remap = false)
public abstract class RagdollGeometryProbeMixin {
    @WrapMethod(method = "render(Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V")
    private void emfattachmentsTest$owner(RagdollPartBlockEntity entity, float partial, PoseStack pose,
            MultiBufferSource buffers, int light, int overlay, Operation<Void> original) {
        boolean previous = RagdollRenderCounters.begin(entity.skinProfile().getId());
        try { original.call(entity, partial, pose, buffers, light, overlay); }
        finally { RagdollRenderCounters.end(previous); }
    }

    @Inject(method = "renderLayers", at = @At("HEAD"))
    private void emfattachmentsTest$actualPart(RagdollPartBlockEntity block, RagdollPartBlockEntity.BodyPart part,
            LivingEntity entity, PoseStack pose, MultiBufferSource buffers, int light, float partial, CallbackInfo ci) {
        RagdollRenderCounters.part(part.name());
    }
}
