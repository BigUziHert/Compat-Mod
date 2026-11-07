package dev.caleb.emfattachments.client;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real arm/held-item scope wiring without a running Minecraft client. */
class FirstPersonArmGeometryTest {
    @Test void pistolFreeArmExemptsOnlyItsExactBodyArmAndSleeveDuringTheOwnedScope() throws Exception {
        var body = new PlayerModel<LivingEntity>(LayerDefinition.create(
                PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot(), false);
        var other = new PlayerModel<LivingEntity>(LayerDefinition.create(
                PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot(), false);
        var mask = mask();
        for (int bits : new int[]{1, 2, 3}) {
            var scope = FirstPersonArmGeometry.openScope(null, true);
            try (scope) {
                FirstPersonArmGeometry.seedArms(body, bits);
                scope.keepFreeArm(body, bits);
                assertEquals(bits == 2, scope.keeps(body.rightArm));
                assertEquals(bits == 1, scope.keeps(body.leftArm));
                assertEquals(bits == 2, scope.keeps(body.rightSleeve));
                assertEquals(bits == 1, scope.keeps(body.leftSleeve));
                assertFalse(scope.keeps(body.head)); assertFalse(scope.keeps(body.hat));
                assertFalse(scope.keeps(body.body)); assertFalse(scope.keeps(body.rightLeg));
                assertFalse(scope.keeps(other.rightArm)); assertFalse(scope.keeps(other.leftArm));
                assertEquals((bits & 1) != 0, mask.contains(body.rightArm));
                assertEquals((bits & 2) != 0, mask.contains(body.leftArm));
                try (var held = FirstPersonArmGeometry.suspendForHeldItems()) {
                    assertFalse(held.keeps(body.rightArm)); assertFalse(held.keeps(body.leftArm));
                }
                assertSame(scope, current().get());
            }
            assertFalse(scope.keeps(body.rightArm)); assertFalse(scope.keeps(body.leftArm));
            assertNull(current().get());
        }
    }

    @Test void gunBodyMaskSurvivesAnimationResetsWithoutMaskingTorsoLegsOrPrivateHands() throws Exception {
        var body = new PlayerModel<LivingEntity>(LayerDefinition.create(
                PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot(), false);
        ModelPart privateHand = new ModelPart(java.util.List.of(), java.util.Map.of());
        var mask = mask();
        float oldX = body.rightArm.x, oldY = body.rightArm.y, oldZ = body.rightArm.z;
        try (var scope = FirstPersonArmGeometry.openScope(null, true)) {
            FirstPersonArmGeometry.seedArms(body, 3);
            assertEquals(oldX, body.rightArm.x); assertEquals(oldY, body.rightArm.y); assertEquals(oldZ, body.rightArm.z);
            // EMF can replace every pose/visibility value; geometry ownership is independent.
            body.rightArm.setPos(0, 0, 0); body.rightArm.visible = true;
            body.leftArm.setPos(0, 0, 0); body.leftArm.visible = true;
            for (ModelPart arm : java.util.List.of(body.rightArm, body.leftArm, body.rightSleeve, body.leftSleeve)) {
                assertTrue(arm.getAllParts().allMatch(mask::contains));
            }
            for (ModelPart retained : java.util.List.of(body.body, body.head, body.rightLeg, body.leftLeg, privateHand)) {
                assertFalse(mask.contains(retained));
            }
            try (var item = FirstPersonArmGeometry.suspendForHeldItems()) {
                assertFalse(mask.contains(body.rightArm));
                assertFalse(mask.contains(privateHand));
            }
            assertTrue(mask.contains(body.rightArm));
        }
        assertFalse(mask.contains(body.rightArm)); assertFalse(mask.contains(body.leftSleeve));
        assertFalse(mask.contains(privateHand));
    }

    @Test void oneArmAnimationKeepsItsOtherArmAndSleeve() throws Exception {
        var body = new PlayerModel<LivingEntity>(LayerDefinition.create(
                PlayerModel.createMesh(CubeDeformation.NONE, true), 64, 64).bakeRoot(), true);
        var mask = mask();
        for (int bits : new int[]{1, 2}) {
            try (var scope = FirstPersonArmGeometry.openScope(null, true)) {
                FirstPersonArmGeometry.seedArms(body, bits);
                assertEquals(bits == 1, mask.contains(body.rightArm));
                assertEquals(bits == 1, mask.contains(body.rightSleeve));
                assertEquals(bits == 2, mask.contains(body.leftArm));
                assertEquals(bits == 2, mask.contains(body.leftSleeve));
                assertFalse(mask.contains(body.body));
            }
        }
    }

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
