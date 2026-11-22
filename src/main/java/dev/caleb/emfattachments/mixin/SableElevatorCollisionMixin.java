package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import dev.caleb.emfattachments.client.SableElevatorRiderCarry;
import dev.ryanhcode.sable.Sable;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.MutablePair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps native Create elevator contact and correction in the Sable rider's frame of reference. */
@Mixin(value = ContraptionCollider.class, remap = false)
public abstract class SableElevatorCollisionMixin {
    @Shadow private static MutablePair<WeakReference<AbstractContraptionEntity>, Double> safetyLock;

    @Shadow static Vec3 collide(Vec3 movement, Entity rider) {
        throw new AssertionError();
    }

    @Inject(method = "collideEntities", at = @At("HEAD"))
    private static void emfattachments$beforeElevatorMovement(AbstractContraptionEntity cabin, CallbackInfo callback,
            @Share("elevatorCameraPosition") LocalRef<Vec3> before) {
        if (!cabin.level().isClientSide() || !(cabin.getContraption() instanceof ElevatorContraption)) return;
        var rider = Minecraft.getInstance().player;
        if (rider != null) before.set(rider.position());
    }

    @Inject(method = "collideEntities", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;registerColliding(Lnet/minecraft/world/entity/Entity;)V"),
            require = 1, expect = 1, allow = 1)
    private static void emfattachments$rememberElevatorContact(AbstractContraptionEntity cabin, CallbackInfo callback,
            @Local Entity rider) {
        SableElevatorRiderCarry.contact(rider, cabin);
    }

    @ModifyVariable(method = "collideEntities", at = @At("STORE"), name = "contactPointMotion", require = 1, allow = 2)
    private static Vec3 emfattachments$carryOnceAcrossDeckSeam(Vec3 motion,
            @Local Entity rider, @Local(argsOnly = true) AbstractContraptionEntity cabin) {
        return SableElevatorRiderCarry.removeOverlappingCarrierMotion(motion, rider, cabin);
    }

    @Inject(method = "collideEntities", at = @At("RETURN"))
    private static void emfattachments$carryAcrossMissedContact(AbstractContraptionEntity cabin, CallbackInfo callback,
            @Share("elevatorCameraPosition") LocalRef<Vec3> before) {
        if (!cabin.level().isClientSide() || !(cabin.getContraption() instanceof ElevatorContraption)) return;
        var rider = Minecraft.getInstance().player;
        if (rider == null) return;
        Vec3 movement = SableElevatorRiderCarry.missingCarry(rider, cabin);
        if (movement != Vec3.ZERO) {
            Vec3 allowed = collide(movement, rider);
            if (allowed.y != 0 || allowed.distanceToSqr(movement) > 1e-8) SableElevatorRiderCarry.clear();
            if (allowed.y == 0) rider.setPos(rider.position().add(allowed));
        }
        SableElevatorRiderCarry.recordCameraMovement(rider, cabin, before.get());
    }

    @Inject(method = "saveClientPlayerFromClipping", at = @At("HEAD"), cancellable = true)
    private static void emfattachments$releaseElevatorSafetyLock(AbstractContraptionEntity cabin, Vec3 motion,
            CallbackInfo callback) {
        if (!(cabin.getContraption() instanceof ElevatorContraption)) return;
        var rider = Minecraft.getInstance().player;
        if (rider == null || !(rider.getAbilities().flying || rider.isFallFlying()
                || rider.input.jumping && rider.getDeltaMovement().y > 0.1)) return;
        safetyLock.setLeft(null);
        callback.cancel();
    }

    @ModifyExpressionValue(method = "savePlayerFromClipping(Lnet/minecraft/world/entity/player/Player;"
            + "Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;Lnet/minecraft/world/phys/Vec3;D)Z",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;"
                    + "toGlobalVector(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;"),
            require = 1, expect = 1, allow = 1)
    private static Vec3 emfattachments$projectElevatorHit(Vec3 hit,
            @Local(argsOnly = true) AbstractContraptionEntity contraption) {
        if (!(contraption.getContraption() instanceof ElevatorContraption)) return hit;
        var subLevel = Sable.HELPER.getContaining(contraption);
        return subLevel == null || subLevel.isRemoved() ? hit : subLevel.logicalPose().transformPosition(hit);
    }
}
