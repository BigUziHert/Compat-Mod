package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalDoubleRef;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import dev.caleb.emfattachments.test.SableElevatorLiveSequence;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ContraptionCollider.class, remap = false)
public abstract class SableElevatorLiveCollisionProbeMixin {
    @WrapOperation(method = "collideEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V", ordinal = 1),
            require = 1, expect = 1, allow = 1)
    private static void emfattachments$observeNativeCarry(Entity rider, double x, double y, double z,
            Operation<Void> original, @Local(argsOnly = true) AbstractContraptionEntity cabin) {
        if (Boolean.getBoolean("emfattachments.sableElevatorCarryTrace") && rider instanceof Player player
                && SableElevatorLiveSequence.observes(player, cabin)) {
            var carrier = Sable.HELPER.getContaining(cabin);
            if (carrier != null) {
                var info = ((EntityMovementExtension) rider).sable$getCollisionInfo();
                Vec3 before = rider.position();
                var plotFeet = carrier.logicalPose().transformPositionInverse(before);
                Vec3 step = before.subtract(carrier.lastPose().transformPosition(plotFeet));
                LogUtils.getLogger().info("Sable elevator native carry: cabin {} tick {} plot feet {} "
                                + "step {} applied {} deck before {} deck now {} inherited {} momentum {}",
                        cabin.getId(), rider.tickCount, plotFeet, step, new Vec3(x, y, z).subtract(before),
                        info != null && info.preTrackingSubLevel == carrier,
                        Sable.HELPER.getTrackingSubLevel(rider) == carrier, info == null ? null : info.inheritedMotion,
                        ((LivingEntityMovementExtension) player).sable$getInheritedVelocity());
            }
        }
        original.call(rider, x, y, z);
    }

    @Inject(method = "savePlayerFromClipping", at = @At("HEAD"))
    private static void emfattachments$observeCorrectionStart(Player rider, AbstractContraptionEntity contraption,
            Vec3 motion, double offset, CallbackInfoReturnable<Boolean> callback,
            @Share("elevatorProbeY") LocalDoubleRef before) {
        if (SableElevatorLiveSequence.observes(rider, contraption)) before.set(rider.getY());
    }

    @Inject(method = "savePlayerFromClipping", at = @At("RETURN"))
    private static void emfattachments$observeCorrectionEnd(Player rider, AbstractContraptionEntity contraption,
            Vec3 motion, double offset, CallbackInfoReturnable<Boolean> callback,
            @Share("elevatorProbeY") LocalDoubleRef before) {
        if (SableElevatorLiveSequence.observes(rider, contraption)) {
            SableElevatorLiveSequence.correction(rider, before.get(), callback.getReturnValue());
        }
    }
}
