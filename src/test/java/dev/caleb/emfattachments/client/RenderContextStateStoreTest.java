package dev.caleb.emfattachments.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderContextStateStoreTest {
    @Test void sameEntityShadowAndBodyKeepIndependentNativeInterpolationHistory() {
        var store = new RenderContextStateStore<String, Object>();
        var shared = new HashMap<String, Object>();
        Object world = new Object(), player = new Object(), shadowFade = new Object(), bodyFade = new Object();
        store.refresh(world, player, true, true);
        shared.put("local", shadowFade);
        Map<String, Object> body = store.select(shared, "local", "local", true);
        assertNotSame(shared, body);
        assertNull(body.get("local"));
        body.put("local", bodyFade);
        // Same-frame shadow and armor calls must not seed, replace, or clear the other history.
        assertSame(shadowFade, store.select(shared, "local", "local", false).get("local"));
        store.refresh(world, player, true, true);
        assertSame(bodyFade, store.select(shared, "local", "local", true).get("local"));
        assertSame(shared, store.select(shared, "remote", "local", true));
        assertSame(shared, store.select(shared, null, "local", true));
    }

    @Test void realCameraAndFeatureTransitionsClearOnlyIsolatedHistory() {
        Object world = new Object(), player = new Object();
        var store = new RenderContextStateStore<String, Object>();
        var shared = new HashMap<String, Object>();
        Object shadowFade = new Object(); shared.put("local", shadowFade);
        for (boolean disabled : new boolean[]{false, true}) {
            store.refresh(world, player, true, true);
            store.select(shared, "local", "local", true).put("local", new Object());
            store.refresh(world, player, !disabled, disabled);
            assertSame(shared, store.select(shared, "local", "local", true));
            store.refresh(world, player, true, true);
            assertTrue(store.select(shared, "local", "local", true).isEmpty());
            assertSame(shadowFade, shared.get("local"));
        }
    }

    @Test void worldPlayerAndDisconnectChangesCannotCarryOldPoseIntoNewEntity() {
        var store = new RenderContextStateStore<String, Object>();
        var shared = new HashMap<String, Object>();
        Object world = new Object(), player = new Object();
        store.refresh(world, player, true, true);
        store.select(shared, "local", "local", true).put("local", new Object());
        store.refresh(world, new Object(), true, true);
        assertTrue(store.select(shared, "local", "local", true).isEmpty());
        store.select(shared, "local", "local", true).put("local", new Object());
        store.refresh(new Object(), player, true, true);
        assertTrue(store.select(shared, "local", "local", true).isEmpty());
        store.select(shared, "local", "local", true).put("local", new Object());
        store.refresh(null, null, true, true);
        assertSame(shared, store.select(shared, "local", "local", true));
        store.refresh(world, player, true, true);
        assertTrue(store.select(shared, "local", "local", true).isEmpty());
    }

    @Test void normalPruningAlsoRemovesStaleIsolatedEntities() {
        var store = new RenderContextStateStore<String, Object>();
        var shared = new HashMap<String, Object>();
        store.refresh(new Object(), new Object(), true, true);
        var body = store.select(shared, "local", "local", true);
        body.put("local", new Object());
        body.put("stale", new Object());
        store.retainOnly(Set.of("local"));
        assertEquals(Set.of("local"), body.keySet());
        store.retainOnly(Set.of());
        assertTrue(body.isEmpty());
    }
}
