package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.test.SableElevatorPhaseProbe;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class SableElevatorPhaseTravelMixin {
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;travel(Lnet/minecraft/world/phys/Vec3;)V"), require = 1)
    private void emfattachments$observeTravel(LivingEntity entity, Vec3 input, Operation<Void> original) {
        if (entity instanceof Player player) SableElevatorPhaseProbe.travel("beforeTravel", player);
        original.call(entity, input);
        if (entity instanceof Player player) SableElevatorPhaseProbe.travel("afterTravel", player);
    }
}
