package dev.caleb.emfattachments.perf;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/** Standalone fixture: deliberately has no dependency on the compatibility mod. */
@Mod(value = "emfattachments_perf", dist = Dist.CLIENT)
public final class EquippedPerformanceMod {
    public EquippedPerformanceMod() { EquippedCrowdBenchmark.register(); }
}
