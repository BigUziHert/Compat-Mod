package dev.caleb.emfattachments.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real arm/held-item scope wiring without a running Minecraft client. */
class FirstPersonArmGeometryTest {
    @Test void unrelatedEntitiesAndUnmaskedHeldItemsShareTheInactiveNoop() throws Exception {
        var inactive = FirstPersonArmGeometry.openScope(null, false);
        for (int i = 0; i < 100; i++) {
            try (var entity = FirstPersonArmGeometry.openScope(null, false);
                 var held = FirstPersonArmGeometry.suspendForHeldItems()) {
                assertSame(inactive, entity); assertSame(inactive, held);
            }
        }
        inactive.close(); inactive.close();
        assertNull(current().get());
    }

    @Test void heldAndNonlocalDrawsSuspendAndRestoreActualMaskEvenWhenTheyThrow() throws Exception {
        var mask = mask(); Object arm = new Object(), heldGeometry = new Object();
        try (var outer = FirstPersonArmGeometry.openScope(null, true)) {
            mask.seed(arm);
            assertThrows(IllegalArgumentException.class, () -> {
                try (var held = FirstPersonArmGeometry.suspendForHeldItems()) {
                    assertFalse(mask.contains(arm));
                    mask.copied(heldGeometry, true);
                    assertFalse(mask.contains(heldGeometry));
                    try (var remote = FirstPersonArmGeometry.openScope(null, false)) {
                        assertFalse(mask.contains(arm));
                    }
                    throw new IllegalArgumentException("held renderer failed");
                }
            });
            assertTrue(mask.contains(arm)); assertFalse(mask.contains(heldGeometry));
            assertSame(outer, current().get());
        }
        assertFalse(mask.contains(arm)); assertNull(current().get());
    }

    @Test void enabledGrandchildDoesNotLeakThroughDisabledBoundary() throws Exception {
        var mask = mask(); Object outerArm = new Object(), innerArm = new Object();
        try (var outer = FirstPersonArmGeometry.openScope(null, true)) {
            mask.seed(outerArm);
            try (var remote = FirstPersonArmGeometry.openScope(null, false)) {
                try (var nested = FirstPersonArmGeometry.openScope(null, true)) {
                    mask.seed(innerArm);
                    assertTrue(mask.contains(innerArm)); assertFalse(mask.contains(outerArm));
                }
                assertFalse(mask.contains(innerArm)); assertFalse(mask.contains(outerArm));
            }
            assertTrue(mask.contains(outerArm)); assertFalse(mask.contains(innerArm));
        }
        assertNull(current().get());
    }

    @Test void activeScopesStillRejectOutOfOrderCloseAndCanRecover() throws Exception {
        var outer = FirstPersonArmGeometry.openScope(null, true);
        var held = FirstPersonArmGeometry.suspendForHeldItems();
        assertThrows(IllegalStateException.class, outer::close);
        assertSame(held, current().get());
        held.close(); assertSame(outer, current().get());
        outer.close(); outer.close(); assertNull(current().get());
    }

    @SuppressWarnings("unchecked")
    private static ScopedGeometryMask<Object> mask() throws Exception {
        var field = FirstPersonArmGeometry.class.getDeclaredField("MASK");
        field.setAccessible(true);
        return (ScopedGeometryMask<Object>) field.get(null);
    }

    private static ThreadLocal<?> current() throws Exception {
        var field = FirstPersonArmGeometry.class.getDeclaredField("CURRENT");
        field.setAccessible(true);
        return (ThreadLocal<?>) field.get(null);
    }
}
