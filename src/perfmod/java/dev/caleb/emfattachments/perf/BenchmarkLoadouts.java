package dev.caleb.emfattachments.perf;

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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.AttachmentType;

/** One-time native equipment setup, deliberately independent of the compatibility mod. */
public final class BenchmarkLoadouts {
    private BenchmarkLoadouts() {}

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
        Applied applied = new Applied(entity, PROFILES.get(Math.floorMod(profileIndex, PROFILES.size())));
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
                    + " at " + applied.setupStep + ": " + cause, failure);
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
        private final EnumMap<EquipmentSlot, ItemStack> originalEquipment = new EnumMap<>(EquipmentSlot.class);
        private final List<Touched> touched = new ArrayList<>();
        private Map<String, Object> metadata = Map.of();
        private Object scarfData;
        private Method scarfSetter;
        private boolean originalScarf, scarfTouched, closed, restored;
        private String setupStep = "start";

        private Applied(LivingEntity entity, Profile profile) { this.entity = entity; this.profile = profile; }
        public Map<String, Object> metadata() { return metadata; }
        public boolean restored() { return restored; }

        private void apply() throws ReflectiveOperationException {
            setupStep = "native capability lookup";
            AccessoriesCapability capability = AccessoriesCapability.get(entity);
            if (capability == null) throw new IllegalStateException("No native Accessories capability on " + entity.getClass().getName());
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
                entity.setItemSlot(slot, replacement);
                ItemStack actual = entity.getItemBySlot(slot), stored = storedItem(entity, slot);
                if (!ItemStack.matches(actual, replacement) || !ItemStack.matches(stored, replacement))
                    throw new IllegalStateException("Native equipment assignment failed for " + id
                            + "; expected=" + replacement + " components=" + replacement.getComponents()
                            + "; visible=" + actual + " components=" + actual.getComponents()
                            + "; stored=" + stored + " components=" + stored.getComponents());
                equipmentRows.add(Map.of("slot", slot.getName(), "item", id));
            }
            for (String id : profile.accessories()) {
                setupStep = "accessory " + id;
                ItemStack replacement = stack(id);
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
                            if (touched.stream().anyMatch(t -> t.container == check && t.index == index)) continue;
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
                var renderer = AccessoriesRendererRegistry.getRenderer(replacement);
                if (renderer == null) throw new IllegalStateException("Native Accessories renderer missing for " + id);
                Touched before = new Touched(selected, selectedIndex,
                        selected.getAccessories().getItem(selectedIndex).copy(),
                        selected.getCosmeticAccessories().getItem(selectedIndex).copy(), selected.shouldRender(selectedIndex));
                touched.add(before);
                selected.getCosmeticAccessories().setItem(selectedIndex, ItemStack.EMPTY);
                selected.renderOptions().set(selectedIndex, true);
                selected.getAccessories().setItem(selectedIndex, replacement);
                selected.markChanged();
                if (!ItemStack.matches(selected.getAccessories().getItem(selectedIndex), replacement) || !selected.shouldRender(selectedIndex))
                    throw new IllegalStateException("Native accessory assignment failed for " + id);
                accessoryRows.add(Map.of("item", id, "slot", selected.getSlotName(), "index", selectedIndex,
                        "renderer", renderer.getClass().getName(), "visible", true,
                        "curiosRendererBridge", renderer.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("curio"),
                        "nativeCapeSkinPath", id.endsWith("_cape")));
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
            result.put("equipment", List.copyOf(equipmentRows));
            result.put("accessories", List.copyOf(accessoryRows));
            result.put("floatyScarfWrapped", scarfTouched);
            result.put("twoHandedOffhandPolicy", "The Incinerator and diamond claymore use an intentionally empty offhand; native Better Combat masks offhand rendering for two-handed weapons.");
            result.put("setupPolicy", "Native Accessories slot discovery and insertion validation; functional item visible, selected cosmetic cleared, only touched slots/equipment restored. No use/attack/ability activation.");
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
