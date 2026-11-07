package dev.caleb.emfattachments.client;

import artifacts.client.item.ArtifactLayers;
import artifacts.client.item.model.ArmsModel;
import com.aetherteam.aether.client.renderer.accessory.GlovesRenderer;
import com.aetherteam.aether.client.renderer.accessory.model.GlovesModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class ArtificeFirstPersonAccessoriesTest {
    @Test void allInstalledArtifactArmMeshesUseTheGunBoneOriginAndRestoreTheirSharedModels() {
        Map<ModelLayerLocation, Supplier<LayerDefinition>> layers = new HashMap<>();
        ArtifactLayers.register(layers::put);
        List<Function<Boolean, ModelLayerLocation>> definitions = List.of(
                ArtifactLayers::claws, ArtifactLayers::glove, ArtifactLayers::pocketPiston,
                ArtifactLayers::goldenHook, ArtifactLayers::onionRing,
                ArtifactLayers::pickaxeHeater, ArtifactLayers::witheredBracelet);
        for (var definition : definitions) for (boolean slim : new boolean[]{false, true}) {
            ArmsModel actual = new ArmsModel(layers.get(definition.apply(slim)).get().bakeRoot());
            ArmsModel expected = new ArmsModel(layers.get(definition.apply(slim)).get().bakeRoot());
            for (boolean left : new boolean[]{false, true}) {
                ModelPart target = left ? actual.leftArm : actual.rightArm;
                ModelPart expectedArm = left ? expected.leftArm : expected.rightArm;
                ModelPart source = part(); // Iron's actual hand uses PartPose.ZERO at its GeckoLib bone.
                // A preceding world draw copies the player's shoulder into this shared model.
                target.setPos(left ? 5 : -5, 2, 0);
                expectedArm.copyFrom(source);
                float[] before = pose(target);
                for (float angle : new float[]{0, 0.8f, -1.7f}) {
                    var expectedVertices = vertices(expectedArm, angle);
                    assertFalse(expectedVertices.isEmpty());
                    assertNotEquals(expectedVertices, vertices(target, angle),
                            "A stale world shoulder must not offset the gun's own arm bone");
                    ArtificeFirstPersonAccessories.withSource(source, false, () ->
                            ArtificeFirstPersonAccessories.renderArm(target, () -> {
                                assertEquals(expectedVertices, vertices(target, angle));
                                // Base and glowing subclass draws share the exact native bone transform.
                                assertEquals(expectedVertices, vertices(target, angle));
                            }));
                    assertArrayEquals(before, pose(target));
                }
            }
        }
    }

    @Test void exceptionalAndNestedDrawsRestorePoseVisibilityAndReleaseTheGunScope() {
        ModelPart first = part(), second = part(), target = part();
        first.setPos(1, 2, 3); first.setRotation(.2f, -.4f, .8f);
        first.xScale = .5f; first.yScale = 1.2f; first.zScale = -.7f;
        second.x = 8;
        target.x = -5; target.y = 2; target.visible = false; target.skipDraw = true;
        float[] original = pose(target), firstPose = transforms(first), secondPose = transforms(second);
        RuntimeException failure = new RuntimeException("intentional accessory failure");
        ArtificeFirstPersonAccessories.withSource(first, false, () -> {
            first.x = 99; // A shared renderer changing the source cannot corrupt the saved bone pose.
            ArtificeFirstPersonAccessories.renderArm(target, () -> {
                assertArrayEquals(firstPose, transforms(target));
                assertFalse(target.visible); assertTrue(target.skipDraw);
            });
            ArtificeFirstPersonAccessories.withSource(null, false, () ->
                    ArtificeFirstPersonAccessories.renderArm(target, () -> assertArrayEquals(original, pose(target))));
            assertSame(failure, assertThrows(RuntimeException.class, () ->
                    ArtificeFirstPersonAccessories.withSource(second, false, () ->
                            ArtificeFirstPersonAccessories.renderArm(target, () -> {
                                assertArrayEquals(secondPose, transforms(target));
                                assertFalse(target.visible); assertTrue(target.skipDraw);
                                throw failure;
                            }))));
            assertArrayEquals(original, pose(target));
            ArtificeFirstPersonAccessories.renderArm(target, () -> assertArrayEquals(firstPose, transforms(target)));
        });
        ArtificeFirstPersonAccessories.renderArm(target, () -> assertArrayEquals(original, pose(target)));
    }

    @Test void leftHandPerspectiveSelectsThePhysicalOppositeArmAndReflectsItsPose() {
        assertEquals(HumanoidArm.RIGHT, ArtificeFirstPersonAccessories.physicalArm(false, false));
        assertEquals(HumanoidArm.LEFT, ArtificeFirstPersonAccessories.physicalArm(true, false));
        assertEquals(HumanoidArm.LEFT, ArtificeFirstPersonAccessories.physicalArm(false, true));
        assertEquals(HumanoidArm.RIGHT, ArtificeFirstPersonAccessories.physicalArm(true, true));
        ModelPart source = part(), target = part();
        source.setPos(2, 3, 4); source.setRotation(.3f, -.5f, .7f);
        source.xScale = .75f; source.yScale = 1.3f; source.zScale = .8f;
        PoseStack expected = new PoseStack();
        expected.scale(-1, 1, 1);
        source.translateAndRotate(expected);
        expected.scale(-1, 1, 1);
        ArtificeFirstPersonAccessories.withSource(source, true, () ->
                ArtificeFirstPersonAccessories.renderArm(target, () -> {
                    PoseStack actual = new PoseStack(); target.translateAndRotate(actual);
                    assertTrue(expected.last().pose().equals(actual.last().pose(), 1e-6f));
                }));
    }

    @Test void installedAetherBaseTrimAndGlintUseTheSuppliedBoneArmAfterTheirNativeReset() throws Exception {
        List<String> operations = new ArrayList<>();
        try (InputStream bytes = GlovesRenderer.class.getResourceAsStream("GlovesRenderer.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!name.equals("renderFirstPerson")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                String descriptor, boolean isInterface) {
                            if (name.equals("setupAnim") || name.equals("copyFrom") || name.equals("render")) {
                                operations.add(name);
                            }
                            assertFalse(name.equals("followBodyRotations"),
                                    "First-person Aether must use the supplied model, not the world model");
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(List.of("setupAnim", "copyFrom", "render", "setupAnim", "copyFrom", "render", "render"), operations);
        for (boolean trim : new boolean[]{false, true}) {
            // Aether uses its wide first-person mesh for both skins, with its own slim offset.
            GlovesModel gloves = new GlovesModel(GlovesModel.createLayer(new CubeDeformation(.25f), false, trim).bakeRoot());
            for (ModelPart glove : List.of(gloves.leftArm, gloves.rightArm)) {
                var atShoulder = vertices(glove, .8f);
                ModelPart nativeBone = part();
                ArtificeFirstPersonAccessories.withSource(nativeBone, false, () ->
                        ArtificeFirstPersonAccessories.renderArm(glove, () -> {
                            assertEquals(72, vertices(glove, .8f).size());
                            assertNotEquals(atShoulder, vertices(glove, .8f));
                            assertEquals(0, glove.x); assertEquals(0, glove.y); assertEquals(0, glove.z);
                        }));
                assertEquals(atShoulder, vertices(glove, .8f));
            }
        }
    }

    @Test void installedGunSkinDrawOccursInsideTheBoneMatrixAndBypassesVanillaHands() throws Exception {
        List<String> operations = new ArrayList<>();
        try (InputStream bytes = getClass().getClassLoader().getResourceAsStream(
                "io/redspace/irons_artifice/client/gun/GunInHandRenderer.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!name.equals("renderFirstPersonHand")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                String descriptor, boolean isInterface) {
                            assertFalse(owner.endsWith("PlayerRenderer"),
                                    "Accessories' vanilla hand hook is absent from Iron's path");
                            if (owner.endsWith("PoseStack") || owner.endsWith("ModelPart")) operations.add(name);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(List.of("pushPose", "scale", "translate", "render", "popPose"), operations);
    }

    @Test void installedSkyjadeOverrideHasThreeNativeDrawsAndDoesNotCopyTheSuppliedArm() throws Exception {
        List<String> draws = new ArrayList<>();
        try (InputStream bytes = getClass().getClassLoader().getResourceAsStream(
                "io/github/razordevs/deep_aether/client/renderer/accessory/SkyjadeGlovesRenderer.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!name.equals("renderFirstPerson")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                String descriptor, boolean isInterface) {
                            if (owner.endsWith("ModelPart") && name.equals("render")) draws.add(descriptor);
                            assertFalse(name.equals("copyFrom"),
                                    "Skyjade's native override explains why the Aether path alone is insufficient");
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        String prefix = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;";
        assertEquals(List.of(prefix + "III)V", prefix + "II)V", prefix + "II)V"), draws);
    }

    private static ModelPart part() { return new ModelPart(List.of(), Map.of()); }
    private static float[] pose(ModelPart part) {
        return new float[]{part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                part.xScale, part.yScale, part.zScale, part.visible ? 1 : 0, part.skipDraw ? 1 : 0};
    }
    private static float[] transforms(ModelPart part) {
        return java.util.Arrays.copyOf(pose(part), 9);
    }
    private static List<Float> vertices(ModelPart part, float angle) {
        PoseStack pose = new PoseStack();
        pose.translate(.2, -.7, -1.1);
        pose.mulPose(Axis.YP.rotation(angle));
        pose.mulPose(Axis.XP.rotation(angle * .7f));
        pose.scale(-1, -1, 1);
        pose.translate(.0625, -.625, 0);
        Vertices vertices = new Vertices();
        part.render(pose, vertices, 15728880, 0);
        return vertices.values;
    }
    private static final class Vertices implements VertexConsumer {
        final List<Float> values = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { values.add(x); values.add(y); values.add(z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
