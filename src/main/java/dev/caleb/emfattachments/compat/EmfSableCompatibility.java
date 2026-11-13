package dev.caleb.emfattachments.compat;

import net.neoforged.fml.ModList;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

/** Separates Core's height capability from its live user-controlled setting. */
public final class EmfSableCompatibility {
    private EmfSableCompatibility() {}

    public static boolean hasSteadyHeight() {
        return Availability.SUPPORTED;
    }

    public static boolean steadyHeightEnabled() {
        return hasSteadyHeight() && Core.enabled();
    }

    static boolean supportsVersion(ArtifactVersion version) {
        return version != null && version.compareTo(new DefaultArtifactVersion("2.4.0")) >= 0;
    }

    // Installed mod metadata is immutable. Delay linking Core's API until the
    // supported version has been discovered, then read its switches live.
    private static final class Availability {
        private static final boolean SUPPORTED = ModList.get().getModContainerById("emf_compat_core")
                .map(mod -> supportsVersion(mod.getModInfo().getVersion())).orElse(false);
    }

    private static final class Core {
        private static boolean enabled() {
            return strm.emfcompat.core.EMFCompatCore.isCompatEnabled()
                    && strm.emfcompat.core.EMFCompatConfig.getBoolean("core.sableSteadyHeight", true);
        }
    }
}
