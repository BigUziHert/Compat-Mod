package dev.caleb.emfattachments.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScopedGeometryMaskTest {
    @Test void idleScopesReuseOneNoopAndDoNotRetainRenderState() throws Exception {
        var mask = new ScopedGeometryMask<Object>();
        var idle = mask.open(false);
        for (int i = 0; i < 100; i++) {
            try (var another = mask.open(false)) {
                assertSame(idle, another);
                mask.seed(new Object()); mask.copied(new Object(), true);
            }
        }
        idle.close(); idle.close();
        assertNull(field(idle, "seeds")); assertNull(field(idle, "copies"));
        assertNull(((ThreadLocal<?>) field(mask, "current")).get());
    }

    @Test void identityStorageIsLazyAndUnmaskedCopiesDoNotCreateIt() throws Exception {
        var mask = new ScopedGeometryMask<Object>(); Object arm = new Object(), armor = new Object();
        try (var scope = mask.open(true)) {
            assertNull(field(scope, "seeds")); assertNull(field(scope, "copies"));
            mask.copied(armor, false);
            assertNull(field(scope, "copies"));
            mask.seed(arm);
            assertNotNull(field(scope, "seeds")); assertNull(field(scope, "copies"));
            mask.copied(armor, true);
            assertNotNull(field(scope, "copies")); assertTrue(mask.contains(armor));
        }
        assertNull(((ThreadLocal<?>) field(mask, "current")).get());
    }

    @Test void disabledBoundaryRetainsOuterMaskAcrossAnEnabledGrandchildAndException() throws Exception {
        var mask = new ScopedGeometryMask<Object>(); Object outerPart = new Object(), innerPart = new Object();
        try (var outer = mask.open(true)) {
            mask.seed(outerPart);
            try (var disabled = mask.open(false)) {
                assertNull(field(disabled, "seeds")); assertNull(field(disabled, "copies"));
                try (var idle = mask.open(false)) { assertFalse(mask.contains(outerPart)); }
                assertThrows(IllegalArgumentException.class, () -> {
                    try (var nested = mask.open(true)) {
                        mask.seed(innerPart); assertTrue(mask.contains(innerPart));
                        assertFalse(mask.contains(outerPart));
                        throw new IllegalArgumentException("render failed");
                    }
                });
                assertFalse(mask.contains(outerPart)); assertFalse(mask.contains(innerPart));
            }
            assertTrue(mask.contains(outerPart)); assertFalse(mask.contains(innerPart));
        }
    }

    @Test void outOfOrderCloseStillRejectsWithoutLosingTheActiveScope() {
        var mask = new ScopedGeometryMask<Object>(); Object arm = new Object();
        var outer = mask.open(true); mask.seed(arm);
        var child = mask.open(false);
        assertThrows(IllegalStateException.class, outer::close);
        assertFalse(mask.contains(arm));
        child.close(); assertTrue(mask.contains(arm));
        outer.close(); outer.close(); assertFalse(mask.contains(arm));
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Test void derivedMasksFollowCopyAndDoNotPersistOnReusedModels() {
        var mask = new ScopedGeometryMask<Object>();
        Object arm = new Object(), armor = new Object(), glove = new Object(), body = new Object();
        try (var ignored = mask.open(true)) {
            mask.seed(arm);
            mask.copied(armor, mask.contains(arm));
            mask.copied(glove, mask.contains(armor));
            assertTrue(mask.contains(arm)); assertTrue(mask.contains(armor)); assertTrue(mask.contains(glove));
            assertFalse(mask.contains(body));
            mask.copied(armor, mask.contains(body));
            assertFalse(mask.contains(armor));
            mask.copied(arm, false); // Animation state copies must not unmark the seeded player arm.
            assertTrue(mask.contains(arm));
        }
        assertFalse(mask.contains(arm)); assertFalse(mask.contains(glove));
    }

    @Test void disabledNestedRemoteOrShadowScopeCannotInheritLocalModelIdentity() {
        var mask = new ScopedGeometryMask<Object>(); Object sharedModel = new Object();
        try (var local = mask.open(true)) {
            mask.seed(sharedModel);
            try (var remote = mask.open(false)) {
                assertFalse(mask.contains(sharedModel)); mask.seed(sharedModel);
                assertFalse(mask.contains(sharedModel));
            }
            assertTrue(mask.contains(sharedModel));
        }
        assertFalse(mask.contains(sharedModel));
    }

    @Test void exceptionsRestoreOuterScopeAndIdentityDoesNotConfuseEqualParts() {
        var mask = new ScopedGeometryMask<Object>();
        Object arm = new String("equal"), unrelated = new String("equal"), nestedArm = new Object();
        try (var outer = mask.open(true)) {
            mask.seed(arm); assertFalse(mask.contains(unrelated));
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = mask.open(true)) {
                    mask.seed(nestedArm); assertTrue(mask.contains(nestedArm));
                    assertFalse(mask.contains(arm)); throw new IllegalStateException("injected render failure");
                }
            });
            assertTrue(mask.contains(arm)); assertFalse(mask.contains(nestedArm));
        }
        assertFalse(mask.contains(arm));
    }
}
