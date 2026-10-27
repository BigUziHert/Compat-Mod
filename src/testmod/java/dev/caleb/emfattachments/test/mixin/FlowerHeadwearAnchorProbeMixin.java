package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.test.AttachmentTestMod;
import dev.caleb.emfattachments.test.ModelState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "dev.caleb.emfattachments.client.FlowerHeadwearRenderer", remap = false)
public abstract class FlowerHeadwearAnchorProbeMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void emfattachmentsTest$flower(ModelPart head, PoseStack matrices, VertexConsumer vertices,
            int light, int overlay, int color, Operation<Void> original,
            @Local(argsOnly = true) EntityModel<?> model, @Local(argsOnly = true) ItemStack stack) {
        var result = AttachmentTestMod.current;
        if (result != null && model instanceof HumanoidModel<?> parent) {
            result.flowerAnchorChecks++;
            if (!ModelState.same(ModelState.state(parent.head), ModelState.state(head))) result.flowerAnchorFailures++;
            result.flowerColorChecks++;
            result.expectedFlowerColor = 0xFF000000 | (DyedItemColor.getOrDefault(stack, -1682375) & 0xFFFFFF);
            result.actualFlowerColor = color;
            if (color != result.expectedFlowerColor) result.flowerColorFailures++;
        }
        original.call(head, matrices, vertices, light, overlay, color);
    }
}
