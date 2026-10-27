package dev.caleb.emfattachments.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Keeps a render context's native state independent of another pass of the same entity. */
public final class RenderContextStateStore<K, V> {
    private final Map<K, V> bodyStates = new HashMap<>();
    private Object world;
    private Object player;
    private boolean active;

    /** Only persistent context changes clear state. A shadow or extraction pass is not one. */
    public void refresh(Object currentWorld, Object currentPlayer, boolean enabled, boolean firstPersonCamera) {
        boolean nextActive = enabled && firstPersonCamera && currentWorld != null && currentPlayer != null;
        if (world != currentWorld || player != currentPlayer || !nextActive) bodyStates.clear();
        world = currentWorld;
        player = currentPlayer;
        active = nextActive;
    }

    public Map<K, V> select(Map<K, V> ordinaryStates, K entityKey, K localPlayerKey, boolean localBodyPass) {
        return active && localBodyPass && entityKey != null && Objects.equals(entityKey, localPlayerKey)
                ? bodyStates : ordinaryStates;
    }

    public void retainOnly(Collection<K> activeEntities) {
        bodyStates.keySet().retainAll(activeEntities);
    }
}
