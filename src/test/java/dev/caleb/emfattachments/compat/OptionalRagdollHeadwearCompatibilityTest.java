package dev.caleb.emfattachments.compat;

import java.util.Set;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptionalRagdollHeadwearCompatibilityTest {
    private static final Set<String> REQUIRED = Set.of("sable_player_ragdoll", "accessories", "curios", "accessories_compat_layer");
    private static final Set<String> MIXINS = Set.of("RagdollHeadwearRoutingMixin", "RagdollCuriosHeadwearRoutingMixin");

    private static boolean enabled(String mixin, Set<String> installed, String version) {
        var plugin = new OptionalMixinPlugin(installed::contains, (mod, minimum) -> {
            assertEquals("sable_player_ragdoll", mod);
            assertEquals("0.7.5", minimum);
            return new DefaultArtifactVersion(version).compareTo(new DefaultArtifactVersion(minimum)) >= 0;
        });
        return plugin.shouldApplyMixin("unused", "dev.caleb.emfattachments.mixin." + mixin);
    }

    @Test void storedSnapshotRoutingRequiresTheNewApiWithoutLoadingMinecraft() {
        for (String mixin : MIXINS) {
            for (String version : Set.of("0.7.2", "0.7.4")) assertFalse(enabled(mixin, REQUIRED, version));
            for (String version : Set.of("0.7.5", "0.7.6", "0.8.0")) assertTrue(enabled(mixin, REQUIRED, version));
        }
    }

    @Test void missingOptionalApisDisableBothRoutes() {
        for (String mixin : MIXINS) for (String missing : REQUIRED) {
            Set<String> installed = new java.util.HashSet<>(REQUIRED);
            installed.remove(missing);
            assertFalse(enabled(mixin, installed, "0.7.5"));
        }
    }

    @Test void olderRagdollsKeepTheExistingDeduplicationIntegration() {
        assertTrue(enabled("RagdollCuriosDedupMixin", REQUIRED, "0.7.2"));
        assertTrue(enabled("RagdollAccessoriesDedupMixin", REQUIRED, "0.7.2"));
        assertTrue(enabled("RagdollAccessoryScopeMixin", REQUIRED, "0.7.2"));
    }
}
