package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.compat.BetterCombatAttackAccess;
import net.bettercombat.client.animation.AttackAnimationSubStack;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Runs after Better Combat has supplied its attackAnimation field. */
@Mixin(value = AbstractClientPlayer.class, priority = 900)
public interface BetterCombatAttackAccessMixin extends BetterCombatAttackAccess {
    // Accessors are generated after the MAIN application pass, when the field
    // introduced by Better Combat exists. A @Shadow is resolved too early.
    @Accessor(value = "attackAnimation", remap = false)
    AttackAnimationSubStack emfattachments$attackStack();

}
