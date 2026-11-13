package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.OverlapCombatProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Restores the former offset during the controlled legacy arm, without replacing the production jar. */
@Mixin(targets = "dev.caleb.emfattachments.compat.EmfCrouchCompatibility", remap = false)
public abstract class OverlapCrouchLegacyControlMixin {
    @Inject(method = "ownsCrouchHeight", at = @At("HEAD"), cancellable = true, require = 1)
    private static void emfattachments$overlapLegacy(CallbackInfoReturnable<Boolean> ci) {
        if (OverlapCombatProbe.active && OverlapCombatProbe.legacyY) ci.setReturnValue(false);
    }
}
