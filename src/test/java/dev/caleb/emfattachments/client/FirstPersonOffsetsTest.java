package dev.caleb.emfattachments.client;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirstPersonOffsetsTest {
    @Test void nativeScaleChangesBodyDistanceAndSwimmingHeightTogether() {
        Vec3 nativeOffset = new Vec3(0.2, 0.6, -0.25);
        for (double scale : new double[]{0.5, 1, 2}) {
            Vec3 result = FirstPersonOffsets.scaleOriginalBodyOffset(nativeOffset, scale);
            assertEquals(nativeOffset.x * scale, result.x, 1e-9);
            assertEquals(nativeOffset.z * scale, result.z, 1e-9);
            assertEquals(nativeOffset.y * scale, result.y, 1e-9);
        }
        assertSame(nativeOffset, FirstPersonOffsets.scaleOriginalBodyOffset(nativeOffset, 1));
    }

    @Test void rejectsInvalidScaleWithoutCorruptingRenderMatrices() {
        Vec3 offset = new Vec3(0.2, 0.6, -0.25);
        for (double scale : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertSame(offset, FirstPersonOffsets.scaleOriginalBodyOffset(offset, scale));
    }

    @Test void clearanceLeavesLevelViewAndSwimmingHeightUnchangedAndScalesWithBody() {
        Vec3 base = new Vec3(.2, .6, -.25);
        for (double pitch : new double[]{-90, 0, 40})
            assertSame(base, FirstPersonOffsets.addDownwardClearance(base, pitch, 0, 1, .45));
        for (double scale : new double[]{.5, 1, 2}) {
            Vec3 result = FirstPersonOffsets.addDownwardClearance(base, 90, 0, scale, .45);
            assertEquals(base.x, result.x, 1e-9);
            assertEquals(base.y, result.y, 0);
            assertEquals(base.z - .45 * scale, result.z, 1e-9);
        }
    }

    @Test void cameraYawControlsClearanceDirectionAndPitchTransitionIsBoundedAndContinuous() {
        Vec3 base = new Vec3(.2, .6, -.25);
        Vec3 east = FirstPersonOffsets.addDownwardClearance(base, 90, 90, 1, .45);
        assertEquals(base.x + .45, east.x, 1e-9);
        assertEquals(base.z, east.z, 1e-9);
        double previous = 0;
        for (int pitch = 40; pitch <= 95; pitch++) {
            double distance = FirstPersonOffsets.addDownwardClearance(base, pitch, 125, 1, .45).distanceTo(base);
            assertTrue(distance + 1e-10 >= previous && distance <= .45 + 1e-10);
            assertTrue(distance - previous < .014, "No frame-sized discontinuity at the activation threshold or vertical look");
            previous = distance;
        }
        assertTrue(FirstPersonOffsets.addDownwardClearance(base, 40.001, 0, 1, .45).distanceTo(base) < 1e-8);
    }

    @Test void clearanceOptOutAndInvalidCameraInputsDoNotMoveTheBody() {
        Vec3 base = new Vec3(.2, .6, -.25);
        assertSame(base, FirstPersonOffsets.addDownwardClearance(base, 90, 0, 1, 0));
        for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertSame(base, FirstPersonOffsets.addDownwardClearance(base, invalid, 0, 1, .45));
            assertSame(base, FirstPersonOffsets.addDownwardClearance(base, 90, invalid, 1, .45));
            assertSame(base, FirstPersonOffsets.addDownwardClearance(base, 90, 0, invalid, .45));
        }
    }
}
