package dev.caleb.emfattachments.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SablePlayerOcclusionTest {
    @Test void nativeExemptionsNeverInspectOptionalSableState() {
        var lookup = new Lookup(false, false, false, false);
        assertTrue(SablePlayerOcclusion.resolve(true, "local player or noCulling entity", lookup));
        assertTrue(lookup.calls.isEmpty());
    }

    @Test void mobsAndServerEntitiesKeepNativePolicyWithoutAttachmentLookups() {
        var lookup = new Lookup(false, true, true, true);
        assertFalse(SablePlayerOcclusion.resolve(false, "not a client player", lookup));
        assertEquals(List.of("clientPlayer"), lookup.calls);
    }

    @Test void ordinaryPlayersKeepTickCullingAndDetachedPlayersResumeItImmediately() {
        var lookup = new Lookup(true, false, false, false);
        assertFalse(SablePlayerOcclusion.resolve(false, "ordinary player", lookup));
        lookup.tracking = new Support();
        assertTrue(SablePlayerOcclusion.resolve(false, "same player boarded", lookup));
        lookup.tracking = null;
        assertFalse(SablePlayerOcclusion.resolve(false, "same player left", lookup));
    }

    @Test void trackedPlayersNeedNoFurtherSubLevelSearch() {
        var lookup = new Lookup(true, true, false, false);
        assertTrue(SablePlayerOcclusion.resolve(false, "tracked or mounted", lookup));
        assertEquals(List.of("clientPlayer", "tracking"), lookup.calls);
    }

    @Test void pendingRemotePacketCanRecoverBeforeFirstTrackingTick() {
        var lookup = new Lookup(true, false, true, false);
        assertTrue(SablePlayerOcclusion.resolve(false, "remote player pending plot tick", lookup));
        assertEquals(List.of("clientPlayer", "tracking", "vehicle", "pendingPlot"), lookup.calls);
        lookup.pending = null;
        assertFalse(SablePlayerOcclusion.resolve(false, "plot unloaded or remote dismounted", lookup));
    }

    @Test void plotContainedPlayersKeepFullTicksWithoutPersistingAVisibilityFlag() {
        var lookup = new Lookup(true, false, false, true);
        assertTrue(SablePlayerOcclusion.resolve(false, "player in plot space", lookup));
        lookup.contained = null;
        assertFalse(SablePlayerOcclusion.resolve(false, "returned to world", lookup));
    }

    @Test void removedTrackingReferenceCannotMaskALiveSeatFallback() {
        var lookup = new Lookup(true, true, false, false);
        lookup.tracking.removed = true;
        lookup.vehicle = new Support();
        assertTrue(SablePlayerOcclusion.resolve(false, "stale tracked deck but live seat", lookup));
        assertEquals(List.of("clientPlayer", "tracking", "vehicle"), lookup.calls);
        lookup.vehicle.removed = true;
        assertFalse(SablePlayerOcclusion.resolve(false, "both decks removed", lookup));
    }

    @Test void everyAttachmentPathStopsExemptingRemovedSubLevelsImmediately() {
        var lookup = new Lookup(true, true, true, true);
        lookup.vehicle = new Support();
        lookup.tracking.removed = true;
        lookup.vehicle.removed = true;
        lookup.pending.removed = true;
        lookup.contained.removed = true;
        assertFalse(SablePlayerOcclusion.resolve(false, "all attachment pointers stale", lookup));
        lookup.pending = new Support();
        assertTrue(SablePlayerOcclusion.resolve(false, "new live plot packet", lookup));
        lookup.pending.removed = true;
        assertFalse(SablePlayerOcclusion.resolve(false, "new plot removed too", lookup));
    }

    private static final class Support {
        boolean removed;
    }

    private static final class Lookup implements SablePlayerOcclusion.Attachments<String, Support> {
        final boolean player;
        Support tracking, vehicle, pending, contained;
        final List<String> calls = new ArrayList<>();
        Lookup(boolean player, boolean tracking, boolean pending, boolean contained) {
            this.player = player;
            this.tracking = tracking ? new Support() : null;
            this.pending = pending ? new Support() : null;
            this.contained = contained ? new Support() : null;
        }
        @Override public boolean isClientPlayer(String entity) { calls.add("clientPlayer"); return player; }
        @Override public Support tracking(String entity) { calls.add("tracking"); return tracking; }
        @Override public Support vehicle(String entity) { calls.add("vehicle"); return vehicle; }
        @Override public Support pendingPlotPosition(String entity) { calls.add("pendingPlot"); return pending; }
        @Override public Support containing(String entity) { calls.add("contained"); return contained; }
        @Override public boolean isRemoved(Support support) { return support.removed; }
    }
}
