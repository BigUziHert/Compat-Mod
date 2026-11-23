package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import dev.caleb.emfattachments.test.SableElevatorPhaseProbe;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ContraptionCollider.class, remap = false)
public abstract class SableElevatorPhaseNativeCarryMixin {
    @WrapOperation(method = "collideEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V", ordinal = 1), require = 1)
    private static void emfattachments$observeCarry(Entity rider, double x, double y, double z, Operation<Void> original,
            @Local(argsOnly = true) AbstractContraptionEntity cabin) {
        if (rider instanceof LocalPlayer) SableElevatorPhaseProbe.collider("beforeNativeCarry", cabin);
        original.call(rider, x, y, z);
        if (rider instanceof LocalPlayer) SableElevatorPhaseProbe.collider("afterNativeCarry", cabin);
    }
}
