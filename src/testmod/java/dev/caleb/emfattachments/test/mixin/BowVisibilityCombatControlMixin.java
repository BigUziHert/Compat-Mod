package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.client.FirstPersonCombat;
import dev.caleb.emfattachments.test.BowVisibilityControls;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=FirstPersonCombat.class,remap=false)
public abstract class BowVisibilityCombatControlMixin {
    @Inject(method="modifiesBodyProcessor",at=@At("HEAD"),cancellable=true,require=1)
    private static void emfattachments$diagnosticNativeTransforms(Object processor,CallbackInfoReturnable<Boolean> cir) {
        // Keep arbitration/one-pass ownership while disabling only our transforms.
        if(BowVisibilityControls.useNativeCombatTransforms()) cir.setReturnValue(false);
    }
}
