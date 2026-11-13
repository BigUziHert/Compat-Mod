package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.compat.SableReducedTickAccess;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Safe stubs are linked to the actual merged native callbacks by the mixin plugin. */
@Mixin(value = Entity.class, priority = 1500)
public abstract class SableReducedTickAccessMixin implements SableReducedTickAccess {
    @Unique private boolean emfattachments$sableReducedTick;

    @Override public boolean emfattachments$hasSableReducedTickBridge() { return false; }
    @Override public void emfattachments$maintainSableTick() {}
    @Override public boolean emfattachments$wasSableReducedTick() { return emfattachments$sableReducedTick; }
    @Override public void emfattachments$setSableReducedTick(boolean reduced) { emfattachments$sableReducedTick = reduced; }

    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void emfattachments$clearReducedTickOnFullTick(CallbackInfo ci) {
        emfattachments$sableReducedTick = false;
    }
}
