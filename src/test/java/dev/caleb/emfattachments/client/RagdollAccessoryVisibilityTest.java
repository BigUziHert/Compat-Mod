package dev.caleb.emfattachments.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import io.wispforest.accessories_compat.curios.wrapper.CuriosConversionUtils;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Native policies/classifiers execute here; transformed render hooks are runtime-probed separately. */
class RagdollAccessoryVisibilityTest {
    @Test void installedRendererDefaultAndExplicitOverridesRemainAuthoritative() {
        AccessoryRenderer ordinary = new NoDrawRenderer();
        assertTrue(RagdollAccessoryVisibility.allows(ordinary, true));
        assertFalse(RagdollAccessoryVisibility.allows(ordinary, false));

        AtomicInteger calls = new AtomicInteger();
        AccessoryRenderer always = new NoDrawRenderer() {
            @Override public boolean shouldRender(boolean option) { calls.incrementAndGet(); return true; }
        };
        AccessoryRenderer never = new NoDrawRenderer() {
            @Override public boolean shouldRender(boolean option) { calls.incrementAndGet(); return false; }
        };
        assertTrue(RagdollAccessoryVisibility.allows(always, false), "Preserve explicit renderer override");
        assertTrue(RagdollAccessoryVisibility.allows(always, true));
        assertFalse(RagdollAccessoryVisibility.allows(never, false));
        assertFalse(RagdollAccessoryVisibility.allows(never, true));
        assertEquals(4, calls.get());
        assertTrue(RagdollAccessoryVisibility.allows(always, null));
        assertTrue(RagdollAccessoryVisibility.allows(never, null), "Unknown owner state keeps the native stored path");
        assertEquals(4, calls.get(), "Unknown must not invent a boolean and call renderer policy");
    }

    @Test void boundsDoNotReadOptionsAndValidSlotsUseTheInstalledContainerDefault() {
        AtomicInteger reads = new AtomicInteger();
        List<Boolean> options = List.of(false, true);
        AccessoriesContainer container = container(3, options, reads);
        assertNull(RagdollAccessoryVisibility.renderOption((AccessoriesContainer) null, 0));
        for (int invalid : new int[]{-2, -1, 3, Integer.MAX_VALUE}) {
            assertNull(RagdollAccessoryVisibility.renderOption(container, invalid));
        }
        assertEquals(0, reads.get(), "Invalid indices must not consult a render flag or mutate anything");
        assertEquals(Boolean.FALSE, RagdollAccessoryVisibility.renderOption(container, 0));
        assertEquals(Boolean.TRUE, RagdollAccessoryVisibility.renderOption(container, 1));
        assertEquals(Boolean.TRUE, RagdollAccessoryVisibility.renderOption(container, 2),
                "The native container defaults an existing slot with no stored flag to visible");
        assertEquals(3, reads.get());
        assertEquals(List.of(false, true), options);
    }

    @Test void unknownSlotReferenceDoesNotReadAnotherPlayersCapability() {
        assertNull(RagdollAccessoryVisibility.renderOption((SlotReference) null));
        // Actual sealed API record with no owner: the guard must stop before capability().
        assertNull(RagdollAccessoryVisibility.renderOption(SlotReference.of(null, "face", 0)));
        assertNull(RagdollAccessoryVisibility.renderOption(SlotReference.of(null, "face", -1)));
        assertTrue(RagdollAccessoryVisibility.shouldRenderStored(new NoDrawRenderer(), null));
    }

    @Test void nativeFaceHatAndGoggleClassifiersNowSelectOnlyThePhysicalHead() throws Exception {
        for (String slot : List.of("face", "hat", "head", "goggles", "custom:face_slot")) {
            Set<BodyPart> canonical = nativeAccessoriesParts(CuriosConversionUtils.slotConvertToA(slot));
            assertEquals(Set.of(BodyPart.HEAD), canonical, slot);
            if (!slot.equals("head")) {
                assertTrue(nativeCuriosPart(slot, BodyPart.TORSO), "Expose the installed unknown-slot fallback");
                assertFalse(nativeCuriosPart(slot, BodyPart.HEAD));
            }
            for (BodyPart physical : BodyPart.values()) {
                assertEquals(physical == BodyPart.HEAD,
                        RagdollAccessoryVisibility.routeHeadSlot(canonical, physical, nativeCuriosPart(slot, physical)),
                        slot + " / " + physical);
            }
        }
    }

