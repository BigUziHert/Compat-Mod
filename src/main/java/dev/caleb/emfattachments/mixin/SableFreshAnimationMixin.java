package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.SableFreshAnimationState;
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
        boolean active = support != null && !support.isRemoved();
        SableFreshAnimationState state = access.emfattachments$getSableFreshAnimationState(active);
        if (state == null) return;
        if (!active) {
            state.resetRenderContexts();
            return;
        }
        state.forRenderContext(emf.isFirstPersonHand()).update(emf.variableMap(), support, frame, EMFMath.getEntityY(), player.onGround(), support.lastPose(), support.logicalPose(),
                player.xo, player.yo, player.zo, player.getX(), player.getY(), player.getZ(), EMFMath.getTickDelta());
    }
}
