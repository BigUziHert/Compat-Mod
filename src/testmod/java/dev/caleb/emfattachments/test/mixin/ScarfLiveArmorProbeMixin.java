package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.ScarfRenderTrace;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HumanoidArmorLayer.class)
public abstract class ScarfLiveArmorProbeMixin {
    @WrapOperation(method="renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/client/model/HumanoidModel;copyPropertiesTo(Lnet/minecraft/client/model/HumanoidModel;)V"))
    private void emfattachments$copy(HumanoidModel<?> parent,HumanoidModel<?> armor,Operation<Void> original,@Local(argsOnly=true) EquipmentSlot slot) {
        ScarfRenderTrace.checkpoint("armorParent_"+slot.getName(),parent);
        original.call(parent,armor);
        ScarfRenderTrace.checkpoint("armorCopy_"+slot.getName(),armor);
    }
    @WrapOperation(method="renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/Model;ILnet/minecraft/resources/ResourceLocation;)V",
        at=@At(value="INVOKE",target="Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void emfattachments$draw(Model m,PoseStack p,VertexConsumer vc,int light,int overlay,int color,Operation<Void> original){
        ScarfRenderTrace.checkpoint("armorDraw",m);original.call(m,p,vc,light,overlay,color);
    }
}
