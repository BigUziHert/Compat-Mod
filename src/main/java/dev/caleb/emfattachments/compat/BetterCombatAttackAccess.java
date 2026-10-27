package dev.caleb.emfattachments.compat;

import dev.kosmx.playerAnim.api.layered.IAnimation;
import net.bettercombat.client.animation.AttackAnimationSubStack;

/** Access to the full attack layer, including the animation being faded out. */
public interface BetterCombatAttackAccess {
    AttackAnimationSubStack emfattachments$attackStack();

    default IAnimation emfattachments$attackLayer() {
        return emfattachments$attackStack().base;
    }
}
