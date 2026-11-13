package dev.caleb.emfattachments.compat;

import net.neoforged.fml.ModList;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

/** Gives the installed Core normalizer sole ownership of crouched root height. */
public final class EmfCrouchCompatibility {
    private EmfCrouchCompatibility() {}

    public static boolean ownsCrouchHeight() {
        return Availability.SUPPORTED && Core.enabled();
    }

    static boolean supportsVersion(ArtifactVersion version) {
        return version != null && version.compareTo(new DefaultArtifactVersion("2.3.2")) >= 0;
    }

    // Mod discovery is immutable during a client process. Keep the metadata lookup
    // outside the render loop and the optional API behind the availability check.
    private static final class Availability {
        private static final boolean SUPPORTED = ModList.get().getModContainerById("emf_compat_core")
                .map(mod -> supportsVersion(mod.getModInfo().getVersion())).orElse(false);
    }

    private static final class Core {
        private static boolean enabled() {
            // The global Core and crouch-fix settings can change while playing.
            return strm.emfcompat.core.CrouchNormalizer.isEnabled();
        }
    }
}
