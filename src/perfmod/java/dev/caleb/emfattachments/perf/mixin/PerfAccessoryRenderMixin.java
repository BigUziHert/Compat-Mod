package dev.caleb.emfattachments.perf.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.perf.EquippedCrowdBenchmark;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Count native dispatches without wrapping renderers, buffers, vertices, or allocating arguments. */
@Mixin(targets = "io.wispforest.accessories.client.AccessoriesRenderLayer", remap = false)
public abstract class PerfAccessoryRenderMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lio/wispforest/accessories/api/client/AccessoryRenderer;render(Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"))
    private void emfattachmentsPerf$nativeAccessoryDispatch(PoseStack matrices, MultiBufferSource buffers, int light,
                                                             LivingEntity player, float swing, float amount,
                                                             float partial, float age, float yaw, float pitch,
                                                             CallbackInfo ci) {
        EquippedCrowdBenchmark.accessoryRendered(player);
    }
}
