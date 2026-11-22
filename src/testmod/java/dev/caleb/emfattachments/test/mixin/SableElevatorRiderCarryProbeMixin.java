package dev.caleb.emfattachments.test.mixin;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.test.SableElevatorLiveSequence;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes positive fallback results from the candidate without modifying rider or cabin state. */
@Mixin(targets = "dev.caleb.emfattachments.client.SableElevatorRiderCarry", remap = false)
public abstract class SableElevatorRiderCarryProbeMixin {
    @Shadow private static WeakReference<LocalPlayer> supportedPlayer;
    @Shadow private static WeakReference<AbstractContraptionEntity> supportingCabin;
    @Shadow private static int contactTick;
    @Shadow private static int carriedTick;

    @Inject(method = "missingCarry", at = @At("HEAD"))
    private static void emfattachments$inspectRiderCarry(LocalPlayer rider, AbstractContraptionEntity cabin,
            CallbackInfoReturnable<Vec3> callback) {
        if (!Boolean.getBoolean("emfattachments.sableElevatorCarryTrace")
                || !SableElevatorLiveSequence.observes(rider, cabin)) return;
        var carrier = Sable.HELPER.getContaining(cabin);
        var info = ((EntityMovementExtension) rider).sable$getCollisionInfo();
        LogUtils.getLogger().info("Sable elevator trace: cabin {} tick {} match {} contact {} carried {} active {} "
                        + "carrier {} tracked {} inherited {} jump {} passenger {} onGround {} playerY {} previousY {} cabinDY {} motion {}",
                cabin.getId(), rider.tickCount, supportedPlayer.get() == rider && supportingCabin.get() == cabin,
                contactTick, carriedTick, rider.isAlive(), carrier != null, Sable.HELPER.getTrackingSubLevel(rider) != null,
                info == null ? null : info.inheritedMotion, rider.input.jumping, rider.isPassenger(), rider.onGround(),
                rider.getY(), rider.yo, cabin.getY() - cabin.getPrevPositionVec().y, rider.getDeltaMovement());
    }

    @WrapOperation(method = "hasFloor", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/BlockGetter;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private static BlockHitResult emfattachments$inspectCarryFloor(BlockGetter blocks, ClipContext ray,
            Operation<BlockHitResult> original) {
        var hit = original.call(blocks, ray);
        var rider = Minecraft.getInstance().player;
        var cabin = supportingCabin.get();
        if (Boolean.getBoolean("emfattachments.sableElevatorCarryTrace") && rider != null
                && cabin != null && SableElevatorLiveSequence.observes(rider, cabin)) {
            LogUtils.getLogger().info("Sable elevator trace ray: cabin {} tick {} start {} end {} result {} face {}",
                    cabin.getId(), rider.tickCount, ray.getFrom(), ray.getTo(), hit.getType(), hit.getDirection());
        }
        return hit;
    }

    @Inject(method = "missingCarry", at = @At("RETURN"))
    private static void emfattachments$observeRiderCarry(LocalPlayer rider, AbstractContraptionEntity cabin,
            CallbackInfoReturnable<Vec3> callback) {
        Vec3 movement = callback.getReturnValue();
        if (movement != Vec3.ZERO && SableElevatorLiveSequence.observes(rider, cabin)) {
            LogUtils.getLogger().info("Sable elevator fallback carry: cabin {} rider tick {} motion {}",
                    cabin.getId(), rider.tickCount, movement);
        }
    }
}
