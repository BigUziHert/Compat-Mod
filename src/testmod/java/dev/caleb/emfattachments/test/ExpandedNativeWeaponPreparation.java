package dev.caleb.emfattachments.test;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.sweenus.simplyswords.api.AwakeningApi;
import net.sweenus.simplyswords.util.LegacyUniqueMigration;

/** Test fixture preparation only; no ability execution or production behavior changes. */
public final class ExpandedNativeWeaponPreparation {
    private ExpandedNativeWeaponPreparation() {}

    public record Prepared(ItemStack stack, Map<String, Object> evidence) {}

    public static Prepared prepare(String requestedId, String awakening) {
        ItemStack requested = BuiltInRegistries.ITEM.get(ResourceLocation.parse(requestedId)).getDefaultInstance();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("requestedRegistryItem", requestedId);
        if (!requestedId.startsWith("simplyswords:")) return new Prepared(requested, evidence);

        // The installed mod performs this exact public migration from
        // UniqueWeaponItem.inventoryTick. Preparing the native backing stack
        // first prevents the test from waiting forever for a legacy alias ID.
        // Migration must precede selected fixture awakening: otherwise an alias
        // that was initialized as locked is re-awakened by its first native tick.
        ItemStack canonical = LegacyUniqueMigration.convertStack(requested);
        evidence.put("legacyAliasMigrated", canonical != requested);
        evidence.put("nativeMigrationLevel", AwakeningApi.getLevel(canonical));
        evidence.put("nativeMigrationForm", AwakeningApi.getFormId(canonical).map(Object::toString).orElse("none"));
        if ("locked".equals(awakening)) AwakeningApi.initializeNaturalDrop(canonical);
        else if (canonical == requested) AwakeningApi.initializeFullyAwakened(canonical);
        // A requested legacy alias denotes its own native presentation:
        // waking_lichblade remains level4 rather than becoming awakened level8.
        // Non-alias cases retain the full-awakening matrix convention.
        evidence.put("awakeningFixtureMode", "locked".equals(awakening) ? "canonical_locked_zero"
                : canonical != requested ? "native_alias_form" : "fully_awakened");
        evidence.put("nativeBackingItem", BuiltInRegistries.ITEM.getKey(canonical.getItem()).toString());
        evidence.put("preparedAwakeningLevel", AwakeningApi.getLevel(canonical));
        evidence.put("preparedForm", AwakeningApi.getFormId(canonical).map(Object::toString).orElse("none"));
        evidence.put("preparedFormRoute", AwakeningApi.getFormRoute(canonical).map(Object::toString).orElse("none"));
        evidence.put("preparedModelValue", AwakeningApi.getFormModelValue(canonical));
        evidence.put("abilityUnlocked", AwakeningApi.isAbilityUnlocked(canonical));
        return new Prepared(canonical, Map.copyOf(evidence));
    }

    /** Call separately on authoritative server and synchronized client before action start. */
    public static Map<String, Object> verify(ItemStack actual, ItemStack expected, String side) {
        Map<String, Object> actualState = state(actual);
        Map<String, Object> expectedState = state(expected);
        if (!actualState.equals(expectedState)) throw new IllegalStateException(
                side + " native weapon state mismatch: expected=" + expectedState + ", actual=" + actualState);
        return Map.of(side + "NativeWeaponState", actualState, side + "NativeWeaponStateVerified", true);
    }

    private static Map<String, Object> state(ItemStack stack) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("backingItem", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("simplyswords")) {
            state.put("awakeningLevel", AwakeningApi.getLevel(stack));
            state.put("form", AwakeningApi.getFormId(stack).map(Object::toString).orElse("none"));
            state.put("route", AwakeningApi.getFormRoute(stack).map(Object::toString).orElse("none"));
            state.put("modelValue", AwakeningApi.getFormModelValue(stack));
        }
        return Map.copyOf(state);
    }
}
