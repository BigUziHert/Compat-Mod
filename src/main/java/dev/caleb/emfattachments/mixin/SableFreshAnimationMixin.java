package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.SableFreshAnimationState;
import dev.caleb.emfattachments.compat.EmfSableCompatibility;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import traben.entity_model_features.models.animation.EMFAnimationHandler;
import traben.entity_model_features.models.animation.math.EMFMath;
import traben.entity_model_features.models.animation.state.EMFState;

/** Keep deck motion out of Fresh Player's landing animation without changing crouch or physics. */
@Mixin(value = EMFAnimationHandler.class, remap = false)
public abstract class SableFreshAnimationMixin implements SableFreshAnimationState.AnimationAccess {
    @Unique private boolean emfattachments$freshChecked;
    @Unique private boolean emfattachments$freshPlayer;

    @Override
    public void emfattachments$beforeFreshEvaluation() {
        if (!emfattachments$freshChecked) {
            emfattachments$freshChecked = true;
            EMFAnimationHandler handler = (EMFAnimationHandler) (Object) this;
            emfattachments$freshPlayer = handler.getLine("var.Created_by_FreshLX_for_Fresh_Animations") != null
                    && handler.getLine("var.pre_posy") != null && handler.getLine("var.verti_drag") != null
                    && handler.getLine("var.t_land") != null && handler.getLine("var.frame_counter_prev") != null;
        }
        if (!emfattachments$freshPlayer || Minecraft.getInstance().isPaused() || EMFMath.isInGui()) return;
        var emf = EMFState.state();
        if (emf == null || !(emf.emfEntity() instanceof AbstractClientPlayer player)
                || !(player instanceof SableFreshAnimationState.Access access)) return;
        // Fresh Player advances position history only once per frame, including shader and hand passes.
        float frame = EMFState.getFrameCounter();
        if (EMFMath.getEntityVariable("var.frame_counter_prev", Float.NaN) == frame) return;
        SubLevel support = player.isPassenger() ? null : Sable.HELPER.getTrackingSubLevel(player);
        boolean upstreamHeight = EmfSableCompatibility.hasSteadyHeight();
        boolean active = support != null && !support.isRemoved()
                && (!upstreamHeight || EmfSableCompatibility.steadyHeightEnabled());
        SableFreshAnimationState state = access.emfattachments$getSableFreshAnimationState(active);
        if (state == null) return;
        if (!active) {
            state.resetRenderContexts();
            return;
        }
        var context = state.forRenderContext(emf.isFirstPersonHand());
        float animationY = EMFMath.getEntityY();
        if (upstreamHeight) {
            // Core owns deck movement. Only protect Fresh's history against the
            // observed one-float downward rounding step in its corrected input.
            context.history.updateUpstreamHeight(emf.variableMap(), support, frame, animationY, player.onGround());
        } else {
            context.update(emf.variableMap(), support, frame, animationY, player.onGround(), support.lastPose(), support.logicalPose(),
                    player.xo, player.yo, player.zo, player.getX(), player.getY(), player.getZ(), EMFMath.getTickDelta());
        }
    }
}
