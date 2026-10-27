package dev.caleb.emfattachments.client;

import dev.ryanhcode.sable.companion.math.Pose3d;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableFreshAnimationHistoryTest {
    @Test void descendingDeckDoesNotResetFreshLandingAndRetainsExistingEasingLag() {
        var history = new SableFreshAnimationHistory();
        Object deck = new Object();
        Map<String, Float> variables = values(80, 80.2f);
        history.update(variables, deck, 1, 80, 0, true);
        history.update(variables, deck, 2, 79.5f, 0, true);
        assertEquals(79.5f, variables.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(0.2f, variables.get(SableFreshAnimationHistory.DRAGGED_Y) - 79.5f, 0.00001f);
        assertFalse(79.5f - variables.get(SableFreshAnimationHistory.PREVIOUS_Y) < 0,
                "Fresh Player resets t_land when on-ground world Y falls below its saved previous Y");
    }

    @Test void genuineJumpAndDescentRelativeToMovingDeckAreStillVisible() {
        var history = new SableFreshAnimationHistory();
        Object deck = new Object();
        Map<String, Float> variables = values(80, 80);
        history.update(variables, deck, 1, 80, 0, true);
        history.update(variables, deck, 2, 79.92f, 0.42, true);
        assertEquals(0.42f, 79.92f - variables.get(SableFreshAnimationHistory.PREVIOUS_Y), 0.00001f);
        variables.put(SableFreshAnimationHistory.PREVIOUS_Y, 79.92f);
        variables.put(SableFreshAnimationHistory.DRAGGED_Y, 79.92f);
        history.update(variables, deck, 3, 79.32f, -0.1, true);
        assertEquals(-0.1f, 79.32f - variables.get(SableFreshAnimationHistory.PREVIOUS_Y), 0.00001f);
    }

    @Test void firstAttachmentNewDeckAndDisembarkDoNotRewriteNativeLandingHistory() {
        var history = new SableFreshAnimationHistory();
        Map<String, Float> variables = values(85, 84);
        Map<String, Float> expected = new HashMap<>(variables);
        Object deck = new Object();
        history.update(variables, deck, 1, 80, 0, true);
        assertEquals(expected, variables);
        history.update(variables, new Object(), 2, 79, 0, true);
        assertEquals(expected, variables);
        history.reset();
        history.update(variables, deck, 3, 77, 0, true);
        assertEquals(expected, variables);
    }

    @Test void repeatedShadowAndHandPassesCannotApplyTheShiftTwice() {
        var history = new SableFreshAnimationHistory();
        Map<String, Float> variables = values(80, 80);
        Object deck = new Object();
        history.update(variables, deck, 1, 80, 0, true);
        history.update(variables, deck, 2, 79, 0, true);
        Map<String, Float> expected = new HashMap<>(variables);
        history.update(variables, deck, 2, 78, -0.5, true);
        assertEquals(expected, variables);
        history.update(variables, deck, 3, 78.5f, 0.5, true);
        assertEquals(78, variables.get(SableFreshAnimationHistory.PREVIOUS_Y));
    }

    @Test void mapReplacementAndInvalidSamplesCannotContaminateANewHistory() {
        var history = new SableFreshAnimationHistory();
        Object deck = new Object();
        Map<String, Float> variables = values(80, 80);
        history.update(variables, deck, 1, 80, 0, true);
        Map<String, Float> replacement = values(42, 43);
        history.update(replacement, deck, 2, 40, 0, true);
        assertEquals(42, replacement.get(SableFreshAnimationHistory.PREVIOUS_Y));
        history.update(replacement, deck, 3, Float.NaN, 0, true);
        history.update(replacement, deck, 4, 39, 0, true);
        assertEquals(42, replacement.get(SableFreshAnimationHistory.PREVIOUS_Y));
        history.update(replacement, deck, 5, 38, Double.POSITIVE_INFINITY, true);
        assertTrue(replacement.values().stream().allMatch(Float::isFinite));
    }

    @Test void actualSableTransformsRemoveDeckTranslationPitchRollAndScaleButPreserveWorldJump() {
        var state = new SableFreshAnimationState();
        Pose3d before = new Pose3d(), after = new Pose3d();
        before.position().set(100, 80, -30);
        after.position().set(110, 74, -25);
        before.rotationPoint().set(30000000, 12, 30000000);
        after.rotationPoint().set(before.rotationPoint());
        before.orientation().rotateYXZ(0.1, 0.3, -0.2);
        after.orientation().rotateYXZ(0.3, -0.1, 0.25);
        before.scale().set(0.75, 0.75, 0.75);
        after.scale().set(0.5, 0.5, 0.5);
        Vector3d local = new Vector3d(30000003, 13, 30000002);
        Vector3d oldWorld = before.transformPosition(local, new Vector3d());
        Vector3d newWorld = after.transformPosition(local, new Vector3d());
        state.sampleOwnVerticalMotion(before, after,
                oldWorld.x, oldWorld.y, oldWorld.z, newWorld.x, newWorld.y, newWorld.z, 0);
        for (float partial : new float[]{0, 0.1f, 0.5f, 0.9f, 1}) {
            assertEquals(0, state.sampleOwnVerticalMotion(before, after,
                    oldWorld.x, oldWorld.y, oldWorld.z, newWorld.x, newWorld.y, newWorld.z, partial), 1e-7);
        }
        // The actual player jumps by 0.42 world blocks, irrespective of the deck's half scale or tilt.
        newWorld.add(0, 0.42, 0);
        assertEquals(0.42, state.sampleOwnVerticalMotion(after, after,
                newWorld.x, newWorld.y, newWorld.z, newWorld.x, newWorld.y, newWorld.z, 1), 1e-7);
        newWorld.add(0, -0.15, 0);
        assertEquals(-0.15, state.sampleOwnVerticalMotion(after, after,
                newWorld.x, newWorld.y, newWorld.z, newWorld.x, newWorld.y, newWorld.z, 1), 1e-7);
    }

    @Test void invalidTransformAndSkippedAnimationFramesDoNotLoseRealMovement() {
        var state = new SableFreshAnimationState();
        Pose3d pose = new Pose3d();
        Map<String, Float> variables = values(80, 80);
        Object deck = new Object();
        state.update(variables, deck, 1, 80, true, pose, pose, 0, 80, 0, 0, 80, 0, 1);
        // Nothing calls update on EMF's cached LOD frames; movement accumulates to the next actual evaluation.
        pose.position().y = -5;
        state.update(variables, deck, 5, 76, true, pose, pose, 0, 76, 0, 0, 76, 0, 1);
        assertEquals(1, 76 - variables.get(SableFreshAnimationHistory.PREVIOUS_Y), 1e-6);
        Pose3d singular = new Pose3d(); singular.scale().y = 0;
        state.update(variables, deck, 6, 70, true, singular, singular, 0, 70, 0, 0, 70, 0, 1);
        assertEquals(75, variables.get(SableFreshAnimationHistory.PREVIOUS_Y));
        state.update(variables, deck, 7, 71, true, pose, pose, 0, 71, 0, 0, 71, 0, 1);
        assertEquals(75, variables.get(SableFreshAnimationHistory.PREVIOUS_Y), "Invalid transform breaks the anchor");
    }

    @Test void primitiveMapUsesSamePolicyAndFloatingPointNoiseDoesNotProduceLandingSquash() {
        var history = new SableFreshAnimationHistory();
        var variables = new Object2FloatOpenHashMap<String>();
        variables.putAll(values(0, 0));
        Object deck = new Object();
        history.update(variables, deck, 27719, 0, 0, true);
        history.update(variables, deck, 0, -0.01f, -1e-9, true);
        assertEquals(-0.01f, variables.getFloat(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(-0.01f, variables.getFloat(SableFreshAnimationHistory.DRAGGED_Y));
    }

    @Test void onlyContinuousGroundContactSuppressesTheMeasuredMillimetricRockingCorrection() {
        Object deck = new Object();
        var grounded = new SableFreshAnimationHistory();
        Map<String, Float> values = values(80, 80);
        grounded.update(values, deck, 1, 80, 0, true);
        grounded.update(values, deck, 2, 79.99896f, -0.0010397, true);
        assertEquals(79.99896f, values.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(0.75f, values.get("var.sneak"), "Intentional crouch easing is never changed");
        assertEquals(0.8f, values.get("var.t_land"), "Native landing timer is never forced");

        for (boolean groundedNow : new boolean[]{false, true}) {
            var airborne = new SableFreshAnimationHistory();
            Map<String, Float> flightValues = values(80, 80);
            airborne.update(flightValues, deck, 1, 80, 0, false);
            airborne.update(flightValues, deck, 2, 79.99896f, -0.0010397, groundedNow);
            assertEquals(-0.0010397, 79.99896f - flightValues.get(SableFreshAnimationHistory.PREVIOUS_Y), 5e-6,
                    "Tiny actual falling motion and the first grounded sample remain visible");
        }
    }

    @Test void realLandingAndLargerGroundedStepsKeepTheirVerticalAmplitude() {
        Object deck = new Object();
        for (double movement : new double[]{-0.86505534, -0.01, 0.01, -0.0021, 0.0021}) {
            var history = new SableFreshAnimationHistory();
            Map<String, Float> values = values(80, 80);
            history.update(values, deck, 1, 80, 0, true);
            history.update(values, deck, 2, (float)(80 + movement), movement, true);
            assertEquals(movement, (float)(80 + movement) - values.get(SableFreshAnimationHistory.PREVIOUS_Y), 5e-6);
        }
    }

    private static Map<String, Float> values(float previous, float dragged) {
        Map<String, Float> values = new HashMap<>();
        values.put(SableFreshAnimationHistory.PREVIOUS_Y, previous);
        values.put(SableFreshAnimationHistory.DRAGGED_Y, dragged);
        values.put("var.sneak", 0.75f);
        values.put("var.t_land", 0.8f);
        return values;
    }
}
