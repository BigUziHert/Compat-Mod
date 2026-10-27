package dev.caleb.emfattachments.client;

import dev.caleb.emfattachments.mixin.AnimationFadeAccessor;
import dev.caleb.emfattachments.mixin.AnimationLayerAccessor;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import java.util.List;
import net.bettercombat.client.animation.AttackAnimationSubStack;

/** Reads BC's own player, including a live fade source; never consults AnimationStack's mode filter. */
public final class NativeAttackArmVisibility {
    private static final Visibility[] FLAGS = flags();
    private NativeAttackArmVisibility() {}

    public record Visibility(boolean rightArm, boolean leftArm, boolean rightItem, boolean leftItem) {
        public boolean hidesAnyArm() { return !rightArm || !leftArm; }
    }

    public static Visibility from(AttackAnimationSubStack stack, float partial) {
        return resolveModifiers(stack.base.getAnimation(),
                ((AnimationLayerAccessor) stack.base).emfattachments$modifiers(), stack.mirror.isEnabled(), partial);
    }

    static Visibility resolveModifiers(IAnimation current, List<?> modifiers, boolean mirrored, float partial) {
        if (current != null && current.isActive()) return visibility(current, mirrored, partial);
        // replaceAnimationWithFade appends its newest fade after older modifiers.
        // Do not build a temporary list or inspect older fades once the native
        // configuration source is found.
        for (int i = modifiers.size() - 1; i >= 0; i--) {
            if (modifiers.get(i) instanceof AnimationFadeAccessor fade
                    && fade.emfattachments$time() < fade.emfattachments$length()) {
                IAnimation source = fade.emfattachments$source();
                if (source != null && source.isActive()) return visibility(source, mirrored, partial);
            }
        }
        return null;
    }

    /** Pure selection boundary used by installed-class regression tests. Newest fade comes first. */
    static Visibility resolve(IAnimation current, List<IAnimation> newestFadeSources, boolean mirrored, float partial) {
        IAnimation source = current != null && current.isActive() ? current : null;
        if (source == null) for (IAnimation candidate : newestFadeSources) {
            if (candidate != null && candidate.isActive()) { source = candidate; break; }
        }
        return source == null ? null : visibility(source, mirrored, partial);
    }

    private static Visibility visibility(IAnimation source, boolean mirrored, float partial) {
        FirstPersonConfiguration config = source.getFirstPersonConfiguration(partial);
        int bits = (config.isShowRightArm() ? 1 : 0) | (config.isShowLeftArm() ? 2 : 0)
                | (config.isShowRightItem() ? 4 : 0) | (config.isShowLeftItem() ? 8 : 0);
        if (mirrored) bits = ((bits & 5) << 1) | ((bits & 10) >> 1);
        return FLAGS[bits];
    }

    private static Visibility[] flags() {
        Visibility[] values = new Visibility[16];
        for (int i = 0; i < values.length; i++) {
            values[i] = new Visibility((i & 1) != 0, (i & 2) != 0, (i & 4) != 0, (i & 8) != 0);
        }
        return values;
    }
}
