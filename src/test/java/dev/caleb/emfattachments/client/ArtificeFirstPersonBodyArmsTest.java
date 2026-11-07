package dev.caleb.emfattachments.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtificeFirstPersonBodyArmsTest {
    @Test void pistolReloadAndFireOccupancyHandoffTracksBothDominantHands() {
        for (boolean leftMain : new boolean[]{false, true}) {
            int heldArm = leftMain ? 2 : 1;
            assertEquals(heldArm, ArtificeFirstPersonBodyArms.armMask(true, false, true, false, false, leftMain));
            // Native reload/fire profile asks for the support bone as well.
            assertEquals(3, ArtificeFirstPersonBodyArms.armMask(true, false, true, true, false, leftMain));
            assertEquals(heldArm, ArtificeFirstPersonBodyArms.armMask(true, false, true, false, false, leftMain));
            assertEquals(0, ArtificeFirstPersonBodyArms.armMask(false, false, true, false, false, leftMain));
        }
    }

    @Test void otherNativeHandDrawsNeverGainAnExtraBodyCopy() {
        assertEquals(3, ArtificeFirstPersonBodyArms.armMask(true, false, false, false, false, false),
                "A held offhand item has its own native hand renderer");
        assertEquals(3, ArtificeFirstPersonBodyArms.armMask(true, true, false, false, false, false),
                "Two pistols own both physical hands");
        assertEquals(3, ArtificeFirstPersonBodyArms.armMask(false, true, false, false, false, false),
                "An offhand-only gun leaves vanilla's empty main-hand arm draw active");
        assertEquals(3, ArtificeFirstPersonBodyArms.armMask(true, false, true, false, true, true),
                "Native ALL_DOUBLE or scoping determines the other hand's visibility");
    }
}
