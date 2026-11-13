package dev.caleb.emfattachments.perf;

import io.wispforest.accessories.Accessories;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.api.client.AccessoriesRendererRegistry;
import io.wispforest.accessories.menu.ArmorSlotTypes;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPatterns;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents;

/** One-time native equipment setup, deliberately independent of the compatibility mod. */
public final class BenchmarkLoadouts {
    private BenchmarkLoadouts() {}

    // The user specified dye and trim, but not their colors/pattern/material.
    // These reproducible representatives are recorded as assumptions in metadata.
    private static final int REPORTED_BACKPACK_MAIN_COLOR = 0xFF1E5AA8;
    private static final int REPORTED_BACKPACK_ACCENT_COLOR = 0xFFFFC940;

    public record Profile(String name, List<String> accessories, Map<EquipmentSlot, String> equipment) {}

    private static final List<Profile> PROFILES = List.of(
            profile("artifacts-explorer", "minecraft:iron", "simplyswords:iron_longsword", "minecraft:shield", false,
                    "aeronautics:aviators_goggles", "artifacts:onion_ring", "artifacts:panic_necklace",
                    "artifacts:running_shoes", "artifacts:crystal_heart"),
            profile("artifacts-fire", "minecraft:netherite", "minecraft:netherite_sword", "minecraft:shield", false,
                    "artifacts:cowboy_hat", "artifacts:fire_gauntlet", "artifacts:lucky_scarf",
                    "artifacts:steadfast_spikes", "artifacts:obsidian_skull"),
            profile("aether-diamond-gloves", "aether:gravitite", "aether:gravitite_sword", "minecraft:shield", true,
                    "artifacts:night_vision_goggles", "aether:diamond_gloves", "aether:blue_cape",
                    "aether:golden_ring", "aether:golden_pendant", "artifacts:flippers"),
            profile("deep-aether-skyjade", "deep_aether:skyjade", "deep_aether:skyjade_sword", "minecraft:shield", false,
                    "artifacts:novelty_drinking_hat", "deep_aether:skyjade_gloves", "deep_aether:floaty_scarf",
                    "deep_aether:cloud_cape", "deep_aether:skyjade_ring", "artifacts:snowshoes"),
            profile("cataclysm-ignitium", "cataclysm:ignitium", "cataclysm:the_incinerator", "minecraft:air", true,
                    "cataclysm:blazing_grips", "cataclysm:berserker_soul_amulet", "cataclysm:belt_of_monstrosity",
                    "cataclysm:ring_of_grudged", "cataclysm:sturdy_boots", "cataclysm:aptrgangr_head"),
            profile("artifacts-feral-totem", "minecraft:diamond", "simplyswords:diamond_claymore", "minecraft:air", true,
                    "artifacts:snorkel", "artifacts:feral_claws", "artifacts:cross_necklace",
                    "artifacts:kitty_slippers", "artifacts:antidote_vessel", "friendsandfoes:totem_of_freezing")
    );

    public static List<Profile> profiles() { return PROFILES; }

    /** Local-only workload selection; the historical six crowd profiles stay unchanged. */
    public static Applied equipLocal(LivingEntity entity) {
        String selection = System.getProperty("emfattachments.perfLocalLoadout", "historical");
        if (selection.equals("historical")) return equip(entity, 0);
        if (!selection.equals("reported-accessories")) {
            throw new IllegalArgumentException("Unknown local benchmark loadout " + selection);
        }
        String backpack = System.getProperty("emfattachments.perfBackpack", "sophisticatedbackpacks:netherite_backpack");
        if (!List.of("sophisticatedbackpacks:backpack", "sophisticatedbackpacks:copper_backpack",
                "sophisticatedbackpacks:iron_backpack", "sophisticatedbackpacks:gold_backpack",
                "sophisticatedbackpacks:diamond_backpack", "sophisticatedbackpacks:netherite_backpack").contains(backpack)) {
            throw new IllegalArgumentException("Unsupported reported-loadout backpack " + backpack);
        }
        EnumMap<EquipmentSlot, String> equipment = new EnumMap<>(EquipmentSlot.class);
        equipment.put(EquipmentSlot.HEAD, "minecraft:netherite_helmet");
        equipment.put(EquipmentSlot.CHEST, "minecraft:netherite_chestplate");
        equipment.put(EquipmentSlot.LEGS, "minecraft:netherite_leggings");
        equipment.put(EquipmentSlot.FEET, "minecraft:netherite_boots");
        equipment.put(EquipmentSlot.MAINHAND, "minecraft:air");
        equipment.put(EquipmentSlot.OFFHAND, "minecraft:air");
        // Equip the all-slot Whoopee Cushion last so it cannot consume a slot
        // needed by a more restricted item. Every assignment remains native-validated.
        Profile profile = new Profile("reported-accessories", List.of(
                "artifacts:night_vision_goggles",
                "artifacts:lucky_scarf", backpack, "artifacts:onion_ring", "artifacts:golden_hook",
                "artifacts:helium_flamingo", "artifacts:bunny_hoppers", "aether:diamond_gloves",
                "artifacts:whoopee_cushion"), Map.copyOf(equipment));
        return equip(entity, profile, true);
    }

