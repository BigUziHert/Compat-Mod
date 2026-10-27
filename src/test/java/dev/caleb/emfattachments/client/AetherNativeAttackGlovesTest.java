package dev.caleb.emfattachments.client;

import com.aetherteam.aether.client.renderer.accessory.model.GlovesModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class AetherNativeAttackGlovesTest {
    @Test void nativeGloveMeshesFollowIndividualArmVisibilityAndRestoreAtBothScales() {
        for (boolean slim : new boolean[]{false, true}) for (boolean trim : new boolean[]{false, true}) {
            GlovesModel gloves = new GlovesModel(GlovesModel.createLayer(new CubeDeformation(0.5f), slim, trim).bakeRoot());
            gloves.young = false;
            gloves.leftArm.setRotation(-0.6f, -0.4f, -0.7f);
            gloves.rightArm.setRotation(-0.8f, 0.3f, 0.5f);
            for (float scale : new float[]{1, 0.5f}) {
                var left = vertices(gloves, scale, true, false);
                var right = vertices(gloves, scale, false, true);
                assertEquals(24 * 3, left.size());
                assertEquals(24 * 3, right.size());
                assertNotEquals(left, right);
                gloves.leftArm.visible = gloves.rightArm.visible = true;
                AetherNativeAttackGloves.withVisibility(true, false, () -> {
                    AetherNativeAttackGloves.renderVisibleArms(gloves,
                            () -> assertEquals(left, draw(gloves, scale)));
                    assertTrue(gloves.leftArm.visible);
                    assertTrue(gloves.rightArm.visible);
                    // Base, trim, and glint are separate native calls with the same scope.
                    AetherNativeAttackGloves.renderVisibleArms(gloves,
                            () -> assertEquals(left, draw(gloves, scale)));
                    AetherNativeAttackGloves.withVisibility(false, true, () ->
                            AetherNativeAttackGloves.renderVisibleArms(gloves,
                                    () -> assertEquals(right, draw(gloves, scale))));
                    AetherNativeAttackGloves.renderVisibleArms(gloves,
                            () -> assertEquals(left, draw(gloves, scale)));
                });
                AetherNativeAttackGloves.renderVisibleArms(gloves,
                        () -> assertEquals(48 * 3, draw(gloves, scale).size()));
                AetherNativeAttackGloves.withVisibility(false, false, () ->
                        AetherNativeAttackGloves.renderVisibleArms(gloves,
                                () -> assertTrue(draw(gloves, scale).isEmpty())));
            }
        }
    }

    @Test void failingDrawRestoresVisibilityAndReleasesScope() {
        GlovesModel gloves = new GlovesModel(GlovesModel.createLayer(CubeDeformation.NONE, false, false).bakeRoot());
        gloves.leftArm.visible = false;
        gloves.rightArm.visible = true;
        RuntimeException failure = new RuntimeException("intentional draw failure");
        assertSame(failure, assertThrows(RuntimeException.class, () ->
                AetherNativeAttackGloves.withVisibility(true, false, () ->
                        AetherNativeAttackGloves.renderVisibleArms(gloves, () -> { throw failure; }))));
        AetherNativeAttackGloves.renderVisibleArms(gloves, () -> {
            assertFalse(gloves.leftArm.visible);
            assertTrue(gloves.rightArm.visible);
        });
    }

    @Test void installedPlayerAnimatorNativeFirstPersonFilterOnlyRetainsHeldItemLayer() throws Exception {
        List<String> types = new ArrayList<>();
        String resource = "dev/kosmx/playerAnim/mixin/firstPerson/LivingEntityRendererMixin.class";
        try (InputStream bytecode = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(bytecode);
            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!name.startsWith("lambda$filterLayers$")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.INSTANCEOF) types.add(type);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(List.of("net/minecraft/client/renderer/entity/layers/PlayerItemInHandLayer"), types,
                "The confirmed native first-person path omits AccessoriesRenderLayer");
    }

    private static List<Float> vertices(GlovesModel gloves, float scale, boolean left, boolean right) {
        gloves.leftArm.visible = left; gloves.rightArm.visible = right;
        return draw(gloves, scale);
    }

    private static List<Float> draw(GlovesModel gloves, float scale) {
        PoseStack poses = new PoseStack(); poses.scale(scale, scale, scale);
        Vertices vertices = new Vertices();
        gloves.renderToBuffer(poses, vertices, 15728880, 0, -1);
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
