package dev.caleb.emfattachments.mixin;

import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractModifier;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ModifierLayer.class, remap = false)
public interface AnimationLayerAccessor {
    @Accessor("modifiers") List<AbstractModifier> emfattachments$modifiers();
}
