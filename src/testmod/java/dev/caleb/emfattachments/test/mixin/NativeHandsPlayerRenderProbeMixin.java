package dev.caleb.emfattachments.test.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.NativeAetherHandProbe;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(PlayerRenderer.class)
public abstract class NativeHandsPlayerRenderProbeMixin {
    @WrapMethod(method="render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void emfattachments$nativeBody(AbstractClientPlayer player,float yaw,float delta,PoseStack poses,MultiBufferSource buffers,int light,Operation<Void> original) {
        try(var scope=NativeAetherHandProbe.body(player,((PlayerRenderer)(Object)this).getModel(),poses)) { original.call(player,yaw,delta,poses,NativeAetherHandProbe.skinBuffers(buffers),light); }
    }
    @WrapMethod(method="renderHand")
    private void emfattachments$nativeHand(PoseStack poses,MultiBufferSource buffers,int light,AbstractClientPlayer player,ModelPart arm,ModelPart sleeve,Operation<Void> original) {
        try(var scope=NativeAetherHandProbe.hand(player,arm,sleeve,poses)) { original.call(poses,NativeAetherHandProbe.skinBuffers(buffers),light,player,arm,sleeve); }
    }
}
