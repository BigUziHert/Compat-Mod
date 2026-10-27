package dev.caleb.emfattachments.client;

import java.util.List;
import java.util.Locale;

/** Pure matching rules; no render state, inventories, or optional mod classes. */
public final class AccessoryVisibilityPolicy {
    private static final List<String> SCARVES = List.of("artifacts:lucky_scarf",
            "artifacts:scarf_of_invisibility", "deep_aether:floaty_scarf");
    // These renderers attach to the head independently of their storage slot.
    // In particular, Artifacts permits the whoopee cushion in many body slots.
    private static final List<String> HEAD_MODELS = List.of("artifacts:whoopee_cushion",
            "artifacts:plastic_drinking_hat", "artifacts:novelty_drinking_hat", "artifacts:snorkel",
            "artifacts:night_vision_goggles", "artifacts:superstitious_hat", "artifacts:villager_hat",
            "artifacts:cowboy_hat", "artifacts:anglers_hat", "create:goggles",
            "aeronautics:aviators_goggles", "the_bumblezone:flower_headwear");
    private AccessoryVisibilityPolicy() {}

    public static boolean shouldHide(String slotName, String itemId, boolean hideHeads,
            boolean hideScarves, List<? extends String> hiddenSlots,
            List<? extends String> hiddenItems, List<? extends String> visibleItems) {
        if (containsId(visibleItems, itemId)) return false;
        return containsId(hiddenItems, itemId) || (hideHeads && (matchesSlot(slotName, hiddenSlots) || containsId(HEAD_MODELS, itemId)))
                || (hideScarves && containsId(SCARVES, itemId));
    }

    public static boolean matchesSlot(String slotName, List<? extends String> configuredSlots) {
        if (slotName == null) return false;
        String normalized = slotName.toLowerCase(Locale.ROOT);
        String path = normalized.substring(normalized.lastIndexOf(':') + 1);
        String[] segments = path.split("[/_.-]+");
        for (String entry : configuredSlots) {
            String configured = entry.trim().toLowerCase(Locale.ROOT);
            if (configured.isEmpty()) continue;
            if (configured.indexOf(':') >= 0) {
                if (normalized.equals(configured)) return true;
                continue;
            }
            if (path.equals(configured)) return true;
            for (String segment : segments) {
                if (canonicalToken(segment).equals(canonicalToken(configured))) return true;
            }
        }
        return false;
    }

    private static String canonicalToken(String token) {
        return token.equals("goggles") ? "goggle" : token;
    }

    private static boolean containsId(List<? extends String> configuredItems, String itemId) {
        if (itemId == null) return false;
        for (String configured : configuredItems) {
            if (itemId.equalsIgnoreCase(configured.trim())) return true;
        }
        return false;
    }
}
