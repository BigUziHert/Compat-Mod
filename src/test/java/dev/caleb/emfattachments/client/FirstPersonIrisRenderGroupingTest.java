package dev.caleb.emfattachments.client;

import java.util.ArrayList;
import java.util.List;
import net.irisshaders.batchedentityrendering.impl.Groupable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirstPersonIrisRenderGroupingTest {
    @Test void injectedEntityHasOneGroupAndNestedPassPreservesItsOuterGroup() {
        TrackingGroup buffer = new TrackingGroup();
        FirstPersonIrisRenderGrouping.render(buffer, () -> {
            assertTrue(buffer.active);
            FirstPersonIrisRenderGrouping.render(buffer, () -> assertTrue(buffer.active));
            assertTrue(buffer.active);
        });
        assertFalse(buffer.active);
        assertEquals(List.of("start", "end"), buffer.events);
    }
    @Test void exceptionEndsOnlyTheGroupStartedForThisPass() {
        TrackingGroup buffer = new TrackingGroup();
        RuntimeException failure = new RuntimeException("Intentional native renderer failure");
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> FirstPersonIrisRenderGrouping.render(buffer, () -> { throw failure; })));
        assertFalse(buffer.active);
        assertEquals(List.of("start", "end"), buffer.events);
        buffer.startGroup();
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> FirstPersonIrisRenderGrouping.render(buffer, () -> { throw failure; })));
        assertTrue(buffer.active);
        assertEquals(List.of("start", "end", "start"), buffer.events);
        buffer.endGroup();
    }
    private static final class TrackingGroup implements Groupable {
        boolean active;
        final List<String> events = new ArrayList<>();
        public void startGroup() { if (active) throw new IllegalStateException("Nested group"); active = true; events.add("start"); }
        public boolean maybeStartGroup() { if (active) return false; startGroup(); return true; }
        public void endGroup() { if (!active) throw new IllegalStateException("No group"); active = false; events.add("end"); }
    }
}
