package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.client.ArtificeSixShooterPose;
import io.redspace.irons_artifice.client.gun.GunInHandRenderer;
import io.redspace.irons_artifice.data.HandOccupancy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = GunInHandRenderer.class, remap = false)
public abstract class ArtificeSixShooterGunHandMixin {
    // Iron always draws the grip bone. Its occupancy check selects only the animated support hand.
    @WrapOperation(method = "currentOccupancy", at = @At(value = "INVOKE",
            target = "Lio/redspace/irons_artifice/item/GunItem;currentOccupancy(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Lio/redspace/irons_artifice/data/HandOccupancy;"), require = 1)
    private HandOccupancy emfattachments$selectGunHands(LivingEntity owner, ItemStack gun,
                                                        Operation<HandOccupancy> original) {
        return ArtificeSixShooterPose.visualOccupancy(owner, gun, original.call(owner, gun));
    }
}
