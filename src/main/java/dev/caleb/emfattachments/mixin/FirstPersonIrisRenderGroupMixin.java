package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.FirstPersonIrisRenderGrouping;
import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import net.irisshaders.batchedentityrendering.impl.Groupable;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelRenderer.class)
public abstract class FirstPersonIrisRenderGroupMixin {
    @WrapMethod(method = "renderEntity")
    private void emfattachments$groupInjectedPlayer(Entity entity, double x, double y, double z, float partial,
            PoseStack pose, MultiBufferSource buffers, Operation<Void> original) {
        if (entity instanceof LivingEntity living && FirstPersonRenderPolicy.isLocalBody(living)
                && buffers instanceof Groupable group) {
            // Iris groups only the native renderLevel call sites. FPM invokes
            // this method from its injected callback, outside those call sites.
            FirstPersonIrisRenderGrouping.render(group, () -> original.call(entity, x, y, z, partial, pose, buffers));
        } else original.call(entity, x, y, z, partial, pose, buffers);
    }
}
