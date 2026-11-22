package dev.caleb.emfattachments.test.mixin;

import com.simibubi.create.content.contraptions.elevator.ElevatorTargetFloorPacket;
import dev.caleb.emfattachments.test.SableElevatorControlsProbe;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes Create's native server receipt and successful floor dispatch. */
@Mixin(value = ElevatorTargetFloorPacket.class, remap = false)
public abstract class SableElevatorControlsPacketProbeMixin {
    @Inject(method = "handle", at = @At("HEAD"))
    private void emfattachments$recordFloorPacket(ServerPlayer player, CallbackInfo callback) {
        var packet = (ElevatorTargetFloorPacket) (Object) this;
        SableElevatorControlsProbe.floorPacket(player, packet.entityId(), packet.targetY(), false);
    }

    @Inject(method = "handle", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/contraptions/elevator/ElevatorContactBlock;callToContactAndUpdate("
                    + "Lcom/simibubi/create/content/contraptions/elevator/ElevatorColumn;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/core/BlockPos;Z)V", shift = At.Shift.AFTER))
    private void emfattachments$recordFloorDispatch(ServerPlayer player, CallbackInfo callback) {
        var packet = (ElevatorTargetFloorPacket) (Object) this;
        SableElevatorControlsProbe.floorPacket(player, packet.entityId(), packet.targetY(), true);
    }
}
