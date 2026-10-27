package dev.caleb.emfattachments.test.mixin;

import artifacts.client.item.model.ArmsModel;
import artifacts.client.item.renderer.GloveArtifactRenderer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.NativeHandAccessoryProbe;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=GloveArtifactRenderer.class,remap=false,priority=2000)
public abstract class NativeHandAccessoryGloveProbeMixin {
    @Shadow protected abstract ArmsModel getModel(boolean slim);
    @WrapMethod(method="renderFirstPersonArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/world/entity/HumanoidArm;Z)V")
    private void emfattachments$nativeGlove(PoseStack poses,MultiBufferSource buffers,int light,AbstractClientPlayer player,
            HumanoidArm side,boolean foil,Operation<Void> original) {
        ArmsModel model=getModel(player.getSkin().model()==PlayerSkin.Model.SLIM);
        ModelPart arm=side==HumanoidArm.LEFT?model.leftArm:model.rightArm;
        try(var scope=NativeHandAccessoryProbe.glove(arm,getClass().getName())) {
            original.call(poses,NativeHandAccessoryProbe.buffers(buffers),light,player,side,foil);
        }
    }
    @Inject(method="renderFirstPersonArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/world/entity/HumanoidArm;Z)V",
            at=@At(value="INVOKE",target="Lartifacts/client/item/renderer/GloveArtifactRenderer;renderFirstPersonArm(Lartifacts/client/item/model/ArmsModel;Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IZZ)V"),require=1)
    private void emfattachments$nativeReset(PoseStack poses,MultiBufferSource buffers,int light,AbstractClientPlayer player,HumanoidArm side,boolean foil,CallbackInfo ci) { NativeHandAccessoryProbe.resetObserved(); }
}
