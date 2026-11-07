package dev.caleb.emfattachments.client;

import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.api.client.AccessoriesRendererRegistry;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import io.wispforest.accessories_compat.curios.wrapper.CuriosConversionUtils;
import java.util.Set;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;

/** Stored Sable stacks omit the eye toggle; consult the actual online owner's slot. */
public final class RagdollAccessoryVisibility {
    private static final Set<BodyPart> BOTH_ARMS = Set.of(BodyPart.LEFT_ARM, BodyPart.RIGHT_ARM);
    private RagdollAccessoryVisibility() {}

    public static boolean shouldRenderStored(AccessoryRenderer renderer, SlotReference reference) {
        return allows(renderer, renderOption(reference));
    }

    public static boolean shouldRenderStored(SlotContext context, ItemStack stack) {
        if (context == null) return true;
        Boolean option = renderOption(CuriosConversionUtils.convertToA(context));
        if (option == null) return true;
        AccessoryRenderer renderer = AccessoriesRendererRegistry.getRenderer(stack);
        // Curios-only renderers still obey the native slot flag. A registered
        // Accessories renderer keeps its explicit shouldRender override.
        return renderer.isEmpty() ? option : allows(renderer, option);
    }

    public static Boolean renderOption(SlotReference reference) {
        // An owner-absent doll has no persisted visibility information. Never
        // borrow the local camera player's settings or invent hidden defaults.
        if (reference == null || !(reference.entity() instanceof Player) || reference.slot() < 0) return null;
        var capability = reference.capability();
        if (capability == null) return null;
        return renderOption(capability.getContainers().get(reference.slotName()), reference.slot());
    }

    public static Boolean renderOption(AccessoriesContainer container, int index) {
        return container == null || index < 0 || index >= container.getSize()
                ? null : container.shouldRender(index);
    }

    public static boolean allows(AccessoryRenderer renderer, Boolean option) {
        return option == null || renderer.shouldRender(option);
    }

    /** Correct unknown bridged head/hand slots while keeping Curios' single feet draw. */
    public static boolean routeCanonicalSlot(Set<BodyPart> canonicalParts, BodyPart physicalPart, boolean nativeResult) {
        if (canonicalParts.size() == 1 && canonicalParts.contains(BodyPart.HEAD)) {
            return physicalPart == BodyPart.HEAD;
        }
        // Namespaced glove slots otherwise fall back to the torso in Curios.
        // The per-part ledger cannot suppress that extra pair after Accessories
        // already drew one glove on each arm; route both paths to the same parts.
        return canonicalParts.equals(BOTH_ARMS) ? BOTH_ARMS.contains(physicalPart) : nativeResult;
    }
}
