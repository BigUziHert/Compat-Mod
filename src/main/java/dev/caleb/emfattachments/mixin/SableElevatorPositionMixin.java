package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.client.SableElevatorPositionSync;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Send the native position after both travel and Create's post-tick cabin movement. */
@Mixin(LocalPlayer.class)
public abstract class SableElevatorPositionMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;sendPosition()V"), require = 1, allow = 1)
    private void emfattachments$sendCabinPosition(LocalPlayer player, Operation<Void> send) {
        SableElevatorPositionSync.sendPosition(player, send);
    }
}
