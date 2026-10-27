package dev.caleb.emfattachments.test;

import dev.caleb.emfattachments.test.mixin.CombatMinecraftAccess;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import net.bettercombat.api.WeaponAttributes;
import net.bettercombat.client.animation.AttackAnimationSubStack;
import net.bettercombat.logic.PlayerAttackHelper;
import net.bettercombat.logic.PlayerAttackProperties;
import net.bettercombat.logic.WeaponRegistry;
import net.bettercombat.logic.WeaponSwing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Shared disposable-world native actions; deliberately has no FirstPerson linkage. */
public final class ExpandedCombatActions {
    private static Field attackField;
    private ExpandedCombatActions() {}

    /** Caller owns snapshot/restoration. Equip once, then allow >=8 client ticks for attributes. */
    public static CompletableFuture<Void> equip(Minecraft mc, ItemStack main, ItemStack off) {
        if (!mc.hasSingleplayerServer()) throw new IllegalStateException("Integrated test world required");
        var id = mc.player.getUUID();
        ItemStack mainCopy = main.copy(), offCopy = off.copy();
        CompletableFuture<Void> result = new CompletableFuture<>();
        mc.getSingleplayerServer().execute(() -> {
            try {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                if (player == null) throw new IllegalStateException("Server player absent");
                ExpandedRagdollRecovery.release(player);
                player.stopUsingItem();
                player.setItemInHand(InteractionHand.MAIN_HAND, mainCopy);
                player.setItemInHand(InteractionHand.OFF_HAND, offCopy);
                player.inventoryMenu.broadcastChanges();
                result.complete(null);
            } catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }

    public static boolean ready(LocalPlayer player, ItemStack expected, CompletableFuture<?> equipped, int ticks) {
        if (equipped.isCompletedExceptionally()) equipped.join();
        return ticks >= 8 && equipped.isDone() && player.getMainHandItem().is(expected.getItem())
                && !player.isUsingItem() && player.getAttackStrengthScale(0) >= 1 && !attackActive(player);
    }

    public static int comboFor(LocalPlayer player, String animation) {
        var attributes = WeaponRegistry.getAttributes(player.getMainHandItem());
        int length = attributes == null || attributes.attacks() == null ? 0 : attributes.attacks().length * 2;
        for (int index = 0; index < length; index++) {
            var hand = PlayerAttackHelper.getCurrentAttack(player, index);
            if (hand != null && hand.attack().animation().equals(animation)) return index;
        }
        throw new IllegalStateException("Equipped weapon has no available combo for " + animation);
    }

    public static WeaponSwing startAttack(Minecraft mc, int combo) {
        WeaponAttributes attributes = WeaponRegistry.getAttributes(mc.player.getMainHandItem());
        if (attributes == null) throw new IllegalStateException("Equipped weapon has no native attributes");
        var expected = PlayerAttackHelper.getCurrentAttack(mc.player, combo);
        if (expected == null) throw new IllegalStateException("Combo has no available native attack: " + combo);
        ((PlayerAttackProperties) mc.player).setComboCount(combo);
        ((CombatMinecraftAccess) mc).emfattachments$startUpswing(attributes);
        var swing = ((CombatMinecraftAccess) mc).emfattachments$ongoingSwing();
        if (swing == null || !swing.attackHand().attack().animation().equals(expected.attack().animation()))
            throw new IllegalStateException("Native startUpswing refused or selected a different attack");
        return swing;
    }

    /** Better Combat adds this field by mixin. Resolve after transformation, once; works without FPM. */
    public static IAnimation attackLayer(AbstractClientPlayer player) {
        try {
            if (attackField == null) {
                attackField = AbstractClientPlayer.class.getDeclaredField("attackAnimation");
                attackField.setAccessible(true);
            }
            return ((AttackAnimationSubStack) attackField.get(player)).base;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native BC layer unavailable", failure); }
    }

    public static boolean attackActive(AbstractClientPlayer player) { return attackLayer(player).isActive(); }
}
