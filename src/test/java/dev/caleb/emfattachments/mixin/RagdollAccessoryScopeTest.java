package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.RagdollAccessoryDrawScope;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.MultiBufferSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production native-render wrapper, including its exceptional exit. */
class RagdollAccessoryScopeTest {
    private static Object current() throws Exception {
        var field = RagdollAccessoryDrawScope.class.getDeclaredField("CURRENT");
        field.setAccessible(true);
        return ((ThreadLocal<?>) field.get(null)).get();
    }
    private static void render(Operation<Void> original) throws Exception {
        Method method = RagdollAccessoryScopeMixin.class.getDeclaredMethod("emfattachments$scopeAccessories",
                RagdollPartBlockEntity.class, float.class, PoseStack.class, MultiBufferSource.class, int.class, int.class, Operation.class);
        method.setAccessible(true);
        method.invoke(new RagdollAccessoryScopeMixin() {}, null, .5f, null, null, 17, 23, original);
    }
    @Test void separatePhysicalPartRendersGetFreshScopes() throws Exception {
        Object[] first = new Object[1];
        render(arguments -> {
            try { first[0] = current(); assertNotNull(first[0]); }
            catch (Exception failure) { throw new AssertionError(failure); }
            assertEquals(.5f, arguments[1]); assertEquals(17, arguments[4]); assertEquals(23, arguments[5]);
            return null;
        });
        assertNull(current());
        render(arguments -> {
            try { assertNotNull(current()); assertNotSame(first[0], current()); }
            catch (Exception failure) { throw new AssertionError(failure); }
            return null;
        });
        assertNull(current());
    }
    @Test void failedNestedNativeRenderRestoresOuterScopeAndThenClearsThread() throws Exception {
        RuntimeException rendererFailure = new RuntimeException("Intentional native renderer failure");
        try (var outer = RagdollAccessoryDrawScope.open()) {
            assertSame(outer, current());
            var failure = assertThrows(InvocationTargetException.class, () -> render(arguments -> {
                try { assertNotSame(outer, current()); assertNotNull(current()); }
                catch (Exception inspectionFailure) { throw new AssertionError(inspectionFailure); }
                throw rendererFailure;
            }));
            assertSame(rendererFailure, failure.getCause());
            assertSame(outer, current());
        }
        assertNull(current());
    }
}
