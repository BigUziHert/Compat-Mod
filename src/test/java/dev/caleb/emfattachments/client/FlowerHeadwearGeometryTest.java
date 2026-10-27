package dev.caleb.emfattachments.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.telepathicgrunt.the_bumblezone.client.rendering.armor.FlowerHeadwearModel;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowerHeadwearGeometryTest {
    @Test
    void nativeFlowerHasSixPetalsAndRendererUsesTheOverriddenGenericHook() throws Exception {
        // The installed Bumblezone model contains six petal cubes, not the
        // single 24-vertex vanilla fallback head seen in the failed visual run.
        var head = FlowerHeadwearModel.createBodyLayer().bakeRoot().getChild("head");
        Counter vertices = new Counter();
        head.render(new PoseStack(), vertices, 15728880, 0, -1);
        assertEquals(144, vertices.count);

        // Bumblezone's IClientItemExtensions overrides getGenericArmorModel
        // only. Check the actual production render call, not a copied helper.
        List<String> armorHooks = new ArrayList<>();
        try (InputStream compiled = FlowerHeadwearRenderer.class.getResourceAsStream("FlowerHeadwearRenderer.class")) {
            new ClassReader(compiled).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!name.equals("render")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                String descriptor, boolean isInterface) {
                            if (owner.endsWith("/IClientItemExtensions")) armorHooks.add(method);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(armorHooks.contains("getGenericArmorModel"));
        assertFalse(armorHooks.contains("getHumanoidArmorModel"));
    }

    private static final class Counter implements VertexConsumer {
        int count;
        @Override public VertexConsumer addVertex(float x, float y, float z) { count++; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
