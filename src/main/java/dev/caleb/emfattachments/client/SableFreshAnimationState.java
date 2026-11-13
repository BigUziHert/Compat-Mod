package dev.caleb.emfattachments.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import java.util.Map;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Per-client-player storage; no global player cache, per-frame allocation, or tick listener. */
public final class SableFreshAnimationState {
    public final SableFreshAnimationHistory history = new SableFreshAnimationHistory();
    private SableFreshAnimationState firstPersonHandState;
    // Core 2.4+ needs only the small history guard, never these legacy matrices.
    private Matrix4d interpolated;
    private Matrix4d currentPoseMatrix;
    private Matrix4d inverse;
    private Vector3d previousLocalPosition;
    private Vector3d carriedPosition;

    /** EMF's hand map advances independently of the normal world/shadow map. */
    public SableFreshAnimationState forRenderContext(boolean firstPersonHand) {
        if (!firstPersonHand) return this;
        if (firstPersonHandState == null) firstPersonHandState = new SableFreshAnimationState();
        return firstPersonHandState;
    }

    /** Leaving support invalidates both histories, including a hand pass that is not currently drawn. */
    public void resetRenderContexts() {
        history.reset();
        if (firstPersonHandState != null) firstPersonHandState.history.reset();
    }

    /** Leaves the player's own world-vertical motion in world units, including on scaled decks. */
    public double sampleOwnVerticalMotion(Pose3dc previousPose, Pose3dc currentPose,
                            double previousX, double previousY, double previousZ,
                            double currentX, double currentY, double currentZ, float partialTick) {
        if (interpolated == null) {
            interpolated = new Matrix4d();
            currentPoseMatrix = new Matrix4d();
            inverse = new Matrix4d();
            previousLocalPosition = new Vector3d();
            carriedPosition = new Vector3d();
        }
        previousPose.bakeIntoMatrix(interpolated);
        currentPose.bakeIntoMatrix(currentPoseMatrix);
        // EMF interpolates the entity's tick endpoints linearly. Use the same interpolation
        // for a fixed local point, rather than introducing a second quaternion interpolation.
        interpolated.lerp(currentPoseMatrix, partialTick);
        double determinant = interpolated.determinant3x3();
        if (!Double.isFinite(determinant) || Math.abs(determinant) < 1e-12) return Double.NaN;
        double x = previousX + partialTick * (currentX - previousX);
        double y = previousY + partialTick * (currentY - previousY);
        double z = previousZ + partialTick * (currentZ - previousZ);
        interpolated.transformPosition(previousLocalPosition, carriedPosition);
        double ownVerticalMotion = y - carriedPosition.y;
        interpolated.invertAffine(inverse).transformPosition(previousLocalPosition.set(x, y, z));
        return ownVerticalMotion;
    }

    public void update(Map<String, Float> variables, Object support, float frame, float worldY,
                       boolean grounded,
                       Pose3dc previousPose, Pose3dc currentPose,
                       double previousX, double previousY, double previousZ,
                       double currentX, double currentY, double currentZ, float partialTick) {
        if (history.isDuplicate(variables, support, frame)) return;
        double motion = sampleOwnVerticalMotion(previousPose, currentPose,
                previousX, previousY, previousZ, currentX, currentY, currentZ, partialTick);
        history.update(variables, support, frame, worldY, motion, grounded);
    }

    public interface Access {
        SableFreshAnimationState emfattachments$getSableFreshAnimationState(boolean create);
    }

    public interface AnimationAccess {
        void emfattachments$beforeFreshEvaluation();
    }
}
