package dev.caleb.emfattachments.client;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessoryVisibilityPolicyTest {
    private static final List<String> HEAD = List.of("head", "hat", "face", "mask", "goggles");
    private static final List<String> SCARVES = List.of("artifacts:lucky_scarf",
            "artifacts:scarf_of_invisibility", "deep_aether:floaty_scarf");

    @Test void matchesSlotTokensWithoutNamespaceOrSubstringFalsePositives() {
        for (String slot : List.of("head", "hat", "face", "mod:mask_slot", "mod:head/slot",
                "mod:goggles", "mod:goggle_slot", "other:FACE")) {
            assertTrue(AccessoryVisibilityPolicy.matchesSlot(slot, HEAD), slot);
        }
        for (String slot : List.of("what", "headmod:back", "mod:charm", "mod:necklace",
                "mod:headache", "mod:unmasked", "mod:hand", "backpack")) {
            assertFalse(AccessoryVisibilityPolicy.matchesSlot(slot, HEAD), slot);
        }
        assertTrue(AccessoryVisibilityPolicy.matchesSlot("specific:pendant_slot", List.of("specific:pendant_slot")));
        assertFalse(AccessoryVisibilityPolicy.matchesSlot("other:pendant_slot", List.of("specific:pendant_slot")));
    }

    @Test void knownScarvesHideWithoutHidingOtherNecklacesAndOverridesWin() {
        for (String item : SCARVES) assertTrue(hide("necklace", item, true, true, List.of()));
        assertFalse(hide("necklace", "artifacts:cross_necklace", true, true, List.of()));
        assertFalse(hide("back", "sophisticatedbackpacks:backpack", true, true, List.of()));
        assertFalse(hide("hand", "artifacts:feral_claws", true, true, List.of()));
        assertFalse(hide("hat", "aeronautics:aviators_goggles", true, true, List.of("aeronautics:aviators_goggles")));
        assertFalse(hide("necklace", "artifacts:lucky_scarf", true, false, List.of()));
        assertFalse(hide("hat", "aeronautics:aviators_goggles", false, true, List.of()));
        assertTrue(hide("necklace", "artifacts:lucky_scarf", false, true, List.of()));
        assertTrue(AccessoryVisibilityPolicy.shouldHide("belt", "custom:camera_obstruction", false, false,
                HEAD, List.of("custom:camera_obstruction"), List.of()));
        assertFalse(AccessoryVisibilityPolicy.shouldHide("hat", "custom:camera_obstruction", true, true,
                HEAD, List.of("custom:camera_obstruction"), List.of("custom:camera_obstruction")));
    }

    private static boolean hide(String slot, String item, boolean heads, boolean scarves, List<String> visible) {
        return AccessoryVisibilityPolicy.shouldHide(slot, item, heads, scarves, HEAD, List.of(), visible);
    }

    @Test void physicalHeadModelsStayHiddenWhenTheirStorageSlotIsElsewhere() {
        for (String slot : List.of("anklet", "back", "belt", "hand", "necklace", "ring")) {
            assertTrue(hide(slot, "artifacts:whoopee_cushion", true, true, List.of()));
            assertFalse(hide(slot, "artifacts:whoopee_cushion", false, true, List.of()));
            assertFalse(hide(slot, "artifacts:whoopee_cushion", true, true, List.of("artifacts:whoopee_cushion")));
        }
        assertFalse(hide("necklace", "artifacts:charm_of_shrinking", true, true, List.of()));
        assertFalse(hide("hand", "artifacts:fire_gauntlet", true, true, List.of()));
    }
}
