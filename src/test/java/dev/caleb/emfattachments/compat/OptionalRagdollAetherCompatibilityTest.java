package dev.caleb.emfattachments.compat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptionalRagdollAetherCompatibilityTest {
    @Test void gloveMaskRequiresTheCompleteScopedRagdollBridgeButNotIronOrEmf() {
        Set<String> required = Set.of("sable_player_ragdoll", "accessories", "curios", "accessories_compat_layer", "aether");
        String mixin = "dev.caleb.emfattachments.mixin.RagdollAetherArmsMixin";
        assertTrue(new OptionalMixinPlugin(required::contains).shouldApplyMixin("unused", mixin));
        for (String missing : required) {
            var installed = new HashSet<>(required);
            installed.remove(missing);
            assertFalse(new OptionalMixinPlugin(installed::contains).shouldApplyMixin("unused", mixin), missing);
        }
    }
}
