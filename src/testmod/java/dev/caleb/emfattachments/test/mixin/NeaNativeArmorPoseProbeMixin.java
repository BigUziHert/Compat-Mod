package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.NeaProjectilePoseProbe;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Read-only witnesses around the installed NeoForge 1.21.1 native armor copy and draw. */
@Mixin(HumanoidArmorLayer.class)
public abstract class NeaNativeArmorPoseProbeMixin {
    @WrapOperation(method="renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/client/model/HumanoidModel;copyPropertiesTo(Lnet/minecraft/client/model/HumanoidModel;)V"),require=1)
    private void emfattachments$copied(HumanoidModel<?> parent,HumanoidModel<?> armor,Operation<Void> original,
                                      @Local(argsOnly=true) LivingEntity entity,@Local(argsOnly=true) EquipmentSlot slot) {
        original.call(parent,armor);
        NeaProjectilePoseProbe.nativeArmorCopy(entity,slot,parent,armor);
    }

    @WrapOperation(method="renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/Model;ILnet/minecraft/resources/ResourceLocation;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),require=1)
    private void emfattachments$drawn(Model model,PoseStack pose,VertexConsumer vertices,int light,int overlay,int color,Operation<Void> original) {
        NeaProjectilePoseProbe.nativeArmorDraw(model,false);
        original.call(model,pose,vertices,light,overlay,color);
        NeaProjectilePoseProbe.nativeArmorDraw(model,true);
    }
}
