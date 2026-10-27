package dev.caleb.emfattachments.client;

import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Set;

/** Cached access avoids a direct cross-module call to Sable's package-private helper. */
public final class RagdollAccessoriesSlotRouting {
    private static final MethodHandle RESOLVE_SLOT = resolver();
    private RagdollAccessoriesSlotRouting() {}

    private static MethodHandle resolver() {
        try {
            var type = Class.forName("dev.leo.sableplayerragdoll.neoforge.client.AccessoriesRenderHelper");
            var method = type.getDeclaredMethod("resolveSlot", String.class);
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @SuppressWarnings("unchecked")
    public static Set<BodyPart> resolveSlot(String canonicalSlot) {
        try {
            return (Set<BodyPart>) RESOLVE_SLOT.invokeExact(canonicalSlot);
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("Cannot resolve Sable accessory slot", error);
        }
    }
}
