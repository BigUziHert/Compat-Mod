package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.SableElevatorRiderCarry;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Supplies Sable's native launch momentum when the local rider leaves a nested elevator. */
@Mixin(LivingEntity.class)
public abstract class SableElevatorTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"))
    private void emfattachments$beforeElevatorTravel(Vec3 travel, CallbackInfo callback) {
        if ((Object) this instanceof LocalPlayer player) SableElevatorRiderCarry.beforeTravel(player);
    }
}
