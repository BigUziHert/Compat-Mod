package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.NeaProjectilePoseProbe;
import dev.tr7zw.notenoughanimations.api.BasicAnimation;
import dev.tr7zw.notenoughanimations.logic.AnimationProvider;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=AnimationProvider.class, remap=false, priority=900)
public abstract class NeaProjectilePoseProbeMixin {
    @Unique private BasicAnimation[] emfattachments$selected;
    @Inject(method="applyAnimations",at=@At("HEAD"),require=1)
    private void emfattachments$reset(AbstractClientPlayer player,PlayerModel<?> model,float delta,float swing,CallbackInfo ci) {
        emfattachments$selected=null;
    }
    @ModifyVariable(method="applyAnimations",at=@At("STORE"),ordinal=0,require=1)
    private BasicAnimation[] emfattachments$selected(BasicAnimation[] selected) {
        emfattachments$selected=selected; return selected;
    }
    @Inject(method="applyAnimations",at=@At("RETURN"),require=1)
    private void emfattachments$capture(AbstractClientPlayer player,PlayerModel<?> model,float delta,float swing,CallbackInfo ci) {
        NeaProjectilePoseProbe.selected(player,model,emfattachments$selected);
    }
}
