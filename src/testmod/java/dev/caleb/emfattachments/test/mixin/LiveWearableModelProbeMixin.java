package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.LiveWearableRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native invisibility can enter PlayerRenderer while skipping the actual body model. */
@Mixin(LivingEntityRenderer.class)
public abstract class LiveWearableModelProbeMixin {
    @WrapOperation(method="render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void emfattachments$observeLiveBodyGeometry(EntityModel<?> model,PoseStack poses,VertexConsumer consumer,int light,int overlay,int color,
                                                       Operation<Void> original,@Local(argsOnly=true) LivingEntity entity) {
        original.call(model,poses,LiveWearableRenderState.model(entity,consumer),light,overlay,color);
    }
}
