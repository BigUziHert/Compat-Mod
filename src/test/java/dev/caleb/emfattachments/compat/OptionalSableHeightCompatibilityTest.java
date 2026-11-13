package dev.caleb.emfattachments.compat;

import java.util.HashSet;
import java.util.Set;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Evaluation hooks serve the legacy fallback or the narrow upstream precision guard. */
class OptionalSableHeightCompatibilityTest {
    private static final String CORE = "emf_compat_core";
    private static final Set<String> REQUIRED = Set.of("sable", "entity_model_features");
    private static final Set<String> MIXINS = Set.of("SableFreshAnimationMixin", "SableFreshAnimationPlayerMixin",
            "SableFreshAsmEvaluationMixin", "SableFreshInterpreterEvaluationMixin");

    private static boolean enabled(String mixin, Set<String> installed, String coreVersion) {
        var plugin = new OptionalMixinPlugin(installed::contains, (mod, minimum) -> {
            assertEquals(CORE, mod);
            assertEquals("2.4.0", minimum);
            assertNotNull(coreVersion, "Absent Core must not require a version lookup");
            return new DefaultArtifactVersion(coreVersion).compareTo(new DefaultArtifactVersion(minimum)) >= 0;
        });
        return plugin.shouldApplyMixin("unused", "dev.caleb.emfattachments.mixin." + mixin);
    }

    private static Set<String> withCore() {
        Set<String> installed = new HashSet<>(REQUIRED);
        installed.add(CORE);
        return installed;
    }

    @Test void legacyHeightCorrectionRemainsWithoutCoreOrWithOlderCore() {
        for (String mixin : MIXINS) {
            assertTrue(enabled(mixin, REQUIRED, null), mixin);
            assertTrue(enabled(mixin, withCore(), "2.3.2"), mixin);
        }
    }

    @Test void upstreamCoreStillNeedsFreshEvaluationHooksForPrecisionProtection() {
        for (String mixin : MIXINS) {
            for (String version : Set.of("2.4.0", "2.4.1", "2.10.0", "3.0.0")) {
                assertTrue(enabled(mixin, withCore(), version), mixin + " with Core " + version);
            }
        }
    }

    @Test void missingSableOrEmfAlwaysDisablesEveryLegacyHook() {
        for (String missing : REQUIRED) {
            Set<String> installed = withCore();
            installed.remove(missing);
            Set<String> withoutCore = new HashSet<>(installed);
            withoutCore.remove(CORE);
            for (String mixin : MIXINS) {
                assertFalse(enabled(mixin, installed, "2.3.2"), mixin + " without " + missing);
                assertFalse(enabled(mixin, installed, "2.4.0"), mixin + " without " + missing);
                assertFalse(enabled(mixin, withoutCore, null), mixin + " without Core or " + missing);
            }
        }
    }

    @Test void upstreamHeightFixDoesNotDisableUnrelatedIntegrations() {
        Set<String> installed = withCore();
        installed.addAll(Set.of("entityculling", "firstperson", "bettercombat", "playeranimator",
                "notenoughanimations", "emf_compat_not_enough_animations"));
        for (String mixin : Set.of("SableSleepingRotationMixin", "SableSleepingNameTagMixin",
                "SablePlayerEntityCullingMixin", "BetterCombatZeroAxisMixin", "BetterCombatAttackAccessMixin",
                "FirstPersonCombatAnimationMixin", "FirstPersonCombatLogicMixin", "NeaBowPoseMixin",
                "FirstPersonNeaBowPoseMixin", "FirstPersonNeaCrossbowPoseMixin", "FirstPersonPoseInterpolatorMixin")) {
            assertTrue(enabled(mixin, installed, "2.4.0"), mixin);
        }
    }

    @Test void globalDisableStillDisablesLegacyHeightCorrection() {
        String previous = System.getProperty("emfattachments.disable");
        System.setProperty("emfattachments.disable", "true");
        try {
            for (String mixin : MIXINS) {
                assertFalse(enabled(mixin, REQUIRED, null), mixin);
                assertFalse(enabled(mixin, withCore(), "2.3.2"), mixin);
            }
        } finally {
            if (previous == null) System.clearProperty("emfattachments.disable");
            else System.setProperty("emfattachments.disable", previous);
        }
    }
}
