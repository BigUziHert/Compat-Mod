package dev.caleb.emfattachments.test.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.caleb.emfattachments.test.SableElevatorPhaseProbe;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks native Create floor registration and positive compat fallback separately. */
@Mixin(targets = "dev.caleb.emfattachments.client.SableElevatorRiderCarry", remap = false)
public abstract class SableElevatorPhaseContactMixin {
    @Inject(method = "contact", at = @At("HEAD"))
    private static void emfattachments$observeContact(Entity entity, AbstractContraptionEntity cabin, CallbackInfo ci) {
        if (entity instanceof LocalPlayer) SableElevatorPhaseProbe.collider("nativeContact", cabin);
    }

    @Inject(method = "missingCarry", at = @At("RETURN"))
    private static void emfattachments$observeFallback(LocalPlayer rider, AbstractContraptionEntity cabin,
            CallbackInfoReturnable<Vec3> cir) {
        if (cir.getReturnValue().lengthSqr() > 0) SableElevatorPhaseProbe.collider("positiveFallback", cabin);
    }
}
