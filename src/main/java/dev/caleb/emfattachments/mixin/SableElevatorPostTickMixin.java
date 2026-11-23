package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.SableElevatorPositionSync;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientHooks.class, remap = false)
public abstract class SableElevatorPostTickMixin {
    @Inject(method = "fireClientTickPost", at = @At("RETURN"))
    private static void emfattachments$finishCabinPosition(CallbackInfo callback) {
        SableElevatorPositionSync.finishPositionSend();
    }
}
