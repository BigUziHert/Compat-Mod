package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.SableFreshAnimationState;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.entity_model_features.models.animation.math.EMFMath;
import traben.entity_model_features.models.animation.state.EMFState;

/** Reports the confirmed cabin floor to Fresh without touching the player's movement flags. */
@Mixin(value = EMFMath.class, remap = false)
public abstract class SableFreshElevatorGroundMixin {
    @Inject(method = "isOnGround", at = @At("HEAD"), cancellable = true)
    private static void emfattachments$confirmedElevatorFloor(CallbackInfoReturnable<Boolean> callback) {
        var emf = EMFState.state();
        if (emf == null || !(emf.emfEntity() instanceof LocalPlayer player)
                || !(player instanceof SableFreshAnimationState.Access access)) return;
        var state = access.emfattachments$getSableFreshAnimationState(false);
        if (state != null && state.forRenderContext(emf.isFirstPersonHand())
                .reportsElevatorGround(emf.variableMap(), EMFState.getFrameCounter())) callback.setReturnValue(true);
    }
}
