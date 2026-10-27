package dev.caleb.emfattachments.test.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.CombatWorldRenderProbe;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** High-priority entry observes this call before the usual optional culling hooks. */
@Mixin(value=LevelRenderer.class,priority=3000)
public abstract class CombatWorldRenderProbeMixin {
    @Inject(method="renderEntity",at=@At("HEAD"),require=1)
    private void emfattachments$worldPlayerEntry(Entity entity,double x,double y,double z,float delta,PoseStack poses,MultiBufferSource buffers,CallbackInfo ci){CombatWorldRenderProbe.levelEntry(entity);}
    @Inject(method="renderEntity",at=@At("RETURN"),require=1)
    private void emfattachments$worldPlayerReturn(Entity entity,double x,double y,double z,float delta,PoseStack poses,MultiBufferSource buffers,CallbackInfo ci){CombatWorldRenderProbe.levelReturn(entity);}
}
