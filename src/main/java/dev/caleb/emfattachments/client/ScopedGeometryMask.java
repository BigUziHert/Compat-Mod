package dev.caleb.emfattachments.client;

import java.util.IdentityHashMap;

/** Identity-only render decisions. No model or persistent visibility is mutated. */
public final class ScopedGeometryMask<T> {
    private final ThreadLocal<Scope> current = new ThreadLocal<>();
    private final Scope inactive = new Scope(null, false);

    public Scope open(boolean enabled) {
        Scope parent = current.get();
        // A disabled child of an active mask still needs its own boundary.
        // Otherwise there is no state to suspend or restore.
        if (!enabled && (parent == null || !parent.enabled)) return inactive;
        Scope next = new Scope(parent, enabled);
        current.set(next);
        return next;
    }

    public boolean contains(T part) {
        Scope scope = current.get();
        return scope != null && scope.enabled && ((scope.seeds != null && scope.seeds.containsKey(part))
                || (scope.copies != null && scope.copies.containsKey(part)));
    }

    public void seed(T part) {
        Scope scope = current.get();
        if (scope != null && scope.enabled) {
            if (scope.seeds == null) scope.seeds = new IdentityHashMap<>();
            scope.seeds.put(part, Boolean.TRUE);
        }
    }

    /** A reused accessory part can subsequently copy an unmasked body part. */
    public void copied(T target, boolean sourceMasked) {
        Scope scope = current.get();
        if (scope == null || !scope.enabled) return;
        if (sourceMasked) {
            if (scope.copies == null) scope.copies = new IdentityHashMap<>();
            scope.copies.put(target, Boolean.TRUE);
        } else if (scope.copies != null) scope.copies.remove(target);
    }

    public final class Scope implements AutoCloseable {
        private final Scope parent;
        private final boolean enabled;
        private IdentityHashMap<T, Boolean> seeds;
        private IdentityHashMap<T, Boolean> copies;
        private boolean closed;

        private Scope(Scope parent, boolean enabled) {
            this.parent = parent;
            this.enabled = enabled;
        }

        @Override public void close() {
            if (this == inactive || closed) return;
            if (current.get() != this) throw new IllegalStateException("Geometry scopes closed out of order");
            // Keep the empty ThreadLocal entry, not the closed render scope;
            // removing it would allocate a new entry at the next idle check.
            current.set(parent);
            if (seeds != null) seeds.clear();
            if (copies != null) copies.clear();
            closed = true;
        }
    }
}