    @Test void nativeFeetAndArmRoutingRemainExactlyUnchanged() throws Exception {
        assertEquals(Set.of(BodyPart.LEFT_LEG, BodyPart.RIGHT_LEG), nativeAccessoriesParts("shoes"));
        assertFalse(nativeCuriosPart("feet", BodyPart.LEFT_LEG));
        assertTrue(nativeCuriosPart("feet", BodyPart.RIGHT_LEG));
        for (String slot : List.of("feet", "ring", "hands", "bracelet", "wrist", "necklace", "unknown_slot")) {
            Set<BodyPart> canonical = nativeAccessoriesParts(CuriosConversionUtils.slotConvertToA(slot));
            for (BodyPart physical : BodyPart.values()) {
                boolean original = nativeCuriosPart(slot, physical);
                assertEquals(original, RagdollAccessoryVisibility.routeHeadSlot(canonical, physical, original),
                        "Do not broaden installed non-head routing: " + slot + " / " + physical);
            }
        }
        for (BodyPart physical : BodyPart.values()) {
            assertFalse(RagdollAccessoryVisibility.routeHeadSlot(Set.of(BodyPart.HEAD, BodyPart.TORSO), physical, false));
            assertTrue(RagdollAccessoryVisibility.routeHeadSlot(Set.of(BodyPart.HEAD, BodyPart.TORSO), physical, true));
        }
    }

    @Test void productionMethodHandlePreservesNativeFailureAndRemainsUsable() throws Exception {
        Class<?> nativeHelper = Class.forName("dev.leo.sableplayerragdoll.neoforge.client.AccessoriesRenderHelper");
        Method method = nativeHelper.getDeclaredMethod("resolveSlot", String.class);
        method.setAccessible(true);
        InvocationTargetException nativeFailure = assertThrows(InvocationTargetException.class,
                () -> method.invoke(null, new Object[]{null}));
        RuntimeException productionFailure = assertThrows(RuntimeException.class,
                () -> RagdollAccessoriesSlotRouting.resolveSlot(null));
        assertEquals(nativeFailure.getCause().getClass(), productionFailure.getClass(),
                "The typed method-handle call must retain native unchecked failure semantics");
        assertEquals(Set.of(BodyPart.HEAD), RagdollAccessoriesSlotRouting.resolveSlot("face"),
                "A native caller error must not poison the cached resolver");
    }

    private static AccessoriesContainer container(int size, List<Boolean> options, AtomicInteger reads) {
        return (AccessoriesContainer) Proxy.newProxyInstance(AccessoriesContainer.class.getClassLoader(),
                new Class<?>[]{AccessoriesContainer.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "getSize" -> size;
                        case "renderOptions" -> { reads.incrementAndGet(); yield options; }
                        case "shouldRender" -> InvocationHandler.invokeDefault(proxy, method, arguments);
                        default -> throw new AssertionError("Unexpected container access/mutation: " + method);
                    };
                });
    }

    @SuppressWarnings("unchecked")
    private static Set<BodyPart> nativeAccessoriesParts(String slot) throws Exception {
        Class<?> nativeHelper = Class.forName("dev.leo.sableplayerragdoll.neoforge.client.AccessoriesRenderHelper");
        Method method = nativeHelper.getDeclaredMethod("resolveSlot", String.class);
        method.setAccessible(true);
        Set<BodyPart> installed = (Set<BodyPart>) method.invoke(null, slot);
        Set<BodyPart> production = RagdollAccessoriesSlotRouting.resolveSlot(slot);
        assertEquals(installed, production, "Production lookup must match the actual installed resolver: " + slot);
        assertEquals(installed, RagdollAccessoriesSlotRouting.resolveSlot(slot), "Repeated cached lookup: " + slot);
        return production;
    }

    private static boolean nativeCuriosPart(String slot, BodyPart part) throws Exception {
        Class<?> nativeHelper = Class.forName("dev.leo.sableplayerragdoll.neoforge.client.CuriosRenderHelper");
        Method method = nativeHelper.getDeclaredMethod("slotBelongsToPart", String.class, BodyPart.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, slot, part);
    }

    private static class NoDrawRenderer implements AccessoryRenderer {
        @Override public <E extends LivingEntity> void render(ItemStack stack, SlotReference reference,
                PoseStack pose, EntityModel<E> model, MultiBufferSource buffers, int light,
                float limbSwing, float amount, float partial, float age, float yaw, float pitch) {
            throw new AssertionError("Policy tests must not render or modify equipment");
        }
    }
}
