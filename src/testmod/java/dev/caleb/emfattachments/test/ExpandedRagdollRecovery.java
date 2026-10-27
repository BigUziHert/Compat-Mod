package dev.caleb.emfattachments.test;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

/** Native cleanup between independent fixtures; never suppresses a ragdoll during a case. */
public final class ExpandedRagdollRecovery {
    private ExpandedRagdollRecovery() {}

    /** Call on the integrated server before equipping/teleporting the next independent case. */
    public static boolean release(ServerPlayer player) {
        if (player.getServer() == null || !player.getServer().isSameThread())
            throw new IllegalStateException("Ragdoll release must run on the server thread");
        if (!ModList.get().isLoaded("sable_player_ragdoll")) return false;
        return Native.release(player);
    }

    public static boolean active(ServerPlayer player) {
        return ModList.get().isLoaded("sable_player_ragdoll") && Native.active(player);
    }

    /** Read-only installed native request queue; never cancels or resolves a pose request. */
    public static boolean pending(ServerPlayer player) {
        return ModList.get().isLoaded("sable_player_ragdoll") && Native.pending(player);
    }

    /** Wait for native seat removal; callers also record visibility against their baseline. */
    public static boolean clientRestored(LivingEntity player) {
        if (player.getVehicle() == null) return true;
        return !BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType())
                .getNamespace().equals("sable_player_ragdoll");
    }

    private static final class Native {
        private static java.lang.reflect.Field pendingPlayers;
        static boolean pending(ServerPlayer player) {
            try {
                if(pendingPlayers==null) {
                    pendingPlayers=dev.leo.sableplayerragdoll.api.RagdollAsyncPoseRequests.class.getDeclaredField("PENDING_PLAYERS");
                    pendingPlayers.setAccessible(true);
                }
                return ((java.util.Set<?>)pendingPlayers.get(null)).contains(player.getUUID());
            } catch(ReflectiveOperationException e) { throw new IllegalStateException("Native pending pose queue unavailable",e); }
        }
        static boolean active(ServerPlayer player) {
            return dev.leo.sableplayerragdoll.api.RagdollAPI.isRagdolled(player);
        }

        static boolean release(ServerPlayer player) {
            var session = dev.leo.sableplayerragdoll.api.RagdollAPI.activeSession(player);
            if (session == null) return false;
            // release() performs the complete native lifecycle: unregistering the
            // forced-remount session, removing physical limbs/seat, restoring the
            // source player's visibility, firing the end event and cooldown.
            session.release();
            if (active(player)) throw new IllegalStateException("Native ragdoll release left an active session");
            return true;
        }
    }
}
