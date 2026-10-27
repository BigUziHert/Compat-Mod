package dev.caleb.emfattachments.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.caleb.emfattachments.client.SablePlayerOcclusion;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Sable's attachment updates running without bypassing render occlusion. */
@Mixin(value = ClientLevel.class, priority = 1500)
public abstract class SablePlayerEntityCullingMixin {
    // Entity Culling's shared helper also controls rendering. Select only its
    // tick callback so hidden players can still skip model/accessory rendering.
    // Sable requires the normal Entity.tick return hook to consume pending plot
    // positions; Entity Culling's reduced basicTick does not execute that hook.
    @TargetHandler(mixin = "dev.tr7zw.entityculling.mixin.ClientWorldMixin", name = "tickEntity")
    @ModifyExpressionValue(method = "@MixinSquared:Handler", at = @At(value = "INVOKE",
            target = "Ldev/tr7zw/entityculling/NMSCullingHelper;ignoresCulling(Lnet/minecraft/world/entity/Entity;)Z",
            remap = false), require = 1, expect = 1, allow = 1)
    private boolean emfattachments$keepSubLevelPlayersTicking(boolean original,
                                                            @Local(argsOnly = true) Entity entity) {
        return SablePlayerOcclusion.keepsFullTick(original, entity);
    }
}
