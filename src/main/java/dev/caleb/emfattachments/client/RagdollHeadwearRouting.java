package dev.caleb.emfattachments.client;

import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import io.wispforest.accessories.api.AccessoriesCapability;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosCapability;

/** Route fixed head geometry by the selected item, including flexible storage slots. */
public final class RagdollHeadwearRouting {
    private RagdollHeadwearRouting() {}

    public static boolean isHeadModel(ItemStack stack) {
        return !stack.isEmpty() && AccessoryVisibilityPolicy.isHeadModel(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    public static boolean belongsToPart(boolean headModel, BodyPart part, boolean nativeResult) {
        return headModel ? part == BodyPart.HEAD : nativeResult;
    }

    public static ItemStack selected(List<ItemStack> actual, List<ItemStack> cosmetics, int index) {
        if (index < 0) return ItemStack.EMPTY;
        ItemStack cosmetic = index < cosmetics.size() ? cosmetics.get(index) : ItemStack.EMPTY;
        return !cosmetic.isEmpty() ? cosmetic : index < actual.size() ? actual.get(index) : ItemStack.EMPTY;
    }

    public static ItemStack storedAccessory(RagdollPartBlockEntity stored, String slot, int index) {
        return selected(stored.getAccessoriesItems().getOrDefault(slot, List.of()),
                stored.getAccessoriesCosmeticItems().getOrDefault(slot, List.of()), index);
    }

    public static ItemStack liveAccessory(LivingEntity entity, String slot, int index) {
        var capability = AccessoriesCapability.get(entity);
        var container = capability == null ? null : capability.getContainers().get(slot);
        if (container == null || index < 0 || index >= container.getSize()) return ItemStack.EMPTY;
        ItemStack cosmetic = container.getCosmeticAccessories().getItem(index);
        return !cosmetic.isEmpty() ? cosmetic : container.getAccessories().getItem(index);
    }

    public static boolean storedCurioHasHeadModel(RagdollPartBlockEntity stored, String slot) {
        List<ItemStack> actual = stored.getCurioItems().getOrDefault(slot, List.of());
        List<ItemStack> cosmetics = stored.getCurioCosmeticItems().getOrDefault(slot, List.of());
        for (int i = 0; i < Math.max(actual.size(), cosmetics.size()); i++) {
            if (isHeadModel(selected(actual, cosmetics, i))) return true;
        }
        return false;
    }

    public static boolean liveCurioHasHeadModel(LivingEntity entity, String slot) {
        var capability = entity.getCapability(CuriosCapability.INVENTORY);
        var container = capability == null ? null : capability.getCurios().get(slot);
        if (container == null) return false;
        var actual = container.getStacks();
        var cosmetics = container.getCosmeticStacks();
        // Keep the native live path's iteration bound; cosmetics-only entries
        // beyond its actual slot count are not invented by this integration.
        for (int i = 0; i < actual.getSlots(); i++) {
            ItemStack cosmetic = i < cosmetics.getSlots() ? cosmetics.getStackInSlot(i) : ItemStack.EMPTY;
            if (isHeadModel(!cosmetic.isEmpty() ? cosmetic : actual.getStackInSlot(i))) return true;
        }
        return false;
    }

    /** Native slot classification, including the pre-existing canonical head correction. */
    public static boolean curioBelongsToPart(String slot, BodyPart part) {
        try {
            return (boolean) CurioRouting.METHOD.invokeExact(slot, part);
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("Cannot resolve Sable Curios slot", error);
        }
    }

    // Resolve lazily: pure routing tests and the Accessories-only route do not
    // need to initialize Sable's private Curios renderer class.
    private static final class CurioRouting {
        private static final MethodHandle METHOD = resolve();
        private static MethodHandle resolve() {
            try {
                var type = Class.forName("dev.leo.sableplayerragdoll.neoforge.client.CuriosRenderHelper");
                var method = type.getDeclaredMethod("slotBelongsToPart", String.class, BodyPart.class);
                method.setAccessible(true);
                return MethodHandles.lookup().unreflect(method);
            } catch (ReflectiveOperationException error) {
                throw new ExceptionInInitializerError(error);
            }
        }
    }
}
