package dev.caleb.emfattachments.client;

import com.aetherteam.aether.client.renderer.accessory.model.GlovesModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class RagdollAetherArmsTest {
    @Test void pairedBaseAndTrimMeshesDrawExactlyThePhysicalArmWithoutMutatingTheSharedModel() {
        for (boolean slim : new boolean[]{false, true}) for (boolean trim : new boolean[]{false, true}) {
            GlovesModel model = maskedModel(slim, trim);
            model.young = false;
            model.leftArm.setRotation(.4f, -.3f, .7f);
            model.rightArm.setRotation(-.8f, .2f, -.1f);
            for (HumanoidArm physical : HumanoidArm.values()) {
                ModelPart own = physical == HumanoidArm.LEFT ? model.leftArm : model.rightArm;
                ModelPart other = physical == HumanoidArm.LEFT ? model.rightArm : model.leftArm;
                // Sable's native helper moves the opposite limb rather than hiding its geometry.
                float oldY = other.y;
                other.y += 10000;
                List<Float> expected = drawPart(own);
                assertEquals(24 * 3, expected.size());
                assertEquals(48 * 3, draw(model).size());
                try (var ignored = RagdollAccessoryArmScope.open(physical)) {
                    for (int pass = 0; pass < 3; pass++) {
                        assertEquals(expected, draw(model), "Base, trim and glint must omit the other physical arm");
                        assertTrue(own.visible);
                        assertTrue(other.visible);
                        assertEquals(oldY + 10000, other.y);
                    }
                    own.visible = false;
                    assertTrue(draw(model).isEmpty(), "Do not revive a hidden selected glove");
                    own.visible = true;
                    own.skipDraw = true;
                    assertTrue(draw(model).isEmpty(), "Keep native skipDraw policy");
                    own.skipDraw = false;
                }
                other.y = oldY;
                assertEquals(48 * 3, draw(model).size(), "Ordinary rendering after recovery keeps both gloves");
            }
        }
    }

    @Test void nestedNonArmAndFailingCallsNeverLeakThePhysicalArmMask() {
        GlovesModel model = maskedModel(false, false);
        model.young = false;
        var both = draw(model);
        RuntimeException expected = new RuntimeException("native glove draw failure");
        try (var outer = RagdollAccessoryArmScope.open(HumanoidArm.RIGHT)) {
            var right = draw(model);
            try (var nonArm = RagdollAccessoryArmScope.open(null)) {
                assertEquals(both, draw(model));
            }
            assertSame(expected, assertThrows(RuntimeException.class, () -> {
                try (var inner = RagdollAccessoryArmScope.open(HumanoidArm.LEFT)) {
                    assertNotEquals(right, draw(model));
                    throw expected;
                }
            }));
            assertEquals(right, draw(model));
        }
        assertEquals(both, draw(model));
    }

    @Test void bothInstalledRenderersUseThePairedGloveModelForEveryWorldPass() throws Exception {
        for (String owner : List.of("com/aetherteam/aether/client/renderer/accessory/GlovesRenderer",
                "io/github/razordevs/deep_aether/client/renderer/accessory/SkyjadeGlovesRenderer")) {
            List<String> draws = new ArrayList<>();
            try (InputStream bytes = getClass().getClassLoader().getResourceAsStream(owner + ".class")) {
                assertNotNull(bytes);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String desc,
                            String signature, String[] exceptions) {
                        if (!name.equals("render")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String target, String name,
                                    String descriptor, boolean isInterface) {
                                if (name.equals("renderToBuffer")) draws.add(target);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            assertEquals(List.of("com/aetherteam/aether/client/renderer/accessory/model/GlovesModel",
                    "com/aetherteam/aether/client/renderer/accessory/model/GlovesModel",
                    "com/aetherteam/aether/client/renderer/accessory/model/GlovesModel"), draws);
        }
    }

    private static GlovesModel maskedModel(boolean slim, boolean trim) {
        return new GlovesModel(GlovesModel.createLayer(new CubeDeformation(.5f), slim, trim).bakeRoot()) {
            @Override protected Iterable<ModelPart> bodyParts() {
                return RagdollAccessoryArmScope.visibleGloveArms(this, super.bodyParts());
            }
        };
    }

    private static List<Float> draw(GlovesModel model) {
        Vertices vertices = new Vertices();
        model.renderToBuffer(new PoseStack(), vertices, 15728880, 0, -1);
        return vertices.values;
    }

    private static List<Float> drawPart(ModelPart part) {
        Vertices vertices = new Vertices();
        part.render(new PoseStack(), vertices, 15728880, 0, -1);
        return vertices.values;
    }

    private static final class Vertices implements VertexConsumer {
        private final List<Float> values = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { values.add(x); values.add(y); values.add(z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
