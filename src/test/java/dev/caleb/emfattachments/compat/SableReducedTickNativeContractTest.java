package dev.caleb.emfattachments.compat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class SableReducedTickNativeContractTest {
    @Test void reducedTickKeepsBookkeepingBeforeExactlyOneNativeAiStep() throws Exception {
        MethodNode basic = method("dev/tr7zw/entityculling/mixin/ClientWorldMixin", "basicTick");
        int oldPosition = -1, count = -1, aiStep = -1, aiSteps = 0;
        for (int i = 0; i < basic.instructions.size(); i++) {
            var instruction = basic.instructions.get(i);
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("setOldPosAndRot")) oldPosition = i;
                if (call.owner.equals("net/minecraft/world/entity/LivingEntity") && call.name.equals("aiStep")) {
                    aiStep = i;
                    aiSteps++;
                    assertEquals(Opcodes.INVOKEVIRTUAL, call.getOpcode(), "RemotePlayer's interpolation override must run");
                }
                assertFalse(call.name.equals("tick") || call.name.equals("baseTick"));
            }
            if (instruction instanceof FieldInsnNode field && field.name.equals("tickCount")
                    && field.getOpcode() == Opcodes.PUTFIELD) count = i;
        }
        assertEquals(1, aiSteps);
        assertTrue(oldPosition >= 0 && oldPosition < count && count < aiStep);
    }

    @Test void invokedSableHandlersUseNoCallbackArgumentAndKeepProjectionAndCleanupNative() throws Exception {
        for (var callback : SableReducedTickLinker.CALLBACKS.entrySet()) {
            MethodNode method = method(callback.getValue().replace('.', '/'), callback.getKey());
            assertEquals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", method.desc);
            for (var instruction : method.instructions) {
                assertFalse(instruction instanceof VarInsnNode variable && variable.var == 1
                        && variable.getOpcode() == Opcodes.ALOAD,
                        "The native invocation passes null CallbackInfo: " + callback.getKey());
            }
        }
        MethodNode projection = method("dev/ryanhcode/sable/mixin/entity/entities_stick_sublevels/EntityMixin",
                "sable$updateSubLevelPosition");
        List<String> calls = calls(projection);
        assertTrue(calls.containsAll(List.of("getContaining", "logicalPose", "transformPosition", "setPos",
                "sable$setTrackingSubLevel")));
        MethodNode cleanup = method("dev/ryanhcode/sable/mixin/entity/entity_sublevel_collision/EntityMixin", "sable$tickInject");
        assertTrue(calls(cleanup).contains("isRemoved"));
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) calls.add(call.name);
        return calls;
    }

    private static MethodNode method(String owner, String name) throws Exception {
        try (InputStream bytes = SableReducedTickNativeContractTest.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            assertNotNull(bytes, owner);
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
        }
    }
}
