package dev.caleb.emfattachments.compat;

/** Per-entity bridge to the installed Sable callbacks, linked after their mixins are applied. */
public interface SableReducedTickAccess {
    boolean emfattachments$hasSableReducedTickBridge();
    void emfattachments$maintainSableTick();
    boolean emfattachments$wasSableReducedTick();
    void emfattachments$setSableReducedTick(boolean reduced);
}
