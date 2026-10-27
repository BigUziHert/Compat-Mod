package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.compat.TidalGrappleImpactGuard;
import dev.leo.ragdollreactions.physics.ImpactReactionHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Excludes native grapple acceleration only from the generic movement heuristic. */
@Mixin(value = ImpactReactionHandler.class, remap = false)
public abstract class TidalGrappleImpactMixin {
    @Inject(method = "tryTriggerReaction", at = @At("HEAD"), cancellable = true)
    private static void emfattachments$nativeGrapple(ServerLevel level, ServerPlayer player, CallbackInfo ci) {
        if (!TidalGrappleImpactGuard.isNativePulling(player)) return;
        // Do not retain a pre-grapple velocity sample that would turn the next
        // ordinary movement into a false acceleration when the hook is released.
        ImpactReactionHandler.onPlayerDisplaced(player);
        ci.cancel();
    }
}
