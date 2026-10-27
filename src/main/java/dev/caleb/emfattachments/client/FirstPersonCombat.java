package dev.caleb.emfattachments.client;

import dev.caleb.emfattachments.compat.BetterCombatAttackAccess;
import dev.caleb.emfattachments.config.CompatConfig;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.caleb.emfattachments.mixin.AnimationFadeAccessor;
import dev.caleb.emfattachments.mixin.AnimationLayerAccessor;
import dev.caleb.emfattachments.mixin.SpeedModifierAccessor;
import net.minecraft.util.Mth;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.neoforged.fml.ModList;

/** Read-only arbitration between the body renderer and the animated hand pass. */
public final class FirstPersonCombat {
    private static boolean queryingActivation;
    private FirstPersonCombat() {}

    public static boolean isAttackActive(AbstractClientPlayer player) {
        return player instanceof BetterCombatAttackAccess access
                && access.emfattachments$attackLayer() != null
                && access.emfattachments$attackLayer().isActive();
    }

    public static boolean ownsCombatRendering() {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (!CompatConfig.combatCompatibility() || !FirstPersonRenderPolicy.isLocalFirstPersonCamera(player)
                || !isAttackActive(player) || isRolling(player)) return false;
        // Honors FirstPerson's activation handlers: sleep, swimming transitions,
        // disabled items, scoping and any other registered camera integration.
        if (queryingActivation) return false;
        queryingActivation = true;
        try {
            return FirstPersonModelCore.instance.getLogicHandler().shouldApplyThirdPerson(false);
        } finally {
            queryingActivation = false;
        }
    }

    public static boolean ownsProcessor(Object processor) {
        var player = Minecraft.getInstance().player;
        return player instanceof IAnimatedPlayer animated
                && animated.playerAnimator_getAnimation() == processor && ownsCombatRendering();
    }

    public static boolean modifiesBodyProcessor(Object processor) {
        return FirstPersonRenderPolicy.isLocalBody(Minecraft.getInstance().player) && ownsProcessor(processor);
    }

    /** Uses the animation's own closing frames and fade curve, never an attack timer. */
    public static float correctionWeight(String part, TransformType type, float delta) {
        var player = Minecraft.getInstance().player;
        if (!(player instanceof BetterCombatAttackAccess access)
                || !(access.emfattachments$attackLayer() instanceof ModifierLayer<?> layer)) return 0;
        var modifiers = ((AnimationLayerAccessor) layer).emfattachments$modifiers();
        float[] deltas = new float[modifiers.size()];
        for (int i = 0; i < modifiers.size(); i++) {
            deltas[i] = delta;
            if (modifiers.get(i) instanceof SpeedModifierAccessor speed) delta = speed.emfattachments$shiftedDelta();
        }
        float weight = keyframeWeight(layer.getAnimation(), delta);
        // Each modifier wraps the next; evaluate the fade chain from inside out.
        for (int i = modifiers.size() - 1; i >= 0; i--) {
            if (!(modifiers.get(i) instanceof AnimationFadeAccessor fade)) continue;
            float progress = fade.emfattachments$length() <= 0 ? 1
                    : Mth.clamp((fade.emfattachments$time() + deltas[i]) / fade.emfattachments$length(), 0, 1);
            float alpha = Mth.clamp(fade.emfattachments$alpha(part, type, progress), 0, 1);
            weight = Mth.lerp(alpha, keyframeWeight(fade.emfattachments$source(), deltas[i]), weight);
        }
        return Mth.clamp(weight, 0, 1);
    }

    private static float keyframeWeight(IAnimation animation, float delta) {
        if (animation == null || !animation.isActive()) return 0;
        if (!(animation instanceof KeyframeAnimationPlayer keyframe)) return 1;
        int closingTicks = keyframe.getData().stopTick - keyframe.getData().endTick;
        if (closingTicks <= 0) return 1;
        return 1 - Mth.clamp((keyframe.getTick() + delta - keyframe.getData().endTick) / closingTicks, 0, 1);
    }

    private static boolean isRolling(AbstractClientPlayer player) {
        return ModList.get().isLoaded("combat_roll") && RollState.isActive(player);
    }

    // Optional integration references are linked only when Combat Roll is loaded.
    private static final class RollState {
        private static boolean isActive(AbstractClientPlayer player) {
            return player instanceof net.combat_roll.internals.RollingEntity rolling
                    && rolling.getRollManager().isRolling();
        }
    }
}
