package dev.caleb.emfattachments.client;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableElevatorReleaseMotionTest {
    private static Pose3d at(double x) {
        var pose = new Pose3d();
        pose.position().x = x;
        return pose;
    }

    private static SubLevelSnapshotInterpolator history(Pose3d a, int aTick, Pose3d b, int bTick) {
        var result = new SubLevelSnapshotInterpolator(a);
        result.receiveSnapshot(aTick, a);
        result.receiveSnapshot(bTick, b);
        return result;
    }

    @Test void normalizesBothDedicatedJitterPatternsAndPreservesWellPacedMotion() {
        var h = history(at(100), 10, at(102.5), 13);
        var pose = at(101.375);
        Vec3 feet = pose.transformPosition(new Vec3(3, 1, -4));
        for (double visual : new double[]{0, 1.328, 1.659}) {
            Vec3 v = SableElevatorReleaseMotion.sample(h, 11.65, pose, feet, new Vec3(visual, 0, 0));
            assertEquals(2.5 / 3, v.x, 1e-8);
            assertEquals(0, v.y);
            assertEquals(0, v.z);
        }
        Vec3 steady = new Vec3(.832, 0, 0);
        assertSame(steady, SableElevatorReleaseMotion.sample(h, 11.65, pose, feet, steady));
    }

    @Test void includesRotationAtTheRidersPointWithoutInjectingVerticalMovement() {
        var a = at(10);
        var b = at(10.25);
        a.rotationPoint().set(20_000_000, 2, 20_000_000);
        b.rotationPoint().set(a.rotationPoint());
        b.position().y = .3;
        b.orientation().rotateY(.11);
        var h = history(a, 50, b, 51);
        Vec3 local = new Vec3(20_000_003, 2, 20_000_004);
        Vec3 feet = b.transformPosition(local);
        Vec3 expected = feet.subtract(a.transformPosition(local));
        Vec3 inherited = SableElevatorReleaseMotion.sample(h, 51, b, feet, Vec3.ZERO);
        assertEquals(expected.x, inherited.x, 1e-8);
        assertEquals(expected.z, inherited.z, 1e-8);
        assertEquals(0, inherited.y);
    }

    @Test void limitsExtrapolationToSablesFreshTailAndHonorsItsStop() {
        var a = at(100);
        var b = at(101);
        var h = history(a, 10, b, 11);
        Vec3 feet = b.transformPosition(Vec3.ZERO);
        assertEquals(1, SableElevatorReleaseMotion.sample(h, 11.5, b, feet, Vec3.ZERO).x, 1e-8);
        h.receiveStop();
        assertEquals(Vec3.ZERO, SableElevatorReleaseMotion.sample(h, 11.5, b, feet, new Vec3(.4, 0, 0)));
        Vec3 fallback = new Vec3(.3, 0, 0);
        assertSame(fallback, SableElevatorReleaseMotion.sample(h, 13, b, feet, fallback));
        assertSame(fallback, SableElevatorReleaseMotion.sample(h, 9, b, feet, fallback));
        assertSame(fallback, SableElevatorReleaseMotion.sample(history(a, 1, b, 12), 5, b, feet, fallback));
        h.buffer.clear();
        assertSame(fallback, SableElevatorReleaseMotion.sample(h, 10, b, feet, fallback));
        assertEquals(Vec3.ZERO, SableElevatorReleaseMotion.sample(h, 10, b, feet, new Vec3(1.66, 0, 0)));
    }

    @Test void rejectsTeleportStalePoseAndNonfiniteInput() {
        var a = at(0);
        var b = at(10);
        var teleport = history(a, 5, b, 6);
        assertEquals(Vec3.ZERO, SableElevatorReleaseMotion.sample(teleport, 6, b, b.transformPosition(Vec3.ZERO), new Vec3(.8, 0, 0)));
        var fresh = history(a, 5, at(.8), 6);
        assertEquals(Vec3.ZERO, SableElevatorReleaseMotion.sample(fresh, 6, b, b.transformPosition(Vec3.ZERO), Vec3.ZERO));
        assertEquals(Vec3.ZERO, SableElevatorReleaseMotion.sample(fresh, 6, a, Vec3.ZERO, new Vec3(Double.NaN, 0, 0)));
        assertEquals(Vec3.ZERO, SableElevatorReleaseMotion.sample(fresh, 6, a, Vec3.ZERO, new Vec3(4, 0, 0)));
    }
}
