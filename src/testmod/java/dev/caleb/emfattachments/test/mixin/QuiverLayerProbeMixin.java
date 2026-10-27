package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.AttachmentTestMod;
import dev.caleb.emfattachments.test.QuiverVertexConsumer;
import net.mehvahdjukaar.supplementaries.configs.ClientConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Verifies the real separate quiver layer executes and emits geometry. */
@Mixin(targets = "net.mehvahdjukaar.supplementaries.client.renderers.entities.layers.QuiverLayer", remap = false)
public abstract class QuiverLayerProbeMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void emfattachmentsTest$enter(CallbackInfo ci) {
        var result = AttachmentTestMod.current;
        if (result != null && result.item.equals("supplementaries:quiver")) {
            result.quiverLayerCalls++;
            result.quiverMode = ClientConfigs.Items.QUIVER_RENDER_MODE.get().name();
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;translateAndRotate(Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void emfattachmentsTest$anchor(ModelPart part, PoseStack matrices, Operation<Void> original,
            @Local(argsOnly = true) LivingEntity entity) {
        var result = AttachmentTestMod.current;
        if (result != null && result.item.equals("supplementaries:quiver") && (Object) Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity) instanceof PlayerRenderer renderer) {
            if (part == renderer.getModel().body) result.quiverBodyAnchorCalls++;
            if (part == renderer.getModel().leftLeg || part == renderer.getModel().rightLeg) result.quiverLegAnchorCalls++;
        }
        original.call(part, matrices);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V"))
    private void emfattachmentsTest$draw(ItemRenderer renderer, LivingEntity entity, ItemStack stack,
            ItemDisplayContext context, boolean leftHand, PoseStack matrices, MultiBufferSource buffers,
            Level level, int light, int overlay, int seed, Operation<Void> original) {
        var result = AttachmentTestMod.current;
        if (result == null || !result.item.equals("supplementaries:quiver")) {
            original.call(renderer, entity, stack, context, leftHand, matrices, buffers, level, light, overlay, seed);
            return;
        }
        result.quiverRenderCalls++;
        MultiBufferSource counted = type -> new QuiverVertexConsumer(buffers.getBuffer(type), result);
        original.call(renderer, entity, stack, context, leftHand, matrices, counted, level, light, overlay, seed);
    }
}
