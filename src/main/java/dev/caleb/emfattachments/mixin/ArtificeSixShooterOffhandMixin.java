package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.client.ArtificeSixShooterPose;
import io.redspace.irons_artifice.client.ClientHelper;
import io.redspace.irons_artifice.data.HandOccupancy;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ClientHelper.class, remap = false)
public abstract class ArtificeSixShooterOffhandMixin {
    // Iron uses this client helper to suppress the other item's vanilla first-person draw.
    @WrapOperation(method = "getHandHoldingTwoHandedGun", at = @At(value = "INVOKE",
            target = "Lio/redspace/irons_artifice/item/GunItem;currentOccupancy(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;)Lio/redspace/irons_artifice/data/HandOccupancy;"), require = 2)
    private static HandOccupancy emfattachments$allowFreeItem(LivingEntity owner, InteractionHand hand,
                                                             Operation<HandOccupancy> original) {
        return ArtificeSixShooterPose.visualOccupancy(owner, owner.getItemInHand(hand), original.call(owner, hand));
    }
}
