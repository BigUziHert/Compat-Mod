package dev.caleb.emfattachments.client;

import net.irisshaders.batchedentityrendering.impl.Groupable;

/** Gives the injected first-person player the same draw-order group as native entities. */
public final class FirstPersonIrisRenderGrouping {
    private FirstPersonIrisRenderGrouping() {}

    public static void render(Groupable buffer, Runnable draw) {
        boolean started = buffer.maybeStartGroup();
        try { draw.run(); }
        finally { if (started) buffer.endGroup(); }
    }
}
