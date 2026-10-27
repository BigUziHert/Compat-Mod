package dev.caleb.emfattachments.test;

import com.github.L_Ender.cataclysm.entity.projectile.Tidal_Hook_Entity;
import com.github.L_Ender.cataclysm.init.ModDataAttachments;
import dev.caleb.emfattachments.compat.TidalGrappleImpactGuard;
import dev.leo.ragdollreactions.physics.ImpactReactionHandler;
import dev.leo.ragdollreactions.physics.ReactionSuppressions;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Actual transformed impact entry point, with the real natively launched hook. */
public final class TidalImpactProbe {
    private TidalImpactProbe() {}

    public static Map<String,Object> whilePulling(ServerPlayer player, Tidal_Hook_Entity hook) throws Exception {
        requireServer(player);
        ListTag inventory = player.getInventory().save(new ListTag());
        Field pulling = field(Tidal_Hook_Entity.class, "isPulling");
        Field stack = field(Tidal_Hook_Entity.class, "stack");
        Field ownerUuid = field(Projectile.class, "ownerUUID");
        Field cachedOwner = field(Projectile.class, "cachedOwner");
        boolean originalPulling = pulling.getBoolean(hook);
        ItemStack originalStack = (ItemStack) stack.get(hook);
        Entity originalOwner = hook.getOwner();
        Object originalOwnerUuid = ownerUuid.get(hook), originalCachedOwner = cachedOwner.get(hook);
        Vec3 originalPosition = hook.position();
        boolean originalFalling = player.getData(ModDataAttachments.HOOK_FALLING);
        var rows = new ArrayList<Map<String,Object>>();
        try {
            rows.add(check(player, "actual_native_owned_pull", true));
            pulling.setBoolean(hook, false);
            rows.add(check(player, "owned_hook_not_latched", false));
            pulling.setBoolean(hook, originalPulling);
            stack.set(hook, originalStack.copy());
            rows.add(check(player, "matching_item_different_stack_identity", false));
            stack.set(hook, originalStack);
            // Projectile.setOwner(null) is a no-op; AbstractArrow.setOwner also
            // alters pickup mode. Clear only the two ownership fields and restore.
            ownerUuid.set(hook, null); cachedOwner.set(hook, null);
            rows.add(check(player, "unowned_hook", false));
            ownerUuid.set(hook, originalOwnerUuid); cachedOwner.set(hook, originalCachedOwner);
            player.setData(ModDataAttachments.HOOK_FALLING, false);
            rows.add(check(player, "native_hook_flag_inactive", false));
            player.setData(ModDataAttachments.HOOK_FALLING, originalFalling);
            hook.setPos(player.position().add(32, 0, 0));
            rows.add(check(player, "outside_native_maximum_range", false));
            hook.setPos(originalPosition);
            rows.add(check(player, "native_pull_after_controls_restored", true));
        } finally {
            pulling.setBoolean(hook, originalPulling); stack.set(hook, originalStack);
            ownerUuid.set(hook, originalOwnerUuid); cachedOwner.set(hook, originalCachedOwner); hook.setPos(originalPosition);
            player.setData(ModDataAttachments.HOOK_FALLING, originalFalling);
        }
        boolean restored = inventory.equals(player.getInventory().save(new ListTag()))
                && pulling.getBoolean(hook) == originalPulling && stack.get(hook) == originalStack
                && hook.getOwner() == originalOwner && hook.position().equals(originalPosition)
                && player.getData(ModDataAttachments.HOOK_FALLING) == originalFalling;
        if (!restored) throw new IllegalStateException("Tidal impact control state did not restore");
        return Map.of("status", "PASS", "rows", rows, "stateRestored", true,
                "scope", "Actual transformed impact heuristic invoked on the real native hook. Negative controls verify ordinary motion tracking remains reachable; no artificial hit, fall or explosion is generated.");
    }

    public static Map<String,Object> afterRelease(ServerPlayer player) throws Exception {
        requireServer(player);
        return check(player, "after_native_release_ordinary_impact_tracking", false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> check(ServerPlayer player, String name, boolean expected) throws Exception {
        if (ReactionSuppressions.isTemporarilySuppressed(player.serverLevel(), player, player.level().getGameTime()))
            throw new IllegalStateException("Native reaction grace would invalidate control: " + name);
        boolean actual = TidalGrappleImpactGuard.isNativePulling(player);
        String[] names = {"LAST_PLAYER_POSITION", "PLAYER_MOTION_HISTORY", "LAST_PLAYER_SAMPLE_TICK", "LAST_PLAYER_SAMPLE_NANOS"};
        var maps = new ArrayList<Map<UUID,Object>>();
        var saved = new ArrayList<Object>(); var present = new ArrayList<Boolean>();
        UUID uuid = player.getUUID();
        for (String fieldName : names) {
            Map<UUID,Object> map = (Map<UUID,Object>) field(ImpactReactionHandler.class, fieldName).get(null);
            maps.add(map); present.add(map.containsKey(uuid)); saved.add(map.get(uuid)); map.remove(uuid);
        }
        boolean tracked;
        try {
            Method method = ImpactReactionHandler.class.getDeclaredMethod("tryTriggerReaction", ServerLevel.class, ServerPlayer.class);
            method.setAccessible(true); method.invoke(null, player.serverLevel(), player);
            tracked = maps.getFirst().containsKey(uuid);
        } finally {
            for (int i = 0; i < maps.size(); i++) {
                maps.get(i).remove(uuid); if (present.get(i)) maps.get(i).put(uuid, saved.get(i));
            }
        }
        if (actual != expected || tracked == expected)
            throw new IllegalStateException(name + ": expectedGuard=" + expected + ", actualGuard=" + actual + ", ordinaryImpactTracking=" + tracked);
        return Map.of("name", name, "expectedNativePullGuard", expected, "actualNativePullGuard", actual,
                "ordinaryImpactTrackingReached", tracked, "trackingStateRestored", true);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void requireServer(ServerPlayer player) {
        if (!player.getServer().isSameThread()) throw new IllegalStateException("Server-thread probe only");
    }
}
