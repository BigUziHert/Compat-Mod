package dev.caleb.emfattachments.client;

import artifacts.client.item.model.ArmsModel;
import artifacts.client.item.ArtifactLayers;
import artifacts.client.item.renderer.GloveArtifactRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactsFirstPersonArmPoseTest {
    @Test void installedGloveMeshesFollowAllNineFieldsOnBothSidesAndBothSkinWidths() {
        // Use the installed registration suppliers. The convenience model
        // factories fetch Minecraft's live EntityModelSet, absent in JUnit.
        Map<ModelLayerLocation, Supplier<LayerDefinition>> layers = new HashMap<>();
        ArtifactLayers.register(layers::put);
        List<Function<Boolean, ModelLayerLocation>> nativeModels = List.of(
                ArtifactLayers::claws, ArtifactLayers::glove,
                ArtifactLayers::pocketPiston, ArtifactLayers::goldenHook,
                ArtifactLayers::onionRing, ArtifactLayers::pickaxeHeater,
                ArtifactLayers::witheredBracelet);
        for (var factory : nativeModels) for (boolean slim : new boolean[]{false, true}) {
            Supplier<LayerDefinition> definition = layers.get(factory.apply(slim));
            assertNotNull(definition);
            ArmsModel actual = new ArmsModel(definition.get().bakeRoot());
            ArmsModel expected = new ArmsModel(definition.get().bakeRoot());
            for (boolean left : new boolean[]{false, true}) {
                ModelPart source = animated(left), target = left ? actual.leftArm : actual.rightArm;
                ModelPart expectedArm = left ? expected.leftArm : expected.rightArm;
                target.visible = expectedArm.visible = true;
                source.visible = false; source.skipDraw = true; // Not part of a pose copy.
                expectedArm.copyFrom(source);
                float[] original = pose(target), originalSource = pose(source);
                List<Float> vertices = vertices(expectedArm);
                assertFalse(vertices.isEmpty(), "Exercise the actual installed accessory geometry");
                assertNotEquals(vertices, vertices(target), "The native resting pose must reproduce the mismatch");
                ArtifactsFirstPersonArmPose.withPose(source, () -> ArtifactsFirstPersonArmPose.render(target, () -> {
                    assertArrayEquals(originalSource, pose(target));
                    assertTrue(target.visible); assertFalse(target.skipDraw);
                    assertEquals(vertices, vertices(target));
                    assertEquals(vertices, vertices(target), "A second glowing-overlay draw shares the same transform");
                }));
                assertArrayEquals(original, pose(target));
                assertArrayEquals(originalSource, pose(source));
                assertFalse(source.visible); assertTrue(source.skipDraw);
            }
        }
    }

    @Test void exceptionalDrawRestoresAllTransformsAndReleasesTheSource() {
        ModelPart source = animated(false), target = animated(true);
        target.xScale = -0.75f; target.yScale = 1.25f; target.zScale = 0.4f;
        target.visible = false; target.skipDraw = true;
        float[] before = pose(target), sourceBefore = pose(source);
        RuntimeException failure = new RuntimeException("intentional renderer failure");
        assertSame(failure, assertThrows(RuntimeException.class, () ->
                ArtifactsFirstPersonArmPose.withPose(source, () ->
                        ArtifactsFirstPersonArmPose.render(target, () -> {
                            assertArrayEquals(sourceBefore, pose(target));
                            throw failure;
                        }))));
        assertArrayEquals(before, pose(target));
        assertArrayEquals(sourceBefore, pose(source));
        assertFalse(target.visible); assertTrue(target.skipDraw);
        ArtifactsFirstPersonArmPose.render(target, () -> assertArrayEquals(before, pose(target)));
    }

    @Test void invalidNestedContextDoesNotReuseAnotherHandAndOuterSnapshotSurvives() {
        ModelPart left = animated(true), right = animated(false), target = part();
        float[] targetBefore = pose(target), leftBefore = pose(left), rightBefore = pose(right);
        ArtifactsFirstPersonArmPose.withPose(left, () -> {
            // The snapshot cannot turn into another entity's pose if a shared
            // player model is subsequently reused by a nested render.
            left.x += 100;
            ArtifactsFirstPersonArmPose.render(target, () -> assertArrayEquals(leftBefore, pose(target)));
            ArtifactsFirstPersonArmPose.withPose(null, () ->
                    ArtifactsFirstPersonArmPose.render(target, () -> assertArrayEquals(targetBefore, pose(target))));
            ArtifactsFirstPersonArmPose.withPose(right, () ->
                    ArtifactsFirstPersonArmPose.render(target, () -> assertArrayEquals(rightBefore, pose(target))));
            ArtifactsFirstPersonArmPose.render(target, () -> assertArrayEquals(leftBefore, pose(target)));
        });
        assertArrayEquals(targetBefore, pose(target));
        ArtifactsFirstPersonArmPose.render(target, () -> assertArrayEquals(targetBefore, pose(target)));
    }

    @Test void installedFirstPersonResetPrecedesTheSingleVirtualBaseAndGlowDrawBoundary() throws Exception {
        List<String> operations = new ArrayList<>();
        try (InputStream bytecode = GloveArtifactRenderer.class.getResourceAsStream("GloveArtifactRenderer.class")) {
            assertNotNull(bytecode);
            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (!name.equals("renderFirstPersonArm") || !descriptor.contains("AbstractClientPlayer")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                               String desc, boolean isInterface) {
                            if (method.equals("setupAnim")) operations.add("setup");
                            if (method.equals("renderFirstPersonArm") && desc.contains("ArmsModel")) {
                                assertEquals(Opcodes.INVOKEVIRTUAL, opcode, "Retain the glowing subclass override");
                                operations.add("draw");
                            }
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (opcode == Opcodes.PUTFIELD && name.equals("xRot")) operations.add("resetX");
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(List.of("setup", "resetX", "draw"), operations,
                "Copying at the public method HEAD would be overwritten by this native reset");
    }

    private static ModelPart animated(boolean left) {
        ModelPart part = part();
        part.setPos(left ? 5.8f : -4.7f, 3.1f, -1.25f);
        part.setRotation(0.23f, left ? -0.44f : 0.37f, left ? -0.31f : 0.26f);
        part.xScale = 1.2f; part.yScale = 0.7f; part.zScale = 1.5f;
        return part;
    }
    private static ModelPart part() { return new ModelPart(List.of(), Map.of()); }
    private static float[] pose(ModelPart part) {
        return new float[]{part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                part.xScale, part.yScale, part.zScale};
    }
    private static List<Float> vertices(ModelPart part) {
        Counter result = new Counter();
        PoseStack outer = new PoseStack(); outer.translate(0.2, -0.1, 0.3); outer.scale(0.5f, 0.5f, 0.5f);
        part.render(outer, result, 15728880, 0, -1);
        return result.values;
    }
    private static final class Counter implements VertexConsumer {
        final List<Float> values = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { values.add(x); values.add(y); values.add(z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { values.add(x); values.add(y); values.add(z); return this; }
    }
}
