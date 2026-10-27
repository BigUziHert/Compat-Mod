package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.SableFreshAnimationState;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AbstractClientPlayer.class)
public abstract class SableFreshAnimationPlayerMixin implements SableFreshAnimationState.Access {
    @Unique private SableFreshAnimationState emfattachments$sableFreshAnimationState;

    @Override
    public SableFreshAnimationState emfattachments$getSableFreshAnimationState(boolean create) {
        if (create && emfattachments$sableFreshAnimationState == null) {
            emfattachments$sableFreshAnimationState = new SableFreshAnimationState();
        }
        return emfattachments$sableFreshAnimationState;
    }
}
