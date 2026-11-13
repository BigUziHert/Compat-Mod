package dev.caleb.emfattachments.client;

import dev.caleb.emfattachments.compat.SableReducedTickAccess;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;

/** Reduced ticks are safe only after a live remote attachment is fully established. */
public final class SableReducedPlayerTick {
    private static final boolean FORCE_FULL_TICKS = Boolean.getBoolean("emfattachments.sableFullTicks");

    private SableReducedPlayerTick() {}

    public static boolean canReduce(Entity entity) {
        if (FORCE_FULL_TICKS || !(entity instanceof RemotePlayer player)
                || !(entity instanceof SableReducedTickAccess bridge)
                || !bridge.emfattachments$hasSableReducedTickBridge()
                || entity.isRemoved() || !player.isAlive() || player.isSleeping()
                || entity.isPassenger() || entity.isVehicle()) return false;
        var tracking = Sable.HELPER.getTrackingSubLevel(entity);
        var plot = ((EntityStickExtension) entity).sable$getPlotPosition();
        if (tracking == null || tracking.isRemoved() || plot == null) return false;
        return stableSupport(tracking, Sable.HELPER.getContaining(entity.level(), plot),
                Sable.HELPER.getVehicleSubLevel(entity), Sable.HELPER.getContaining(entity));
    }

    static boolean stableSupport(Object tracking, Object plotSupport, Object vehicle, Object containing) {
        return tracking != null && tracking == plotSupport && vehicle == null && containing == null;
    }

    public static boolean needsTransitionTick(Entity entity) {
        if (!(entity instanceof RemotePlayer) || !(entity instanceof SableReducedTickAccess bridge)
                || !bridge.emfattachments$hasSableReducedTickBridge()) return false;
        // A stale or clearing packet must get one complete native tick, even when
        // no live support remains for the historical full-tick resolver to find.
        return bridge.emfattachments$wasSableReducedTick()
                || Sable.HELPER.getTrackingSubLevel(entity) != null
                || ((EntityStickExtension) entity).sable$getPlotPosition() != null;
    }

    public static void beforeAiStep(Entity entity) {
        if (!canReduce(entity)) return;
        var bridge = (SableReducedTickAccess) entity;
        bridge.emfattachments$maintainSableTick();
        bridge.emfattachments$setSableReducedTick(true);
    }
}
