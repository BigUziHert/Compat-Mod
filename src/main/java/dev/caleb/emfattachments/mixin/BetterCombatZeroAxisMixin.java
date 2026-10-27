package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.compat.CollisionAxes;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A zero cross product cannot separate boxes, including its negative-zero forms. */
@Mixin(targets = "net.bettercombat.client.collision.OrientedBoundingBox", remap = false)
public abstract class BetterCombatZeroAxisMixin {
    @Inject(method = "Separated([Lnet/minecraft/world/phys/Vec3;[Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Z",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void emfattachments$ignoreSignedZeroAxis(Vec3[] first, Vec3[] second, Vec3 axis,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (CollisionAxes.isZero(axis)) cir.setReturnValue(false);
    }
}
