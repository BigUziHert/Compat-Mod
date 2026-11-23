package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.ScarfRenderTrace;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class ScarfLiveRendererProbeMixin {
    private static final String RENDER="render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";
    @Shadow protected EntityModel<?> model;
    @Inject(method=RENDER,at=@At("HEAD"))
    private void emfattachments$begin(LivingEntity actor,float yaw,float pt,PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci) {
        ScarfRenderTrace.begin(actor,model);
    }
    @Inject(method=RENDER,at=@At(value="INVOKE",target="Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",shift=At.Shift.AFTER))
    private void emfattachments$setup(LivingEntity actor,float yaw,float pt,PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci) {
        ScarfRenderTrace.checkpoint("afterSetup",model);
    }
    @ModifyExpressionValue(method=RENDER,at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getRenderType(Lnet/minecraft/world/entity/LivingEntity;ZZZ)Lnet/minecraft/client/renderer/RenderType;"))
    private RenderType emfattachments$type(RenderType type){ScarfRenderTrace.renderType(type);return type;}
    @WrapOperation(method=RENDER,at=@At(value="INVOKE",target="Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void emfattachments$body(EntityModel<?> m,PoseStack pose,VertexConsumer vertices,int light,int overlay,int color,Operation<Void> call) {
        call.call(m,pose,ScarfRenderTrace.base(m,vertices),light,overlay,color);
    }
    @Inject(method=RENDER,at=@At("RETURN"))
    private void emfattachments$end(LivingEntity actor,float yaw,float pt,PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci){ScarfRenderTrace.end(actor);}
}
