package dev.caleb.emfattachments.test;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/** Test-only teleport/recovery grace. Callers wait beyond it before completing cleanup or starting an action. */
public final class ExpandedReactionTransition {
    public static final int GRACE_TICKS=25;
    private ExpandedReactionTransition() {}
    /** A fixture teleport must not be interpreted as gameplay acceleration. No suppression window. */
    public static void displaced(ServerPlayer player) {
        if(!player.getServer().isSameThread()) throw new IllegalStateException("Reaction transition must run on server");
        if(ModList.get().isLoaded("ragdoll_reactions")) Native.displaced(player);
    }
    public static boolean begin(ServerPlayer player) {
        if(!player.getServer().isSameThread()) throw new IllegalStateException("Reaction transition must run on server");
        if(!ModList.get().isLoaded("ragdoll_reactions")) return false;
        Native.begin(player); return true;
    }
    private static final class Native {
        static void begin(ServerPlayer player) {
            displaced(player);
            dev.leo.ragdollreactions.physics.ReactionSuppressions.suppress(player,player.serverLevel().getGameTime(),GRACE_TICKS);
        }
        static void displaced(ServerPlayer player) {
            dev.leo.ragdollreactions.physics.ImpactReactionHandler.onPlayerDisplaced(player);
            dev.leo.ragdollreactions.physics.ClientMotionTelemetry.clear(player.getUUID());
        }
    }
}
