package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.SableElevatorPhaseProbe;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class SableElevatorPhaseSendMixin {
    @Inject(method = "send", at = @At("HEAD"))
    private void emfattachments$observePacket(Packet<?> packet, CallbackInfo info) {
        if (packet instanceof ServerboundMovePlayerPacket move) SableElevatorPhaseProbe.sent(move);
    }
}
