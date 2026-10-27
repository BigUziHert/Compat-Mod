package dev.caleb.emfattachments.client;

import io.wispforest.accessories.api.slot.SlotReference;
import io.wispforest.accessories_compat.curios.wrapper.CuriosConversionUtils;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;

/** Matches successful accessory draws inside one physical ragdoll part. */
public final class RagdollAccessoryDrawScope implements AutoCloseable {
    private static final ThreadLocal<RagdollAccessoryDrawScope> CURRENT = new ThreadLocal<>();
    private final RagdollAccessoryDrawScope previous;
    private final Map<Slot, ItemStack> completedDraws = new HashMap<>();
    private boolean closed;

    private RagdollAccessoryDrawScope() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static RagdollAccessoryDrawScope open() {
        return new RagdollAccessoryDrawScope();
    }

    public static void completed(SlotReference reference, ItemStack stack) {
        RagdollAccessoryDrawScope scope = CURRENT.get();
        if (scope == null || reference == null || stack.isEmpty()) return;
        scope.completedDraws.put(new Slot(reference.entity(), reference.slotName(), reference.slot()), stack.copy());
    }

    public static boolean isDuplicate(SlotContext context, ItemStack stack) {
        RagdollAccessoryDrawScope scope = CURRENT.get();
        if (scope == null || context == null || stack.isEmpty()) return false;
        // Use the installed bridge's canonical conversion, including its
        // configurable curio->pocket/any mapping, rather than an alias guess.
        SlotReference reference = CuriosConversionUtils.convertToA(context);
        ItemStack prior = scope.completedDraws.get(new Slot(reference.entity(), reference.slotName(), reference.slot()));
        if (prior == null) return false;
        if (ItemStack.matches(prior, stack)) return true;
        // Sable stores Accessories' selected cosmetic but Curios' actual stack.
        // Suppress the latter only with an exact live-slot witness that the
        // cosmetic replacing this very stack already completed on this part.
        // A failed draw, missing owner, changed stack or unrelated item keeps
        // the original fallback; never treat different stacks as equal.
        if (!(reference.entity() instanceof Player) || reference.slot() < 0) return false;
        var capability = reference.capability();
        if (capability == null) return false;
        var container = capability.getContainers().get(reference.slotName());
        if (container == null || reference.slot() >= container.getSize()) return false;
        ItemStack actual = container.getAccessories().getItem(reference.slot());
        ItemStack cosmetic = container.getCosmeticAccessories().getItem(reference.slot());
        return !cosmetic.isEmpty() && ItemStack.matches(cosmetic, prior) && ItemStack.matches(actual, stack);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        completedDraws.clear();
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    /** Entity identity keeps other players independent even within nested draws. */
    private record Slot(LivingEntity entity, String name, int index) {
        @Override public boolean equals(Object other) {
            return other instanceof Slot slot && entity == slot.entity && index == slot.index && name.equals(slot.name);
        }
        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(entity) + name.hashCode()) + index;
        }
    }
}
