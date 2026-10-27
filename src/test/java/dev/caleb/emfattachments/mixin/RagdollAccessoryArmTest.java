package dev.caleb.emfattachments.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.RagdollAccessoryArmScope;
import java.lang.reflect.Method;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the actual injection methods; no invented slot-to-hand mapping. */
class RagdollAccessoryArmTest {
    private static boolean canceled(boolean artifact, HumanoidArm selected) throws Exception {
        Object mixin = artifact ? new RagdollArtifactArmMixin() {} : new RagdollCataclysmArmMixin() {};
        Class<?> type = artifact ? RagdollArtifactArmMixin.class : RagdollCataclysmArmMixin.class;
        Class<?>[] signature = artifact
                ? new Class<?>[]{HumanoidArm.class, PoseStack.class, VertexConsumer.class, int.class, int.class, int.class, CallbackInfo.class}
                : new Class<?>[]{HumanoidArm.class, PoseStack.class, VertexConsumer.class, int.class, int.class, CallbackInfo.class};
        Method method = type.getDeclaredMethod("emfattachments$onlyPhysicalArm", signature);
        method.setAccessible(true);
        CallbackInfo info = new CallbackInfo("renderArm", true);
        if (artifact) method.invoke(mixin, selected, null, null, 17, 23, -1, info);
        else method.invoke(mixin, selected, null, null, 17, 23, info);
        return info.isCancelled();
    }

    @Test void nativeChosenArmDrawsOnlyOnItsOwnPhysicalPart() throws Exception {
        for (boolean artifact : new boolean[]{false, true}) {
            for (HumanoidArm physical : HumanoidArm.values()) {
                try (var ignored = RagdollAccessoryArmScope.open(physical)) {
                    assertFalse(canceled(artifact, physical));
                    assertTrue(canceled(artifact, physical.getOpposite()));
                    // Glow/foil passes reuse the selected arm and obey the same rule.
                    assertFalse(canceled(artifact, physical));
                    assertTrue(canceled(artifact, physical.getOpposite()));
                }
            }
        }
    }

    @Test void ordinaryAndNonArmDrawsAreUnchangedAndNestedCallsRestoreScope() throws Exception {
        for (boolean artifact : new boolean[]{false, true}) {
            assertFalse(canceled(artifact, HumanoidArm.LEFT));
            assertFalse(canceled(artifact, HumanoidArm.RIGHT));
            try (var outer = RagdollAccessoryArmScope.open(HumanoidArm.RIGHT)) {
                try (var nonArm = RagdollAccessoryArmScope.open(null)) {
                    assertFalse(canceled(artifact, HumanoidArm.LEFT));
                    assertFalse(canceled(artifact, HumanoidArm.RIGHT));
                }
                assertTrue(canceled(artifact, HumanoidArm.LEFT));
                RuntimeException expected = new RuntimeException("native draw failure");
                assertSame(expected, assertThrows(RuntimeException.class, () -> {
                    try (var inner = RagdollAccessoryArmScope.open(HumanoidArm.LEFT)) {
                        assertTrue(RagdollAccessoryArmScope.hides(HumanoidArm.RIGHT));
                        throw expected;
                    }
                }));
                assertTrue(canceled(artifact, HumanoidArm.LEFT));
                assertFalse(canceled(artifact, HumanoidArm.RIGHT));
            }
            assertFalse(canceled(artifact, HumanoidArm.LEFT));
        }
    }
}
