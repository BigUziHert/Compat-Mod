package dev.caleb.emfattachments.test;

import artifacts.component.ability.AttributeModifiers;
import artifacts.registry.ModDataComponents;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Installs the Artifacts item on the authoritative rider, then observes its normal tick and sync. */
final class BunnySlippersEquipment {
    private static final ResourceLocation ITEM = ResourceLocation.parse("artifacts:bunny_hoppers");
    private static final String SLOT = "shoes";

    private BunnySlippersEquipment() {}

    record Saved(UUID rider, ItemStack original) {
        void restore(ServerPlayer player) {
            if (!player.getUUID().equals(rider)) throw new IllegalArgumentException("Different rider");
            requireShoes(player).getAccessories().setItem(0, original.copy());
        }
    }

    static Saved equip(ServerPlayer player) {
        ItemStack hoppers = newHoppers();
        boolean allowed = AccessoriesAPI.getStackSlotTypes(player, hoppers).stream()
                .anyMatch(type -> SLOT.equals(type.name()));
        if (!allowed) throw new IllegalStateException("Bunny Hoppers did not resolve to the shoes slot");
        return replace(player, hoppers);
    }

    static Saved bare(ServerPlayer player) {
        return replace(player, ItemStack.EMPTY);
    }

    private static Saved replace(ServerPlayer player, ItemStack stack) {
        var inventory = requireShoes(player).getAccessories();
        Saved saved = new Saved(player.getUUID(), inventory.getItem(0).copy());
        inventory.setItem(0, stack);
        return saved;
    }

    private static ItemStack newHoppers() {
        return BuiltInRegistries.ITEM.getOptional(ITEM).orElseThrow().getDefaultInstance();
    }

    private static AccessoriesContainer shoes(Player player) {
        var capability = AccessoriesCapability.get(player);
        if (capability == null) return null;
        var container = capability.getContainers().get(SLOT);
        return container != null && container.getSize() > 0 ? container : null;
    }

    private static AccessoriesContainer requireShoes(ServerPlayer player) {
        if (!player.serverLevel().getServer().isSameThread()) {
            throw new IllegalStateException("Equipment changes require the server thread");
        }
        var container = shoes(player);
        if (container == null) throw new IllegalStateException("Missing functional shoes slot");
        return container;
    }

    private static AttributeModifiers.Entry jumpEffect() {
        var abilities = newHoppers().get(ModDataComponents.ATTRIBUTE_MODIFIERS.get());
        if (abilities == null) throw new IllegalStateException("Bunny Hoppers have no Artifacts attributes");
        return abilities.entries().stream().filter(entry -> entry.attribute().equals(Attributes.JUMP_STRENGTH))
                .findFirst().orElseThrow();
    }

    static boolean ready(Player player, boolean equipped) {
        var container = shoes(player);
        var jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (container == null || jump == null) return false;
        var stack = container.getAccessories().getItem(0);
        var expected = jumpEffect().createModifier();
        var actual = jump.getModifier(expected.id());
        if (!equipped) return stack.isEmpty() && actual == null;
        return ITEM.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                && actual != null && actual.amount() > 0 && actual.equals(expected);
    }

    static Map<String, Object> sample(Player player) {
        Map<String, Object> result = new LinkedHashMap<>();
        var container = shoes(player);
        result.put("slot", SLOT);
        result.put("index", 0);
        result.put("slotAvailable", container != null);
        if (container != null) {
            result.put("functionalItem", itemId(container.getAccessories().getItem(0)));
            result.put("cosmeticItem", itemId(container.getCosmeticAccessories().getItem(0)));
        }
        var jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        var expected = jumpEffect().createModifier();
        result.put("expectedModifier", modifierDetails(expected));
        if (jump != null) {
            result.put("jumpStrengthBase", jump.getBaseValue());
            result.put("jumpStrength", jump.getValue());
            result.put("jumpModifiers", jump.getModifiers().stream()
                    .map(BunnySlippersEquipment::modifierDetails).toList());
            result.put("bunnyModifierApplied", expected.equals(jump.getModifier(expected.id())));
        }
        result.put("jumpBoostPower", player.getJumpBoostPower());
        result.put("safeFallDistance", player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE));
        result.put("fallDamageMultiplier", player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER));
        var stat = Stats.CUSTOM.get(Stats.JUMP);
        if (player instanceof ServerPlayer serverPlayer) result.put("serverJumps", serverPlayer.getStats().getValue(stat));
        if (player instanceof LocalPlayer localPlayer) result.put("cachedClientJumps", localPlayer.getStats().getValue(stat));
        return result;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static Map<String, Object> modifierDetails(AttributeModifier modifier) {
        return Map.of("id", modifier.id().toString(), "amount", modifier.amount(), "operation", modifier.operation().name());
    }
}
