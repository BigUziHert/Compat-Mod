package dev.caleb.emfattachments.mixin;

import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = AbstractFadeModifier.class, remap = false)
public interface AnimationFadeAccessor {
    @Accessor("time") int emfattachments$time();
    @Accessor("length") int emfattachments$length();
    @Accessor("beginAnimation") IAnimation emfattachments$source();
    @Invoker("getAlpha") float emfattachments$alpha(String part, TransformType type, float progress);
}
