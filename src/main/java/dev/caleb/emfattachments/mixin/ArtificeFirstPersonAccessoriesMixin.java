package dev.caleb.emfattachments.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.ArtificeFirstPersonAccessories;
import io.redspace.irons_artifice.client.gun.GunInHandRenderer;
import io.redspace.irons_artifice.item.GunItem;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

@Mixin(value = GunInHandRenderer.class, remap = false)
public abstract class ArtificeFirstPersonAccessoriesMixin extends GeoItemRenderer<GunItem> {
    protected ArtificeFirstPersonAccessoriesMixin(GeoModel<GunItem> model) { super(model); }

    @Inject(method = "renderFirstPersonHand",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V",
                    shift = At.Shift.AFTER), require = 1)
    private void emfattachments$renderAccessoriesOnGunArm(MultiBufferSource buffers, RenderType renderType,
            ModelPart arm, PoseStack poses, int light, CallbackInfo ci) {
        ArtificeFirstPersonAccessories.render(arm, renderPerspective, poses, buffers, light);
    }
}
