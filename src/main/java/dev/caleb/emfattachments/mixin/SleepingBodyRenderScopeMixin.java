package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.SleepingBodyRenderScope;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** Inventory previews and direct renderer calls never enter the world-camera scope. */
@Mixin(LevelRenderer.class)
public abstract class SleepingBodyRenderScopeMixin {
    @WrapMethod(method = "renderEntity")
    private void emfattachments$scopeSleepingBody(Entity entity, double x, double y, double z,
            float partialTick, PoseStack pose, MultiBufferSource buffers, Operation<Void> original) {
        var previous = SleepingBodyRenderScope.enter(entity);
        try {
            original.call(entity, x, y, z, partialTick, pose, buffers);
        } finally {
            SleepingBodyRenderScope.restore(previous);
        }
    }
}
