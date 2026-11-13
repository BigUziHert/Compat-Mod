package dev.caleb.emfattachments.compat;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptionalAetherCapeLookupTest {
    private static boolean enabled(Set<String> mods) {
        return new OptionalMixinPlugin(mods::contains).shouldApplyMixin(
                "com.aetherteam.aether.mixin.AetherMixinHooks",
                "dev.caleb.emfattachments.mixin.AetherCapeLookupMixin");
    }

    @Test void requiresAetherAndAccessoriesButNoRenderingAddons() {
        assertTrue(enabled(Set.of("aether", "accessories")));
        assertFalse(enabled(Set.of("aether")));
        assertFalse(enabled(Set.of("accessories")));
        assertFalse(enabled(Set.of()));
    }

    @Test void focusedNativeControlAndGlobalDisableSkipTheOptimization() {
        for (String property : Set.of("emfattachments.aetherParallelCapeLookup", "emfattachments.disable")) {
            String previous = System.getProperty(property);
            try {
                System.setProperty(property, "true");
                assertFalse(enabled(Set.of("aether", "accessories")), property);
            } finally {
                if (previous == null) System.clearProperty(property);
                else System.setProperty(property, previous);
            }
        }
    }
}
