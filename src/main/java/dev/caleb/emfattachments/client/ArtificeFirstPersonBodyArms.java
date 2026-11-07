package dev.caleb.emfattachments.client;

import dev.tr7zw.firstperson.FirstPersonModelCore;
import dev.tr7zw.firstperson.InventoryUtil;
import dev.tr7zw.firstperson.versionless.config.VanillaHands;
import io.redspace.irons_artifice.data.HandOccupancy;
import io.redspace.irons_artifice.item.GunItem;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Queried only after identifying the local FirstPerson body render. */
final class ArtificeFirstPersonBodyArms {
    private ArtificeFirstPersonBodyArms() {}

    static int nativeArmMask(LivingEntity entity) {
        if (!(entity instanceof Player player)) return 0;
        // FirstPerson deliberately returns EMPTY from the entity hand getters in
        // this body pass. Read the real inventory just as its LogicHandler does.
        var inventory = InventoryUtil.getInventory(player);
        var main = InventoryUtil.getSelected(inventory);
        var offhand = InventoryUtil.getOffhand(inventory);
        boolean mainGun = main.getItem() instanceof GunItem;
        boolean offhandGun = offhand.getItem() instanceof GunItem;
        if (!mainGun && !offhandGun) return 0;
        var logic = FirstPersonModelCore.instance.getLogicHandler();
        // Honor native swimming, dynamic-camera and competing animation decisions.
        if (!logic.showVanillaHands(entity) || !logic.hideArmsAndItems(entity)) return 0;
        // Use the same support-hand decision as the gun renderer. A separate
        // offhand item, or ALL_DOUBLE, has its own native hand pass.
        HandOccupancy occupancy = ArtificeSixShooterPose.visualOccupancy(entity, main,
                GunItem.currentOccupancy(entity, main));
        return armMask(mainGun, offhandGun, offhand.isEmpty(),
                occupancy == HandOccupancy.BOTH,
                FirstPersonModelCore.instance.getConfig().vanillaHandsMode == VanillaHands.ALL_DOUBLE
                        || GunItem.isScoping(entity),
                player.getMainArm() == HumanoidArm.LEFT);
    }

    /** Physical mask bits: right=1, left=2, matching FirstPersonArmGeometry. */
    static int armMask(boolean mainGun, boolean offhandGun, boolean emptyOffhand,
                       boolean bothNativeHands, boolean nativeFreeHand, boolean leftMain) {
        if (!mainGun && !offhandGun) return 0;
        if (mainGun && emptyOffhand && !bothNativeHands && !nativeFreeHand) return leftMain ? 2 : 1;
        return 3;
    }
}
