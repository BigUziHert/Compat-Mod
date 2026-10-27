package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.AttachmentTestMod;
import dev.caleb.emfattachments.test.TorsoAnchorProbe;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "io.github.razordevs.deep_aether.client.renderer.accessory.FloatyScarfRenderer", remap = false)
public abstract class ScarfAnchorProbeMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void emfattachmentsTest$capture(CallbackInfo ci, @Local(argsOnly = true) PoseStack matrices,
            @Share("emfattachmentsTestEntry") LocalRef<Matrix4f> entry) {
        entry.set(new Matrix4f(matrices.last().pose()));
    }

    @Inject(method = "render", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void emfattachmentsTest$verify(CallbackInfo ci, @Local(argsOnly = true) PoseStack matrices,
            @Local(argsOnly = true) EntityModel<?> parent,
            @Share("emfattachmentsTestEntry") LocalRef<Matrix4f> entry) {
        if (AttachmentTestMod.current != null && parent instanceof HumanoidModel<?> humanoid) {
            PoseStack expected = new PoseStack();
            expected.last().pose().set(entry.get());
            humanoid.body.translateAndRotate(expected);
            TorsoAnchorProbe.compare(expected.last().pose(), matrices.last().pose());
        }
    }
}
