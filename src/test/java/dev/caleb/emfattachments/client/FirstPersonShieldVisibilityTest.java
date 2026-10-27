package dev.caleb.emfattachments.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FirstPersonShieldVisibilityTest {
    @Test void actualShellGeometryOmitsHiddenHeadButKeepsVisibleBodyAndRestoresModel() {
        HumanoidModel<LivingEntity> shield = mesh(), parent = mesh();
        parent.head.visible = false; parent.hat.visible = false;
        shield.head.z = 2.75f;
        assertEquals(168, vertices(shield));
        FirstPersonShieldVisibility.renderVisibleParts(shield, parent, () -> {
            assertFalse(shield.head.visible); assertFalse(shield.hat.visible);
            assertTrue(shield.body.visible); assertTrue(shield.leftArm.visible); assertTrue(shield.rightArm.visible);
            assertEquals(120, vertices(shield), "Only the two hidden camera-enclosing cubes should be removed");
        });
        assertEquals(168, vertices(shield)); assertEquals(2.75f, shield.head.z);
        assertFalse(parent.head.visible); assertFalse(parent.hat.visible);
    }

    @Test void exceptionalDrawRestoresOriginalVisibilityAndDepthWithoutRuntimeMixins() {
        ModelPart sourceHead = part(); sourceHead.visible = false;
        ModelPart targetHead = part(); targetHead.z = 3.25f;
        ModelPart targetHat = part(); targetHat.visible = false; targetHat.z = 77;
        HumanoidModel<LivingEntity> parent = model(sourceHead, part());
        HumanoidModel<LivingEntity> shield = model(targetHead, targetHat);
        RuntimeException expected = new RuntimeException("Intentional draw failure");
        assertSame(expected, assertThrows(RuntimeException.class, () -> FirstPersonShieldVisibility.renderVisibleParts(shield, parent, () -> {
            assertFalse(targetHead.visible); assertEquals(3.25f, targetHead.z);
            throw expected;
        })));
        assertTrue(targetHead.visible); assertEquals(3.25f, targetHead.z);
        assertFalse(targetHat.visible); assertEquals(77f, targetHat.z);
        assertFalse(sourceHead.visible);
    }

    private static HumanoidModel<LivingEntity> mesh() {
        return new HumanoidModel<>(LayerDefinition.create(HumanoidModel.createMesh(new CubeDeformation(1.1f), 0), 64, 64).bakeRoot());
    }
    private static HumanoidModel<LivingEntity> model(ModelPart head, ModelPart hat) {
        return new HumanoidModel<>(new ModelPart(List.of(), Map.of("head", head, "hat", hat,
                "body", part(), "left_arm", part(), "right_arm", part(),
                "left_leg", part(), "right_leg", part())));
    }
    private static int vertices(HumanoidModel<?> model) {
        Counter counter = new Counter(); model.renderToBuffer(new PoseStack(), counter, 15728880, 0); return counter.vertices;
    }
    private static ModelPart part() { return new ModelPart(List.of(), Map.of()); }
    private static final class Counter implements VertexConsumer {
        int vertices;
        @Override public VertexConsumer addVertex(float x, float y, float z) { vertices++; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
