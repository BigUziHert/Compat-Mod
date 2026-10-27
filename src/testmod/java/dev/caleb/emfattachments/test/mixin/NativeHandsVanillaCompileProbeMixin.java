package dev.caleb.emfattachments.test.mixin;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.NativeAetherHandProbe;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ModelPart.class)
public abstract class NativeHandsVanillaCompileProbeMixin {
    @Inject(method="compile",at=@At("HEAD"),require=1)
    private void emfattachments$nativeCompiled(PoseStack.Pose pose,VertexConsumer vertices,int light,int overlay,int color,CallbackInfo ci) { NativeAetherHandProbe.compiled((ModelPart)(Object)this,pose); }
}
