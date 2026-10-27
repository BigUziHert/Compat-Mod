package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.AttachmentTestMod;
import dev.caleb.emfattachments.test.TorsoAnchorProbe;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.faboslav.friendsandfoes.neoforge.modcompat.curios.CuriosTotemRenderer", remap = false)
public abstract class FriendsAndFoesAnchorProbeMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void emfattachmentsTest$capture(CallbackInfo ci, @Local(argsOnly = true) PoseStack matrices,
            @Share("emfattachmentsTestEntry") LocalRef<Matrix4f> entry) {
        entry.set(new Matrix4f(matrices.last().pose()));
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;getInstance()Lnet/minecraft/client/Minecraft;"))
    private void emfattachmentsTest$verify(CallbackInfo ci, @Local(argsOnly = true) PoseStack matrices,
            @Local(argsOnly = true) RenderLayerParent<?, ?> parent,
            @Share("emfattachmentsTestEntry") LocalRef<Matrix4f> entry) {
        if (AttachmentTestMod.current != null && parent.getModel() instanceof HumanoidModel<?> humanoid) {
            PoseStack expected = new PoseStack();
            expected.last().pose().set(entry.get());
            humanoid.body.translateAndRotate(expected);
            expected.scale(0.35f, 0.35f, 0.35f);
            expected.translate(0f, 1.1f, -0.4f);
            expected.mulPose(Direction.DOWN.getRotation());
            TorsoAnchorProbe.compare(expected.last().pose(), matrices.last().pose());
        }
    }
}
