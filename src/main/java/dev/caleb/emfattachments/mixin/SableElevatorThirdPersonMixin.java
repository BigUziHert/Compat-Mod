package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.client.SableElevatorRiderCarry;
import io.socol.betterthirdperson.api.CustomCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** An elevator carry occurs between the player tick hooks Better Third Person uses to detect teleports. */
@Mixin(value = CustomCamera.class, remap = false)
public abstract class SableElevatorThirdPersonMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;equals(Ljava/lang/Object;)Z"), require = 1, allow = 1)
    private boolean emfattachments$ignoreNativeElevatorCarry(Vec3 previous, Object current, Operation<Boolean> original) {
        if (original.call(previous, current)) return true;
        var player = Minecraft.getInstance().player;
        return player != null && current instanceof Vec3 position
                && SableElevatorRiderCarry.isRecordedCameraMovement(player, previous, position);
    }
}
