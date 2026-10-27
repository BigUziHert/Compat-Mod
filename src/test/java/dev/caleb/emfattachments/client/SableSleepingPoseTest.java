package dev.caleb.emfattachments.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SableSleepingPoseTest {
    @Test void nativeGeneralRotationExemptionsDoNotInspectBedState() {
        var bed = new Bed(true, true, true);
        assertFalse(SableSleepingPose.resolve(false, "native exemption", bed));
        assertTrue(bed.calls.isEmpty());
    }

    @Test void awakePlayersMobsAndServerStateKeepNativeRotationWithoutBedLookups() {
        var bed = new Bed(false, true, true);
        assertTrue(SableSleepingPose.resolve(true, "outside sleeping client player scope", bed));
        assertEquals(List.of("sleepingClientPlayer"), bed.calls);
    }

    @Test void missingDirectionPreservesGeneralRotationBecauseDedicatedHookWouldExit() {
        var bed = new Bed(true, false, true);
        assertTrue(SableSleepingPose.resolve(true, "sleep pose without valid bed facing", bed));
        assertEquals(List.of("sleepingClientPlayer", "bedDirection"), bed.calls);
    }

    @Test void OrdinaryBedsAndMissingOrRemovedSubLevelsKeepNativeBehavior() {
        var bed = new Bed(true, true, false);
        assertTrue(SableSleepingPose.resolve(true, "no live client sublevel bed", bed));
        assertEquals(List.of("sleepingClientPlayer", "bedDirection", "liveClientBed"), bed.calls);
    }

    @Test void liveSubLevelBedsUseOnlyTheirDedicatedSleepingTransform() {
        var bed = new Bed(true, true, true);
        assertFalse(SableSleepingPose.resolve(true, "local or remote sleeping player", bed));
        assertEquals(List.of("sleepingClientPlayer", "bedDirection", "liveClientBed"), bed.calls);
    }

    @Test void wakingOrLosingBedSupportImmediatelyRestoresGeneralRotation() {
        var bed = new Bed(true, true, true);
        assertFalse(SableSleepingPose.resolve(true, "sleeping", bed));
        bed.sleeping = false;
        assertTrue(SableSleepingPose.resolve(true, "awake", bed));
        bed.sleeping = true;
        bed.liveClientBed = false;
        assertTrue(SableSleepingPose.resolve(true, "bed sublevel unloaded", bed));
        bed.liveClientBed = true;
        assertFalse(SableSleepingPose.resolve(true, "new live sublevel bed", bed));
    }

    private static final class Bed implements SableSleepingPose.BedTransform<String> {
        boolean sleeping, direction, liveClientBed;
        final List<String> calls = new ArrayList<>();

        Bed(boolean sleeping, boolean direction, boolean liveClientBed) {
            this.sleeping = sleeping;
            this.direction = direction;
            this.liveClientBed = liveClientBed;
        }

        @Override public boolean isSleepingClientPlayer(String entity) {
            calls.add("sleepingClientPlayer");
            return sleeping;
        }

        @Override public boolean hasBedDirection(String entity) {
            calls.add("bedDirection");
            return direction;
        }

        @Override public boolean hasLiveClientBed(String entity) {
            calls.add("liveClientBed");
            return liveClientBed;
        }
    }
}
