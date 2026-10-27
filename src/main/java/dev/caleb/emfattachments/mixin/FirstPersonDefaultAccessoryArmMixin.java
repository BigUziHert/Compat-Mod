package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.FirstPersonArmGeometry;
import io.wispforest.accessories.api.client.DefaultAccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import java.util.function.Consumer;
import net.minecraft.client.model.HumanoidModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Default hand/wrist/ring meshes use ItemRenderer and never copy a ModelPart. */
@Mixin(value = DefaultAccessoryRenderer.class, remap = false)
public abstract class FirstPersonDefaultAccessoryArmMixin {
    @WrapOperation(method = "render",
            at = @At(value = "INVOKE", target = "Lio/wispforest/accessories/api/client/DefaultAccessoryRenderer$RenderHelper;render(Ljava/util/function/Consumer;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/model/HumanoidModel;Lio/wispforest/accessories/api/slot/SlotReference;)V"), require = 1)
    private void emfattachments$omitArmAnchoredDefault(DefaultAccessoryRenderer.RenderHelper helper,
            Consumer<PoseStack> draw, PoseStack poses, HumanoidModel<?> model,
            SlotReference reference, Operation<Void> original) {
        if (FirstPersonArmGeometry.hidesDefaultAnchor(reference.slotName(), reference.slot(), model)) return;
        original.call(helper, draw, poses, model, reference);
    }
}
