package dev.caleb.emfattachments.client;

import dev.ryanhcode.sable.companion.math.Pose3d;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableFreshUpstreamHeightTest {
    private static final float BEFORE_PULSE = -47.474113f;
    private static final float AFTER_PULSE = -47.474117f;

    @Test void observedCoreHeightPulseCannotRestartFreshLandingAndKeepsExistingDragLag() {
        var history = new SableFreshAnimationHistory();
        Object deck = new Object();
        Map<String, Float> variables = values(BEFORE_PULSE, BEFORE_PULSE + 0.125f);
        history.updateUpstreamHeight(variables, deck, 1, BEFORE_PULSE, true);
        history.updateUpstreamHeight(variables, deck, 2, AFTER_PULSE, true);

        assertEquals(AFTER_PULSE, variables.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(0.125f, variables.get(SableFreshAnimationHistory.DRAGGED_Y) - AFTER_PULSE);
        assertFalse(AFTER_PULSE - variables.get(SableFreshAnimationHistory.PREVIOUS_Y) < 0,
                "Fresh resets its landing timer when grounded pos_y falls below pre_posy");
        assertEquals(0.8f, variables.get("var.t_land"));
        assertEquals(0.75f, variables.get("var.sneak"));
    }

    @Test void adjacentDownwardFloatsWorkOnEitherSideOfZeroAndAcrossExponentBoundaries() {
        for (float before : new float[]{-64, -1, -Float.MIN_NORMAL, 0, Float.MIN_NORMAL, 1, 64, 1024}) {
            var history = new SableFreshAnimationHistory();
            Object deck = new Object();
            Map<String, Float> variables = values(before, before);
            float after = Math.nextDown(before);
            history.updateUpstreamHeight(variables, deck, 1, before, true);
            history.updateUpstreamHeight(variables, deck, 2, after, true);
            assertEquals(after, variables.get(SableFreshAnimationHistory.PREVIOUS_Y), "height " + before);
            assertEquals(after, variables.get(SableFreshAnimationHistory.DRAGGED_Y), "height " + before);
        }
    }

    @Test void largerChangesPositiveChangesAndRealStepsRetainNativeHistory() {
        for (float after : new float[]{BEFORE_PULSE, Math.nextUp(BEFORE_PULSE),
                Math.nextDown(AFTER_PULSE), Math.nextDown(Math.nextDown(AFTER_PULSE)),
                BEFORE_PULSE - 0.002f, BEFORE_PULSE - 0.5f, BEFORE_PULSE + 0.5f}) {
            var history = new SableFreshAnimationHistory();
            Object deck = new Object();
            Map<String, Float> variables = values(BEFORE_PULSE, BEFORE_PULSE + 0.125f);
            Map<String, Float> expected = new HashMap<>(variables);
            history.updateUpstreamHeight(variables, deck, 1, BEFORE_PULSE, true);
            history.updateUpstreamHeight(variables, deck, 2, after, true);
            assertEquals(expected, variables, "height " + after);
        }
    }

    @Test void largeFloatSpacingAtExtremeAltitudeCannotHideMeaningfulMovement() {
        for (float before : new float[]{2048, -1024, 1_000_000, -1_000_000, Float.MAX_VALUE}) {
            var history = new SableFreshAnimationHistory();
            Object deck = new Object();
            Map<String, Float> variables = values(before, before);
            Map<String, Float> expected = new HashMap<>(variables);
            history.updateUpstreamHeight(variables, deck, 1, before, true);
            history.updateUpstreamHeight(variables, deck, 2, Math.nextDown(before), true);
            assertEquals(expected, variables, "height " + before);
        }
    }

    @Test void firstAttachmentSupportChangeMapReplacementAndResetOnlyEstablishAnAnchor() {
        var history = new SableFreshAnimationHistory();
        Object deck = new Object();
        Map<String, Float> variables = values(BEFORE_PULSE, BEFORE_PULSE);
        Map<String, Float> expected = new HashMap<>(variables);
        history.updateUpstreamHeight(variables, deck, 1, AFTER_PULSE, true);
        assertEquals(expected, variables, "First attachment may be a real landing");
        Object otherDeck = new Object();
        history.updateUpstreamHeight(variables, otherDeck, 2, AFTER_PULSE, true);
        assertEquals(expected, variables, "Changing support may be a real landing");
        Map<String, Float> replacement = new HashMap<>(variables);
        history.updateUpstreamHeight(replacement, otherDeck, 3, AFTER_PULSE, true);
        assertEquals(expected, replacement, "A new EMF variable map has its own history");
        history.reset();
        history.updateUpstreamHeight(replacement, otherDeck, 4, AFTER_PULSE, true);
        assertEquals(expected, replacement, "Reset invalidates the old contact anchor");
        history.updateUpstreamHeight(replacement, otherDeck, 5, AFTER_PULSE, true);
        assertEquals(AFTER_PULSE, replacement.get(SableFreshAnimationHistory.PREVIOUS_Y));
    }

    @Test void airborneMotionAndTheFirstGroundedSampleKeepEvenOneFloatStep() {
        for (boolean[] contact : new boolean[][]{{false, false}, {true, false}, {false, true}}) {
            var history = new SableFreshAnimationHistory();
            Object deck = new Object();
            Map<String, Float> variables = values(BEFORE_PULSE, BEFORE_PULSE);
            Map<String, Float> expected = new HashMap<>(variables);
            history.updateUpstreamHeight(variables, deck, 1, BEFORE_PULSE, contact[0]);
            history.updateUpstreamHeight(variables, deck, 2, AFTER_PULSE, contact[1]);
            assertEquals(expected, variables, "Only continuous ground contact permits precision alignment");
        }
    }

    @Test void repeatedPassCannotConsumeAnotherFloatStepButLaterEvaluationCan() {
        var history = new SableFreshAnimationHistory();
        Object deck = new Object();
        Map<String, Float> variables = values(BEFORE_PULSE, BEFORE_PULSE);
        history.updateUpstreamHeight(variables, deck, 1, BEFORE_PULSE, true);
        history.updateUpstreamHeight(variables, deck, 2, AFTER_PULSE, true);
        float next = Math.nextDown(AFTER_PULSE);
        history.updateUpstreamHeight(variables, deck, 2, next, true);
        assertEquals(AFTER_PULSE, variables.get(SableFreshAnimationHistory.PREVIOUS_Y));
        history.updateUpstreamHeight(variables, deck, 5, next, true);
        assertEquals(next, variables.get(SableFreshAnimationHistory.PREVIOUS_Y),
                "Skipped cached LOD frames do not consume the next actual evaluation");
    }

    @Test void invalidInputOrMissingSupportInvalidatesTheAnchor() {
        for (int invalid = 0; invalid < 6; invalid++) {
            var history = new SableFreshAnimationHistory();
            Object deck = new Object();
            Map<String, Float> variables = values(BEFORE_PULSE, BEFORE_PULSE);
            history.updateUpstreamHeight(variables, deck, 1, BEFORE_PULSE, true);
            if (invalid == 0) history.updateUpstreamHeight(variables, null, 2, AFTER_PULSE, true);
            if (invalid == 1) history.updateUpstreamHeight(variables, deck, 2, Float.NaN, true);
            if (invalid == 2) history.updateUpstreamHeight(variables, deck, 2, Float.POSITIVE_INFINITY, true);
            if (invalid == 3) history.updateUpstreamHeight(variables, deck, Float.NaN, AFTER_PULSE, true);
            if (invalid == 4 || invalid == 5) {
                variables.remove(invalid == 4 ? SableFreshAnimationHistory.PREVIOUS_Y : SableFreshAnimationHistory.DRAGGED_Y);
                history.updateUpstreamHeight(variables, deck, 2, AFTER_PULSE, true);
                variables.putAll(values(BEFORE_PULSE, BEFORE_PULSE));
            }
            history.updateUpstreamHeight(variables, deck, 3, AFTER_PULSE, true);
            assertEquals(BEFORE_PULSE, variables.get(SableFreshAnimationHistory.PREVIOUS_Y), "case " + invalid);
            history.updateUpstreamHeight(variables, deck, 4, AFTER_PULSE, true);
            assertEquals(AFTER_PULSE, variables.get(SableFreshAnimationHistory.PREVIOUS_Y), "case " + invalid);
        }
    }

    @Test void nonFiniteSavedValuesAreNeverWrittenIntoOtherHistoryEntries() {
        for (String key : new String[]{SableFreshAnimationHistory.PREVIOUS_Y, SableFreshAnimationHistory.DRAGGED_Y}) {
            for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                var history = new SableFreshAnimationHistory();
                Object deck = new Object();
                Map<String, Float> variables = values(BEFORE_PULSE, BEFORE_PULSE);
                history.updateUpstreamHeight(variables, deck, 1, BEFORE_PULSE, true);
                variables.put(key, invalid);
                Map<String, Float> expected = new HashMap<>(variables);
                history.updateUpstreamHeight(variables, deck, 2, AFTER_PULSE, true);
                assertEquals(expected, variables);
                variables.putAll(values(BEFORE_PULSE, BEFORE_PULSE));
                history.updateUpstreamHeight(variables, deck, 3, AFTER_PULSE, true);
                assertEquals(BEFORE_PULSE, variables.get(SableFreshAnimationHistory.PREVIOUS_Y),
                        "Invalid saved values must invalidate the old anchor");
            }
        }
    }

    @Test void worldAndHandMapsKeepIndependentPrecisionAnchorsAndResetTogether() {
        var states = new SableFreshAnimationState();
        Object deck = new Object();
        var world = values(BEFORE_PULSE, BEFORE_PULSE);
        var hand = values(BEFORE_PULSE, BEFORE_PULSE + 0.125f);
        states.forRenderContext(false).history.updateUpstreamHeight(world, deck, 1, BEFORE_PULSE, true);
        states.forRenderContext(true).history.updateUpstreamHeight(hand, deck, 1, BEFORE_PULSE, true);
        states.forRenderContext(false).history.updateUpstreamHeight(world, deck, 2, AFTER_PULSE, true);
        assertEquals(BEFORE_PULSE, hand.get(SableFreshAnimationHistory.PREVIOUS_Y));
        states.forRenderContext(true).history.updateUpstreamHeight(hand, deck, 2, AFTER_PULSE, true);
        assertEquals(AFTER_PULSE, world.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(AFTER_PULSE, hand.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(0.125f, hand.get(SableFreshAnimationHistory.DRAGGED_Y) - AFTER_PULSE);

        states.resetRenderContexts();
        float next = Math.nextDown(AFTER_PULSE);
        states.forRenderContext(false).history.updateUpstreamHeight(world, deck, 3, next, true);
        states.forRenderContext(true).history.updateUpstreamHeight(hand, deck, 3, next, true);
        assertEquals(AFTER_PULSE, world.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(AFTER_PULSE, hand.get(SableFreshAnimationHistory.PREVIOUS_Y));
    }

    @Test void primitiveMapsUseTheSamePrecisionPolicy() {
        var history = new SableFreshAnimationHistory();
        Object deck = new Object();
        var variables = new Object2FloatOpenHashMap<String>();
        variables.putAll(values(BEFORE_PULSE, BEFORE_PULSE + 0.125f));
        history.updateUpstreamHeight(variables, deck, 1, BEFORE_PULSE, true);
        history.updateUpstreamHeight(variables, deck, 2, AFTER_PULSE, true);
        assertEquals(AFTER_PULSE, variables.getFloat(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(AFTER_PULSE + 0.125f, variables.getFloat(SableFreshAnimationHistory.DRAGGED_Y));
    }

    @Test void precisionWorldAndHandContextsDoNotAllocateLegacyTransforms() throws Exception {
        var states = new SableFreshAnimationState();
        Object deck = new Object();
        for (boolean hand : new boolean[]{false, true}) {
            var context = states.forRenderContext(hand);
            var variables = values(BEFORE_PULSE, BEFORE_PULSE);
            context.history.updateUpstreamHeight(variables, deck, 1, BEFORE_PULSE, true);
            context.history.updateUpstreamHeight(variables, deck, 2, AFTER_PULSE, true);
            for (String name : new String[]{"interpolated", "currentPoseMatrix", "inverse",
                    "previousLocalPosition", "carriedPosition"}) {
                var field = SableFreshAnimationState.class.getDeclaredField(name);
                field.setAccessible(true);
                assertNull(field.get(context), "Precision history must not allocate " + name);
            }

            // Older/absent Core still uses the native deck sampler and initializes its scratch state.
            var pose = new Pose3d();
            assertEquals(0, context.sampleOwnVerticalMotion(pose, pose, 0, 0, 0, 0, 0, 0, 1));
            for (String name : new String[]{"interpolated", "currentPoseMatrix", "inverse",
                    "previousLocalPosition", "carriedPosition"}) {
                var field = SableFreshAnimationState.class.getDeclaredField(name);
                field.setAccessible(true);
                assertNotNull(field.get(context), "Legacy sampling must initialize " + name);
            }
        }
    }

    private static Map<String, Float> values(float previous, float dragged) {
        Map<String, Float> values = new HashMap<>();
        values.put(SableFreshAnimationHistory.PREVIOUS_Y, previous);
        values.put(SableFreshAnimationHistory.DRAGGED_Y, dragged);
        values.put("var.t_land", 0.8f);
        values.put("var.sneak", 0.75f);
        return values;
    }
}
