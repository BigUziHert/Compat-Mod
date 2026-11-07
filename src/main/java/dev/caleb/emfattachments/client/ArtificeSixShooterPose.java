package dev.caleb.emfattachments.client;

import io.redspace.irons_artifice.data.HandOccupancy;
import io.redspace.irons_artifice.item.FireDelayState;
import io.redspace.irons_artifice.item.GunItem;
import io.redspace.irons_artifice.registry.ItemRegistry;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Hand ownership for rendering the Six-Shooter. Fire and reload state remain Iron's. */
public final class ArtificeSixShooterPose {
    private ArtificeSixShooterPose() {}

    public static boolean isSixShooter(ItemStack stack) {
        return stack.is(ItemRegistry.SIX_SHOOTER.get());
    }

    public static boolean oneHandedFire(LivingEntity owner, ItemStack stack) {
        return isSixShooter(stack) && !GunItem.isReloading(stack) && FireDelayState.isActive(owner, stack);
    }

    public static HandOccupancy visualOccupancy(LivingEntity owner, ItemStack stack, HandOccupancy nativeOccupancy) {
        if (nativeOccupancy == HandOccupancy.BOTH && oneHandedFire(owner, stack)) return HandOccupancy.MAINHAND;
        return nativeOccupancy;
    }
}
