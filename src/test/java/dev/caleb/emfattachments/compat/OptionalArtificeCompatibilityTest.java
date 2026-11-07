package dev.caleb.emfattachments.compat;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalArtificeCompatibilityTest {
    private static boolean enabled(String mixin, String... installed) {
        Set<String> mods = Set.of(installed);
        return new OptionalMixinPlugin(mods::contains).shouldApplyMixin("unused",
                "dev.caleb.emfattachments.mixin." + mixin);
    }

    @Test void gunAccessoriesWorkWithoutEmfOrFirstPerson() {
        assertTrue(enabled("ArtificeFirstPersonAccessoriesMixin", "irons_artifice", "accessories"));
        assertTrue(enabled("ArtificeArtifactsFirstPersonPoseMixin", "irons_artifice", "accessories", "artifacts"));
    }

    @Test void sixShooterHandRenderingOnlyRequiresIron() {
        for (String mixin : Set.of("ArtificeSixShooterGunHandMixin", "ArtificeSixShooterOffhandMixin")) {
            assertTrue(enabled(mixin, "irons_artifice"));
            assertFalse(enabled(mixin));
            assertFalse(enabled(mixin, "firstperson", "accessories", "entity_model_features"));
        }
    }

    @Test void gunHooksNeverLoadWithoutTheirOptionalApis() {
        for (String mixin : Set.of("ArtificeFirstPersonAccessoriesMixin", "ArtificeArtifactsFirstPersonPoseMixin")) {
            assertFalse(enabled(mixin));
            assertFalse(enabled(mixin, "accessories", "artifacts", "entity_model_features"));
            assertFalse(enabled(mixin, "irons_artifice", "artifacts", "entity_model_features"));
        }
        assertFalse(enabled("ArtificeArtifactsFirstPersonPoseMixin", "irons_artifice", "accessories"));
    }

    @Test void thirdPersonPoseOnlyNeedsIronAndEmf() {
        assertTrue(enabled("ArtificeEmfGunPoseMixin", "irons_artifice", "entity_model_features"));
        assertFalse(enabled("ArtificeEmfGunPoseMixin", "irons_artifice"));
        assertFalse(enabled("ArtificeEmfGunPoseMixin", "entity_model_features"));
        assertFalse(enabled("ArtificeEmfGunPoseMixin"));
    }

    @Test void oneHandedSixShooterReloadWorksWithIronAlone() {
        assertTrue(enabled("ArtificeSixShooterReloadMixin", "irons_artifice"));
        assertFalse(enabled("ArtificeSixShooterReloadMixin", "entity_model_features", "firstperson"));
    }

    @Test void optionalFirstPersonHandSelectionRequiresBothHostsOnly() {
        for (String mixin : Set.of("ArtificeFirstPersonHandsMixin", "ArtificeFirstPersonFreeArmMixin")) {
            assertTrue(enabled(mixin, "irons_artifice", "firstperson"));
            assertFalse(enabled(mixin, "irons_artifice"));
            assertFalse(enabled(mixin, "firstperson"));
            assertFalse(enabled(mixin, "accessories", "entity_model_features"));
        }
    }

    @Test void gunBodyGeometryHooksDoNotRequireCombatOrPlayerAnimator() {
        for (String mixin : Set.of("FirstPersonArmScopeMixin", "FirstPersonArmPartMixin",
                "FirstPersonHeldItemGeometryMixin")) {
            assertTrue(enabled(mixin, "firstperson", "irons_artifice"));
            assertFalse(enabled(mixin, "irons_artifice"));
            assertFalse(enabled(mixin, "firstperson"));
            assertTrue(enabled(mixin, "firstperson", "bettercombat", "playeranimator"));
            assertFalse(enabled(mixin, "firstperson", "bettercombat"));
        }
        assertFalse(enabled("FirstPersonEmfArmPartMixin", "firstperson", "irons_artifice"));
        assertTrue(enabled("FirstPersonEmfArmPartMixin", "firstperson", "irons_artifice", "entity_model_features"));
        assertFalse(enabled("FirstPersonDefaultAccessoryArmMixin", "firstperson", "irons_artifice"));
        assertTrue(enabled("FirstPersonDefaultAccessoryArmMixin", "firstperson", "irons_artifice", "accessories"));
    }

    @Test void skyjadeRequiresItsRendererAndGunHostButNotEmf() {
        assertTrue(enabled("ArtificeSkyjadeFirstPersonPoseMixin", "irons_artifice", "accessories", "aether", "deep_aether"));
        for (String missing : Set.of("irons_artifice", "accessories", "aether", "deep_aether")) {
            String[] present = Set.of("irons_artifice", "accessories", "aether", "deep_aether")
                    .stream().filter(mod -> !mod.equals(missing)).toArray(String[]::new);
            assertFalse(enabled("ArtificeSkyjadeFirstPersonPoseMixin", present));
        }
    }
}
