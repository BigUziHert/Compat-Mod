package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.AttachmentTestMod;
import dev.caleb.emfattachments.test.ModelState;
import dev.caleb.emfattachments.test.CountingVertexConsumer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Checks the actual goggles model immediately before its vertices are emitted. */
@Mixin(targets = "com.titammods.aeronautics_curios_compat.client.AviatorsGogglesCurioRenderer", remap = false)
public abstract class AviatorAnchorProbeMixin {
    @WrapOperation(method = "render", at = @At(value="INVOKE", target="Lnet/minecraft/client/model/HumanoidModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", remap=false))
    private void emfattachmentsTest$headAnchor(HumanoidModel<?> armor, PoseStack pose, VertexConsumer vertices,
            int light, int overlay, int color, Operation<Void> original,
            @Local(argsOnly=true) RenderLayerParent<?,?> parent) {
        var result = AttachmentTestMod.current;
        if (result != null && parent.getModel() instanceof HumanoidModel<?> body) {
            result.gogglesAnchorChecks++;
            result.sourceHead = ModelState.state(body.head);
            result.armorHead = ModelState.state(armor.head);
            if (!ModelState.same(result.sourceHead,result.armorHead)) result.gogglesAnchorFailures++;
        }
        original.call(armor,pose,vertices,light,overlay,color);
    }
}

