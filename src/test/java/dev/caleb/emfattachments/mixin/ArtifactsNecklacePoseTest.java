package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArtifactsNecklacePoseTest {
    @Test
    void realWrapperKeepsScaledNecklaceOnTranslatedRotatedAndScaledTorso() throws Exception {
        for (boolean young : new boolean[]{false, true}) {
            ArtifactsNecklacePoseMixin necklace = new ArtifactsNecklacePoseMixin(root()) {};
            necklace.body.setPos(1.75f, 3.2f, -5.5f);
            necklace.body.setRotation(0.6f, -0.25f, 0.15f);
            necklace.body.xScale = 1.1f;
            necklace.body.yScale = 0.9f;
            necklace.body.zScale = 1.05f;
            float[] before = pose(necklace.body);

            PoseStack expected = parent(young);
            necklace.body.translateAndRotate(expected);
            expected.scale(0.51f, 0.51f, 0.51f);

            PoseStack oldOrder = parent(young);
            oldOrder.scale(0.51f, 0.51f, 0.51f);
            necklace.body.translateAndRotate(oldOrder);
            assertFalse(expected.last().pose().equals(oldOrder.last().pose(), 0.001f),
                    "The original order must expose the regression for this animated torso");

            PoseStack actual = parent(young);
            AtomicInteger calls = new AtomicInteger();
            Operation<Void> nativeScale = arguments -> {
                calls.incrementAndGet();
                ((PoseStack) arguments[0]).scale((float) arguments[1], (float) arguments[2], (float) arguments[3]);
                return null;
            };
            Method wrapper = ArtifactsNecklacePoseMixin.class.getDeclaredMethod(
                    "emfattachments$preserveTorsoTranslation", PoseStack.class,
                    float.class, float.class, float.class, Operation.class);
            wrapper.setAccessible(true);
            wrapper.invoke(necklace, actual, 0.51f, 0.51f, 0.51f, nativeScale);
            necklace.body.translateAndRotate(actual);
            assertArrayEquals(expected.last().pose().get(new float[16]), actual.last().pose().get(new float[16]), 1.0e-6f);
            assertArrayEquals(expected.last().normal().get(new float[9]), actual.last().normal().get(new float[9]), 1.0e-6f);
            assertEquals(1, calls.get(), "Retain the native geometry scale exactly once");
            assertArrayEquals(before, pose(necklace.body), "Rendering must leave the copied body pose unchanged");
        }
    }

    @Test
    void zeroPivotPreservesOriginalStandingGeometry() throws Exception {
        ArtifactsNecklacePoseMixin necklace = new ArtifactsNecklacePoseMixin(root()) {};
        PoseStack expected = parent(false);
        expected.scale(0.51f, 0.51f, 0.51f);
        PoseStack actual = parent(false);
        Method wrapper = ArtifactsNecklacePoseMixin.class.getDeclaredMethod(
                "emfattachments$preserveTorsoTranslation", PoseStack.class,
                float.class, float.class, float.class, Operation.class);
        wrapper.setAccessible(true);
        Operation<Void> scale = arguments -> {
            ((PoseStack) arguments[0]).scale((float) arguments[1], (float) arguments[2], (float) arguments[3]);
            return null;
        };
        wrapper.invoke(necklace, actual, 0.51f, 0.51f, 0.51f, scale);
        assertArrayEquals(expected.last().pose().get(new float[16]), actual.last().pose().get(new float[16]), 0f);
    }

    private static PoseStack parent(boolean young) {
        PoseStack stack = new PoseStack();
        stack.translate(2, -1, 0.75);
        stack.mulPose(new Quaternionf().rotationXYZ(0.2f, -0.4f, 0.1f));
        stack.scale(0.5f, 0.5f, 0.5f); // Real entity scale, independent of necklace scale.
        if (young) {
            stack.scale(0.5f, 0.5f, 0.5f);
            stack.translate(0, 24f / 16f, 0); // Preserve the native baby-model branch.
        }
        return stack;
    }

    private static float[] pose(ModelPart part) {
        return new float[]{part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                part.xScale, part.yScale, part.zScale};
    }

    private static ModelPart root() {
        Map<String, ModelPart> parts = new HashMap<>();
        for (String name : List.of("head", "hat", "body", "left_arm", "right_arm", "left_leg", "right_leg"))
            parts.put(name, new ModelPart(List.of(), Map.of()));
        return new ModelPart(List.of(), parts);
    }
}
