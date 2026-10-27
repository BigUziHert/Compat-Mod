package dev.caleb.emfattachments.mixin;

import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SpeedModifier.class, remap = false)
public interface SpeedModifierAccessor {
    @Accessor("shiftedDelta") float emfattachments$shiftedDelta();
}
