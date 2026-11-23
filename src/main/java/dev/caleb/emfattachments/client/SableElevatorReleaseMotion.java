package dev.caleb.emfattachments.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.phys.Vec3;

/** Converts server pose history into the horizontal velocity inherited on leaving a cabin. */
final class SableElevatorReleaseMotion {
    static final double MAX_STEP = 1.5;

    private SableElevatorReleaseMotion() {}

    static Vec3 forRelease(SubLevel carrier, Vec3 feet, Vec3 poseStep) {
        if (!(carrier instanceof ClientSubLevel client)) return bounded(poseStep);
        var container = SubLevelContainer.getContainer(client.getLevel());
        if (container == null) return bounded(poseStep);
        var clock = container.getInterpolation();
        if (clock.isStopped()) return Vec3.ZERO;
        return sample(client.getInterpolator(), clock.mostRecentInterpolationTick, client.logicalPose(), feet, poseStep);
    }

    static Vec3 sample(SubLevelSnapshotInterpolator interpolator, double tick, Pose3dc current, Vec3 feet, Vec3 poseStep) {
        // A catch-up frame can cover two normal ticks. A larger discontinuity is unsafe to inherit.
        if (!Double.isFinite(poseStep.y) || !(poseStep.horizontalDistanceSqr() <= 4 * MAX_STEP * MAX_STEP)) return Vec3.ZERO;
        var history = interpolator.buffer;
        synchronized (history) {
            if (history.size() < 2 || !Double.isFinite(tick)) return bounded(poseStep);
            int first = history.getFirst().gameTick(), last = history.getLast().gameTick();
            // The upstream interpolator retains six ticks and extrapolates only one tick past its last packet.
            if (tick < first || tick > (double) last + 1 || first >= last) return bounded(poseStep);
            double from = Math.max(first, tick - 1);
            if (tick > last) from = last;
            for (int i = 1; i < history.size(); i++) {
                int a = history.get(i - 1).gameTick(), b = history.get(i).gameTick();
                if (a >= b || a < from + 1 && b >= from && (long) b - a > 6) return bounded(poseStep);
            }
            var previous = new Pose3d();
            var next = new Pose3d();
            interpolator.getSampleAt(from, previous);
            interpolator.getSampleAt(from + 1, next);
            // Use one local point in both poses so a turn contributes the rider's tangential velocity.
            Vec3 local = current.transformPositionInverse(feet);
            Vec3 endpoint = next.transformPosition(local);
            if (!(endpoint.distanceToSqr(feet) <= 4 * MAX_STEP * MAX_STEP)) return Vec3.ZERO;
            Vec3 step = endpoint.subtract(previous.transformPosition(local)).multiply(1, 0, 1);
            if (!Double.isFinite(step.y)) return Vec3.ZERO;
            if (!(step.horizontalDistanceSqr() <= MAX_STEP * MAX_STEP)) return Vec3.ZERO;
            if (step.horizontalDistanceSqr() <= 1e-8) return Vec3.ZERO;
            return poseStep.horizontalDistanceSqr() <= MAX_STEP * MAX_STEP && step.distanceToSqr(poseStep) <= 1e-4
                    ? bounded(poseStep) : step;
        }
    }

    private static Vec3 bounded(Vec3 step) {
        if (!Double.isFinite(step.y) || !(step.horizontalDistanceSqr() <= MAX_STEP * MAX_STEP)) return Vec3.ZERO;
        return step.y == 0 ? step : new Vec3(step.x, 0, step.z);
    }
}
