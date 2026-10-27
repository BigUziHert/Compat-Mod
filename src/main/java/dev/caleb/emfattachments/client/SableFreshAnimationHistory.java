package dev.caleb.emfattachments.client;

import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import java.util.Map;

/** Moves Fresh Player's two position histories into the deck's current frame of reference. */
public final class SableFreshAnimationHistory {
    public static final String PREVIOUS_Y = "var.pre_posy";
    public static final String DRAGGED_Y = "var.verti_drag";
    private Object support;
    private Map<String, Float> variables;
    private float lastFrame = Float.NaN;
    private boolean previouslyGrounded;

    public void reset() {
        support = null;
        variables = null;
        lastFrame = Float.NaN;
        previouslyGrounded = false;
    }

    public void update(Map<String, Float> currentVariables, Object currentSupport,
                       float frame, float worldY, double ownVerticalMotion, boolean grounded) {
        if (currentSupport == null || !Float.isFinite(worldY) || !Double.isFinite(ownVerticalMotion)
                || !currentVariables.containsKey(PREVIOUS_Y) || !currentVariables.containsKey(DRAGGED_Y)) {
            reset();
            return;
        }
        if (isDuplicate(currentVariables, currentSupport, frame)) return;
        float previousY = read(currentVariables, PREVIOUS_Y);
        float draggedY = read(currentVariables, DRAGGED_Y);
        if (support == currentSupport && variables == currentVariables
                && Float.isFinite(previousY) && Float.isFinite(draggedY)) {
            double relativeMotion = ownVerticalMotion;
            // Inverse rotation can leave sub-micron roundoff on a stationary deck passenger.
            if (Math.abs(relativeMotion) < 1.0e-6) relativeMotion = 0;
            // An upright collision box settles slightly as a deck tilts beneath it. Fresh Player
            // treats even this sub-pixel grounded drift as another landing. The measured contact
            // correction was at most 1.04 mm; allow 2 mm only while contact remains continuous.
            // This is an animation tolerance, not a Sable physics constant or a movement change.
            if (grounded && previouslyGrounded && Math.abs(relativeMotion) <= 0.002) relativeMotion = 0;
            float movedPreviousY = worldY - (float) relativeMotion;
            float movedDraggedY = movedPreviousY + (draggedY - previousY);
            if (Float.isFinite(movedPreviousY) && Float.isFinite(movedDraggedY)) {
                write(currentVariables, PREVIOUS_Y, movedPreviousY);
                write(currentVariables, DRAGGED_Y, movedDraggedY);
            }
        }
        // Starting or changing support only establishes an anchor. It must not erase an actual landing.
        support = currentSupport;
        variables = currentVariables;
        lastFrame = frame;
        previouslyGrounded = grounded;
    }

    public boolean isDuplicate(Map<String, Float> currentVariables, Object currentSupport, float frame) {
        return support == currentSupport && variables == currentVariables && lastFrame == frame;
    }

    private static float read(Map<String, Float> values, String key) {
        return values instanceof Object2FloatMap<String> primitive ? primitive.getFloat(key) : values.get(key);
    }

    private static void write(Map<String, Float> values, String key, float value) {
        if (values instanceof Object2FloatMap<String> primitive) primitive.put(key, value);
        else values.put(key, value);
    }
}