    private static Profile profile(String name, String armor, String weapon, String shield,
                                   boolean helmet, String... accessories) {
        EnumMap<EquipmentSlot, String> equipment = new EnumMap<>(EquipmentSlot.class);
        equipment.put(EquipmentSlot.HEAD, helmet ? armor + "_helmet" : "minecraft:air");
        equipment.put(EquipmentSlot.CHEST, armor + "_chestplate");
        equipment.put(EquipmentSlot.LEGS, armor + "_leggings");
        equipment.put(EquipmentSlot.FEET, armor + "_boots");
        equipment.put(EquipmentSlot.MAINHAND, weapon);
        equipment.put(EquipmentSlot.OFFHAND, shield);
        return new Profile(name, List.of(accessories), Map.copyOf(equipment));
    }

    /** The caller owns the entity and invokes this only outside measured intervals. */
    public static Applied equip(LivingEntity entity, int profileIndex) {
        return equip(entity, PROFILES.get(Math.floorMod(profileIndex, PROFILES.size())), false);
    }

    private static Applied equip(LivingEntity entity, Profile profile, boolean replaceAllAccessories) {
        Applied applied = new Applied(entity, profile, replaceAllAccessories);
        try {
            applied.apply();
            return applied;
        } catch (Throwable failure) {
            try { applied.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            StringBuilder cause = new StringBuilder();
            Throwable current = failure;
            for (int i = 0; current != null && i < 8; i++, current = current.getCause()) {
                if (!cause.isEmpty()) cause.append(" -> ");
                cause.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            }
            throw new IllegalStateException("Native benchmark loadout failed: " + applied.profile.name()
                    + " at " + applied.setupStep + ": " + cause
                    + (applied.replaceAllAccessories ? "; all native candidates=" + applied.nativeSlotCandidates : ""), failure);
        }
    }

    private static ItemStack stack(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        if (!BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalStateException("Missing benchmark item " + id);
        ItemStack stack = BuiltInRegistries.ITEM.get(key).getDefaultInstance();
        if (stack.isEmpty() && !id.equals("minecraft:air")) throw new IllegalStateException("Empty benchmark item " + id);
        return stack;
    }

    private record Touched(AccessoriesContainer container, int index, ItemStack normal,
                           ItemStack cosmetic, boolean render) {}

    private record ExpectedAccessory(AccessoriesContainer container, int index, ItemStack stack, ItemStack cosmetic) {}

    /** Actual native component witness, collected only outside timed rendering. */
    private static Map<String, Object> appearance(ItemStack stack) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("foil", stack.hasFoil());
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        result.put("trimPattern", trim == null ? "" : trim.pattern().unwrapKey().orElseThrow().location().toString());
        result.put("trimMaterial", trim == null ? "" : trim.material().unwrapKey().orElseThrow().location().toString());
        if (stack.getItem() instanceof BackpackItem) {
            result.put("backpackMainColor", String.format(java.util.Locale.ROOT, "%08x", BackpackItem.getMainColor(stack)));
            result.put("backpackAccentColor", String.format(java.util.Locale.ROOT, "%08x", BackpackItem.getAccentColor(stack)));
            result.put("backpackInventorySlots", stack.getOrDefault(ModCoreDataComponents.NUMBER_OF_INVENTORY_SLOTS.get(), -1));
            result.put("backpackUpgradeSlots", stack.getOrDefault(ModCoreDataComponents.NUMBER_OF_UPGRADE_SLOTS.get(), -1));
            result.put("backpackStorageUuidPresent", stack.has(ModCoreDataComponents.STORAGE_UUID.get()));
        }
        return Map.copyOf(result);
    }

    private static void initializeEmptyBackpack(ItemStack stack) {
        BackpackItem item = (BackpackItem) stack.getItem();
        ItemStack before = stack.copy();
        // The native wrapper adds its configured slot-count components on first
        // use. Do this once before freezing the expected stack, never in timing.
        BackpackWrapper.fromStack(stack);
        if (stack.getOrDefault(ModCoreDataComponents.NUMBER_OF_INVENTORY_SLOTS.get(), -1) != item.getNumberOfSlots()
                || stack.getOrDefault(ModCoreDataComponents.NUMBER_OF_UPGRADE_SLOTS.get(), -1) != item.getNumberOfUpgradeSlots()
                || stack.has(ModCoreDataComponents.STORAGE_UUID.get()))
            throw new IllegalStateException("Unexpected native empty-backpack initialization: " + stack.getComponents());
        ItemStack normalized = stack.copy();
        for (ItemStack compared : List.of(before, normalized)) {
            compared.remove(ModCoreDataComponents.NUMBER_OF_INVENTORY_SLOTS.get());
            compared.remove(ModCoreDataComponents.NUMBER_OF_UPGRADE_SLOTS.get());
        }
        if (!ItemStack.matches(before, normalized))
            throw new IllegalStateException("Native backpack initialization changed more than slot-count defaults: before="
                    + before.getComponents() + "; after=" + normalized.getComponents());
    }

    private static ItemStack storedItem(LivingEntity entity, EquipmentSlot slot) {
        if (entity instanceof Player player) {
            return switch (slot) {
                case MAINHAND -> player.getInventory().getSelected();
                case OFFHAND -> player.getInventory().offhand.get(0);
                case HEAD, CHEST, LEGS, FEET -> player.getInventory().armor.get(slot.getIndex());
                default -> entity.getItemBySlot(slot);
            };
        }
        return entity.getItemBySlot(slot);
    }

    public static final class Applied implements AutoCloseable {
        private final LivingEntity entity;
        private final Profile profile;
        private final boolean replaceAllAccessories;
        private final EnumMap<EquipmentSlot, ItemStack> originalEquipment = new EnumMap<>(EquipmentSlot.class);
        private final EnumMap<EquipmentSlot, ItemStack> expectedEquipment = new EnumMap<>(EquipmentSlot.class);
        private final List<Touched> touched = new ArrayList<>();
        private final List<ExpectedAccessory> expectedAccessories = new ArrayList<>();
        private final Map<String, List<Map<String, Object>>> nativeSlotCandidates = new LinkedHashMap<>();
        private Map<String, Object> metadata = Map.of();
        private Object scarfData;
        private Method scarfSetter;
        private boolean originalScarf, scarfTouched, closed, restored;
        private String setupStep = "start";

        private Applied(LivingEntity entity, Profile profile, boolean replaceAllAccessories) {
            this.entity = entity; this.profile = profile; this.replaceAllAccessories = replaceAllAccessories;
        }
        public Map<String, Object> metadata() { return metadata; }
        public boolean restored() { return restored; }

        /** Call outside measured intervals after native synchronization and at stage boundaries. */
        public Map<String, Object> verifyCurrent() {
            if (closed) throw new IllegalStateException("Cannot verify a closed loadout");
            if (replaceAllAccessories && !Accessories.config().clientOptions.showCosmeticAccessories())
                throw new IllegalStateException("Reported loadout requires native cosmetic accessory rendering enabled");
            List<Map<String, Object>> equipmentRows = new ArrayList<>(), accessoryRows = new ArrayList<>();
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
                ItemStack expected = expectedEquipment.get(slot), actual = storedItem(entity, slot);
                if (expected == null || !ItemStack.matches(expected, actual))
                    throw new IllegalStateException("Current equipment differs from selected loadout: " + slot);
                equipmentRows.add(Map.of("slot", slot.getName(), "item", BuiltInRegistries.ITEM.getKey(actual.getItem()).toString(),
                        "count", actual.getCount(), "appearance", appearance(actual)));
            }
            for (ExpectedAccessory expected : expectedAccessories) {
                AccessoriesContainer container = expected.container;
                if (container.getSize() <= expected.index)
                    throw new IllegalStateException("Selected accessory slot disappeared: " + container.getSlotName());
                ItemStack actual = container.getAccessories().getItem(expected.index);
                ItemStack cosmetic = container.getCosmeticAccessories().getItem(expected.index);
                if (!ItemStack.matches(expected.stack, actual) || !ItemStack.matches(expected.cosmetic, cosmetic) || !container.shouldRender(expected.index))
                    throw new IllegalStateException("Current accessory differs from selected loadout: " + container.getSlotName() + ":" + expected.index
                            + "; expected=" + expected.stack + " components=" + expected.stack.getComponents()
                            + "; actual=" + actual + " components=" + actual.getComponents()
                            + "; expectedCosmetic=" + expected.cosmetic.getComponents() + "; cosmetic=" + cosmetic.getComponents());
                ItemStack selectedStack = cosmetic.isEmpty() ? actual : cosmetic;
                var renderer = AccessoriesRendererRegistry.getRenderer(selectedStack);
                if (renderer == null || !renderer.shouldRender(container.shouldRender(expected.index)))
                    throw new IllegalStateException("Selected accessory is not renderable: " + actual);
                accessoryRows.add(Map.of("slot", container.getSlotName(), "index", expected.index,
                        "item", BuiltInRegistries.ITEM.getKey(selectedStack.getItem()).toString(), "count", selectedStack.getCount(),
                        "functionalItem", BuiltInRegistries.ITEM.getKey(actual.getItem()).toString(),
                        "selectedCosmetic", BuiltInRegistries.ITEM.getKey(cosmetic.getItem()).toString(),
                        "visible", true, "renderer", renderer.getClass().getName(), "appearance", appearance(selectedStack)));
            }
            if (replaceAllAccessories) {
                var capability = AccessoriesCapability.get(entity);
                if (capability == null) throw new IllegalStateException("Current native capability disappeared");
                verifyReportedContents(capability);
            }
            return Map.of("profile", profile.name(), "verified", true, "accessoryCount", accessoryRows.size(),
                    "equippedItemCount", expectedAccessories.size() + expectedAccessories.stream().filter(e -> !e.cosmetic.isEmpty()).count(),
                    "exactAccessoryLoadout", replaceAllAccessories,
                    "equipment", List.copyOf(equipmentRows), "accessories", List.copyOf(accessoryRows));
        }

        private void verifyReportedContents(AccessoriesCapability capability) {
            int actualCount = 0, cosmeticCount = 0;
            for (var container : capability.getContainers().values()) {
                boolean armor = ArmorSlotTypes.isArmorType(container.getSlotName());
                for (int index = 0; index < container.getSize(); index++) {
                    ItemStack actual = container.getAccessories().getItem(index), cosmetic = container.getCosmeticAccessories().getItem(index);
                    if (armor) {
                        if (!cosmetic.isEmpty()) throw new IllegalStateException("Unexpected cosmetic armor in " + container.getSlotName());
                        continue;
                    }
                    final AccessoriesContainer check = container; final int slotIndex = index;
                    ExpectedAccessory expected = expectedAccessories.stream()
                            .filter(e -> e.container == check && e.index == slotIndex).findFirst().orElse(null);
                    if (expected == null) {
                        if (!actual.isEmpty() || !cosmetic.isEmpty())
                            throw new IllegalStateException("Unexpected extra accessory in " + container.getSlotName() + ":" + index);
                        continue;
                    }
                    if (!ItemStack.matches(expected.stack, actual) || !ItemStack.matches(expected.cosmetic, cosmetic)
                            || !container.shouldRender(index)) throw new IllegalStateException("Reported slot differs at " + container.getSlotName() + ":" + index);
                    if (!actual.isEmpty()) actualCount++;
                    if (!cosmetic.isEmpty()) cosmeticCount++;
                }
            }
            if (actualCount != 9 || cosmeticCount != 1 || expectedAccessories.size() != 9)
                throw new IllegalStateException("Expected nine functional accessories and one cosmetic, got " + actualCount + "/" + cosmeticCount);
        }

        private void apply() throws ReflectiveOperationException {
            setupStep = "native capability lookup";
            AccessoriesCapability capability = AccessoriesCapability.get(entity);
            if (capability == null) throw new IllegalStateException("No native Accessories capability on " + entity.getClass().getName());
            if (replaceAllAccessories) {
                if (!Accessories.config().clientOptions.showCosmeticAccessories())
                    throw new IllegalStateException("Reported loadout requires native cosmetic accessory rendering enabled");
                List<String> candidateItems = new ArrayList<>(profile.accessories());
                candidateItems.add("aeronautics:aviators_goggles");
                for (String id : candidateItems) {
                    List<Map<String, Object>> candidates = new ArrayList<>();
                    AccessoriesAPI.getStackSlotTypes(entity, stack(id)).stream()
                            .filter(type -> !ArmorSlotTypes.isArmorType(type.name()))
                            .map(capability::getContainer).filter(container -> container != null)
                            .sorted(Comparator.comparing(AccessoriesContainer::getSlotName))
                            .forEach(container -> candidates.add(Map.of("slot", container.getSlotName(), "size", container.getSize())));
                    nativeSlotCandidates.put(id, List.copyOf(candidates));
                }
            }
            List<Map<String, Object>> equipmentRows = new ArrayList<>(), accessoryRows = new ArrayList<>();
            // Snapshot storage before changing either hand: Better Combat masks the offhand
            // getter when the main hand is two-handed, without emptying its actual inventory.
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND))
                originalEquipment.put(slot, storedItem(entity, slot).copy());
            // Explicit enum ordering keeps metadata identical between fresh processes.
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
                String id = profile.equipment().get(slot);
                setupStep = "equipment " + slot.getName() + "=" + id;
                ItemStack replacement = stack(id);
                if (replaceAllAccessories && slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                    replacement.set(DataComponents.TRIM, new ArmorTrim(
                            entity.registryAccess().lookupOrThrow(Registries.TRIM_MATERIAL).getOrThrow(TrimMaterials.GOLD),
                            entity.registryAccess().lookupOrThrow(Registries.TRIM_PATTERN).getOrThrow(TrimPatterns.SILENCE)));
                }
                entity.setItemSlot(slot, replacement);
                ItemStack actual = entity.getItemBySlot(slot), stored = storedItem(entity, slot);
                if (!ItemStack.matches(actual, replacement) || !ItemStack.matches(stored, replacement))
                    throw new IllegalStateException("Native equipment assignment failed for " + id
                            + "; expected=" + replacement + " components=" + replacement.getComponents()
                            + "; visible=" + actual + " components=" + actual.getComponents()
                            + "; stored=" + stored + " components=" + stored.getComponents());
                expectedEquipment.put(slot, replacement.copy());
                equipmentRows.add(replaceAllAccessories
                        ? Map.of("slot", slot.getName(), "item", id, "appearance", appearance(actual))
                        : Map.of("slot", slot.getName(), "item", id));
            }
            if (replaceAllAccessories) {
                setupStep = "snapshot and clear previous accessory loadout";
                for (var container : capability.getContainers().values()) {
                    boolean armor = ArmorSlotTypes.isArmorType(container.getSlotName());
                    for (int index = 0; index < container.getSize(); index++) {
                        touched.add(new Touched(container, index,
                                container.getAccessories().getItem(index).copy(),
                                container.getCosmeticAccessories().getItem(index).copy(), container.shouldRender(index)));
                        if (!armor) container.getAccessories().setItem(index, ItemStack.EMPTY);
                        container.getCosmeticAccessories().setItem(index, ItemStack.EMPTY);
                        container.renderOptions().set(index, true);
                    }
                    container.markChanged();
                }
            }
            List<Touched> assigned = new ArrayList<>();
            for (String id : profile.accessories()) {
                setupStep = "accessory " + id;
                ItemStack replacement = stack(id);
                if (replaceAllAccessories && replacement.getItem() instanceof BackpackItem) {
                    // Use the mod's native component setter; do not invent stored
                    // contents/UUIDs or initialize upgrades in the measured fixture.
                    BackpackItem.setColors(replacement, REPORTED_BACKPACK_MAIN_COLOR, REPORTED_BACKPACK_ACCENT_COLOR);
                    initializeEmptyBackpack(replacement);
                }
                List<AccessoriesContainer> candidates = AccessoriesAPI.getStackSlotTypes(entity, replacement).stream()
                        .filter(type -> !ArmorSlotTypes.isArmorType(type.name()))
                        .map(capability::getContainer).filter(container -> container != null && container.getSize() > 0)
                        .sorted(Comparator.comparingInt((AccessoriesContainer c) -> slotRank(id, c.getSlotName()))
                                .thenComparing(AccessoriesContainer::getSlotName)).toList();
                AccessoriesContainer selected = null;
                int selectedIndex = -1;
                // Preserve unrelated local equipment, choosing empty valid slots first.
                for (boolean requireEmpty : new boolean[]{true, false}) {
                    for (AccessoriesContainer container : candidates) {
                        for (int i = 0; i < container.getSize(); i++) {
                            final AccessoriesContainer check = container; final int index = i;
                            if (assigned.stream().anyMatch(t -> t.container == check && t.index == index)) continue;
                            if (requireEmpty && !container.getAccessories().getItem(i).isEmpty()) continue;
                            if (!AccessoriesAPI.canInsertIntoSlot(replacement, container.createReference(i))) continue;
                            selected = container; selectedIndex = i; break;
                        }
                        if (selected != null) break;
                    }
                    if (selected != null) break;
                }
                if (selected == null) throw new IllegalStateException("No unoccupied native slot for " + id + "; candidate containers="
                        + candidates.stream().map(c -> c.getSlotName() + ":" + c.getSize()).toList());
                ItemStack cosmetic = replaceAllAccessories && id.equals("artifacts:night_vision_goggles")
                        ? stack("aeronautics:aviators_goggles") : ItemStack.EMPTY;
                if (!cosmetic.isEmpty() && !AccessoriesAPI.canInsertIntoSlot(cosmetic, selected.createReference(selectedIndex)))
                    throw new IllegalStateException("Native cosmetic goggles insertion rejected for " + selected.getSlotName());
                ItemStack selectedStack = cosmetic.isEmpty() ? replacement : cosmetic;
                var renderer = AccessoriesRendererRegistry.getRenderer(selectedStack);
                if (renderer == null) throw new IllegalStateException("Native Accessories renderer missing for " + id);
                Touched before = new Touched(selected, selectedIndex,
                        selected.getAccessories().getItem(selectedIndex).copy(),
                        selected.getCosmeticAccessories().getItem(selectedIndex).copy(), selected.shouldRender(selectedIndex));
                assigned.add(before);
                if (!replaceAllAccessories) touched.add(before);
                selected.getCosmeticAccessories().setItem(selectedIndex, cosmetic);
                selected.renderOptions().set(selectedIndex, true);
                selected.getAccessories().setItem(selectedIndex, replacement);
                selected.markChanged();
                if (!ItemStack.matches(selected.getAccessories().getItem(selectedIndex), replacement)
                        || !ItemStack.matches(selected.getCosmeticAccessories().getItem(selectedIndex), cosmetic) || !selected.shouldRender(selectedIndex))
                    throw new IllegalStateException("Native accessory assignment failed for " + id);
                expectedAccessories.add(new ExpectedAccessory(selected, selectedIndex, replacement.copy(), cosmetic.copy()));
                accessoryRows.add(Map.of("item", BuiltInRegistries.ITEM.getKey(selectedStack.getItem()).toString(),
                        "functionalItem", id, "slot", selected.getSlotName(), "index", selectedIndex,
                        "renderer", renderer.getClass().getName(), "visible", true,
                        "selectedCosmetic", BuiltInRegistries.ITEM.getKey(selected.getCosmeticAccessories().getItem(selectedIndex).getItem()).toString(),
                        "appearance", appearance(selectedStack),
                        "curiosRendererBridge", renderer.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("curio"),
                        "nativeCapeSkinPath", id.endsWith("_cape")));
            }
            if (replaceAllAccessories) {
                setupStep = "verify exact reported accessory workload";
                verifyReportedContents(capability);
            }
            if (profile.accessories().contains("deep_aether:floaty_scarf")) {
                setupStep = "Deep Aether scarf attachment";
                var supplier = (Supplier<?>) Class.forName("io.github.razordevs.deep_aether.networking.attachment.DAAttachments")
                        .getField("PLAYER").get(null);
                scarfData = entity.getData((AttachmentType<?>) supplier.get());
                originalScarf = (boolean) scarfData.getClass().getMethod("isFloatyScarfWrappedAroundNeck").invoke(scarfData);
                scarfSetter = scarfData.getClass().getMethod("setFloatyScarfWrappedAroundNeck", boolean.class);
                scarfTouched = true;
                scarfSetter.invoke(scarfData, true);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("profile", profile.name());
            result.put("accessoryCount", accessoryRows.size());
            result.put("equippedItemCount", expectedAccessories.size() + expectedAccessories.stream().filter(e -> !e.cosmetic.isEmpty()).count());
            result.put("equipment", List.copyOf(equipmentRows));
            result.put("accessories", List.copyOf(accessoryRows));
            result.put("floatyScarfWrapped", scarfTouched);
            result.put("twoHandedOffhandPolicy", "The Incinerator and diamond claymore use an intentionally empty offhand; native Better Combat masks offhand rendering for two-handed weapons.");
            result.put("setupPolicy", "Native Accessories slot discovery and insertion validation; functional item visible, selected cosmetic cleared, only touched slots/equipment restored. No use/attack/ability activation.");
            if (replaceAllAccessories) {
                result.put("exactAccessoryLoadout", true);
                result.put("nativeSlotCandidates", Map.copyOf(nativeSlotCandidates));
                result.put("backpackFixture", "Fresh dyed stack of the selected backpack tier; native wrapper initializes only configured inventory/upgrade slot-count components before the exact stack snapshot. No storage UUID, stored contents, upgrades or display items are synthesized.");
                result.put("appearanceAssumptions", Map.of("backpackMainColor", "ff1e5aa8", "backpackAccentColor", "ffffc940",
                        "armorTrimPattern", "minecraft:silence", "armorTrimMaterial", "minecraft:gold",
                        "reason", "User specified dyed Netherite backpack and full trimmed Netherite armor, but not colors, trim pattern/material, contents or upgrades.",
                        "enchantments", "none supplied", "heldItems", "empty"));
                result.put("setupPolicy", "Snapshot all accessory state and clear prior items/cosmetics; preserve actual armor. Equip nine native-validated functional accessories plus cosmetic aviator goggles replacing the night-vision goggles in the same native one-slot hat container: ten stored items, nine selected accessory renderers. Restore all original items/visibility/equipment afterward. Full Netherite armor with gold silence trim; held items empty; no use/attack/ability activation.");
            }
            metadata = Map.copyOf(result);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            if (scarfTouched) {
                try { scarfSetter.invoke(scarfData, originalScarf); }
                catch (ReflectiveOperationException ex) { failure = new IllegalStateException("Scarf attachment restoration failed", ex); }
            }
            for (int i = touched.size() - 1; i >= 0; i--) {
                Touched before = touched.get(i);
                try {
                    AccessoriesContainer c = before.container;
                    c.getAccessories().setItem(before.index, before.normal.copy());
                    c.getCosmeticAccessories().setItem(before.index, before.cosmetic.copy());
                    c.renderOptions().set(before.index, before.render);
                    c.markChanged();
                    if (!ItemStack.matches(c.getAccessories().getItem(before.index), before.normal)
                            || !ItemStack.matches(c.getCosmeticAccessories().getItem(before.index), before.cosmetic)
                            || c.shouldRender(before.index) != before.render) throw new IllegalStateException("Slot restore mismatch " + c.getSlotName());
                } catch (RuntimeException ex) {
                    if (failure == null) failure = ex; else failure.addSuppressed(ex);
                }
            }
            for (var before : originalEquipment.entrySet()) {
                try {
                    entity.setItemSlot(before.getKey(), before.getValue().copy());
                    if (!ItemStack.matches(storedItem(entity, before.getKey()), before.getValue()))
                        throw new IllegalStateException("Equipment restore mismatch " + before.getKey());
                } catch (RuntimeException ex) {
                    if (failure == null) failure = ex; else failure.addSuppressed(ex);
                }
            }
            if (failure != null) throw failure;
            restored = true;
        }
    }

    private static int slotRank(String item, String slot) {
        String path = slot.substring(slot.lastIndexOf(':') + 1);
        if (item.contains("gloves") && path.contains("glove")) return 0;
        if (item.contains("cape") && (path.contains("cape") || path.equals("back"))) return 0;
        if (item.contains("ring") && path.contains("ring")) return 0;
        if ((item.contains("pendant") || item.contains("scarf")) && (path.contains("pendant") || path.equals("necklace"))) return 0;
        if (item.equals("aeronautics:aviators_goggles") && path.equals("head")) return 0;
        return 10;
    }
}
