package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.ArtificeFirstPersonAccessories;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Skyjade overrides Aether's hand renderer and ignores the supplied player's arm pose. */
@Mixin(targets = "io.github.razordevs.deep_aether.client.renderer.accessory.SkyjadeGlovesRenderer", remap = false)
public abstract class ArtificeSkyjadeFirstPersonPoseMixin {
    @WrapOperation(method = "renderFirstPerson",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
            require = 1)
    private void emfattachments$followGunBoneColored(ModelPart arm, PoseStack poses,
            VertexConsumer vertices, int light, int overlay, int color, Operation<Void> original) {
        ArtificeFirstPersonAccessories.renderArm(arm,
                () -> original.call(arm, poses, vertices, light, overlay, color));
    }

    @WrapOperation(method = "renderFirstPerson",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"),
            require = 2)
    private void emfattachments$followGunBoneTrimAndGlint(ModelPart arm, PoseStack poses,
            VertexConsumer vertices, int light, int overlay, Operation<Void> original) {
        ArtificeFirstPersonAccessories.renderArm(arm,
                () -> original.call(arm, poses, vertices, light, overlay));
    }
}
