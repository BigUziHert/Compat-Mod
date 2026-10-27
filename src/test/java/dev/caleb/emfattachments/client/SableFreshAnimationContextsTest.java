package dev.caleb.emfattachments.client;

import dev.ryanhcode.sable.companion.math.Pose3d;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableFreshAnimationContextsTest {
    @Test void alternatingWorldShadowAndHandPassesDoNotRestartEitherHistory() {
        var states = new SableFreshAnimationState();
        Object deck = new Object();
        var world = values(80, 80.125f);
        var hand = values(80, 80.375f);
        sample(states, false, world, deck, 1, 0, 80);
        sample(states, true, hand, deck, 1, 0, 80);

        // EMF keeps independent world and hand maps, but renders both in one frame.
        sample(states, false, world, deck, 2, -1, 79);
        sample(states, true, hand, deck, 2, -1, 79);
        assertEquals(79, world.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(79, hand.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(79.125f, world.get(SableFreshAnimationHistory.DRAGGED_Y));
        assertEquals(79.375f, hand.get(SableFreshAnimationHistory.DRAGGED_Y));
        assertSame(states.forRenderContext(true), states.forRenderContext(true));
        assertNotSame(states.forRenderContext(false), states.forRenderContext(true));

        // A later shadow pass cannot shift either map again on the same frame.
        sample(states, false, world, deck, 2, -5, 75);
        assertEquals(79, world.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(79, hand.get(SableFreshAnimationHistory.PREVIOUS_Y));

        // Genuine player ascent still reaches both animations despite the descending deck.
        sample(states, false, world, deck, 3, -2, 78.42f);
        sample(states, true, hand, deck, 3, -2, 78.42f);
        assertEquals(0.42f, 78.42f - world.get(SableFreshAnimationHistory.PREVIOUS_Y), 1e-5);
        assertEquals(0.42f, 78.42f - hand.get(SableFreshAnimationHistory.PREVIOUS_Y), 1e-5);
    }

    @Test void worldFramesDoNotConsumePersonalMotionBeforeTheNextHandEvaluation() {
        var states = new SableFreshAnimationState();
        Object deck = new Object();
        var world = values(80, 80);
        var hand = values(80, 80);
        sample(states, false, world, deck, 1, 0, 80);
        sample(states, true, hand, deck, 1, 0, 80);
        sample(states, false, world, deck, 2, -1, 79.2f);
        // Represent Fresh Player's own previous-position assignment after its evaluation.
        world.put(SableFreshAnimationHistory.PREVIOUS_Y, 79.2f);
        world.put(SableFreshAnimationHistory.DRAGGED_Y, 79.2f);
        sample(states, false, world, deck, 3, -2, 78.4f);
        sample(states, true, hand, deck, 3, -2, 78.4f);
        assertEquals(0.2f, 78.4f - world.get(SableFreshAnimationHistory.PREVIOUS_Y), 1e-5);
        assertEquals(0.4f, 78.4f - hand.get(SableFreshAnimationHistory.PREVIOUS_Y), 1e-5,
                "The hand history must include its own skipped-frame movement");
    }

    @Test void leavingSupportInvalidatesTheTemporarilyUndrawnHandHistoryToo() {
        var states = new SableFreshAnimationState();
        Object deck = new Object();
        var world = values(80, 80);
        var hand = values(80, 80);
        sample(states, false, world, deck, 1, 0, 80);
        sample(states, true, hand, deck, 1, 0, 80);
        states.resetRenderContexts();
        // While detached the normal and hand animations retain their own falling histories.
        world.putAll(values(85, 84));
        hand.putAll(values(90, 89));
        sample(states, false, world, deck, 3, -1, 79);
        sample(states, true, hand, deck, 3, -1, 79);
        assertEquals(85, world.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(84, world.get(SableFreshAnimationHistory.DRAGGED_Y));
        assertEquals(90, hand.get(SableFreshAnimationHistory.PREVIOUS_Y));
        assertEquals(89, hand.get(SableFreshAnimationHistory.DRAGGED_Y));
    }

    private static void sample(SableFreshAnimationState states, boolean hand,
                               Map<String, Float> variables, Object support,
                               float frame, double deckY, float playerY) {
        var pose = new Pose3d();
        pose.position().y = deckY;
        states.forRenderContext(hand).update(variables, support, frame, playerY, true,
                pose, pose, 0, playerY, 0, 0, playerY, 0, 1);
    }

    private static Map<String, Float> values(float previous, float dragged) {
        var values = new HashMap<String, Float>();
        values.put(SableFreshAnimationHistory.PREVIOUS_Y, previous);
        values.put(SableFreshAnimationHistory.DRAGGED_Y, dragged);
        return values;
    }
}
