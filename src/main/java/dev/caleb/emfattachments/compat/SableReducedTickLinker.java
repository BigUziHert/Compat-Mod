package dev.caleb.emfattachments.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Links native callbacks by provenance and actual tick order, never by generated handler IDs. */
final class SableReducedTickLinker {
    private static final String CALLBACK = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
    static final Map<String, String> CALLBACKS = Map.of(
            "sable$updateLastSubLevelId", "dev.ryanhcode.sable.mixin.death_message.EntityMixin",
            "sable$updateSubLevelPosition", "dev.ryanhcode.sable.mixin.entity.entities_stick_sublevels.EntityMixin",
            "sable$tickInject", "dev.ryanhcode.sable.mixin.entity.entity_sublevel_collision.EntityMixin");

    private SableReducedTickLinker() {}

    static boolean supported(BiPredicate<String, String> atLeast) {
        // These exact release families were inspected. Unknown versions retain full ticks.
        return atLeast.test("sable", "2.0.6") && !atLeast.test("sable", "2.0.7")
                && atLeast.test("entityculling", "1.12.0") && !atLeast.test("entityculling", "1.12.2");
    }

    static boolean link(ClassNode entity) {
        MethodNode bridge = method(entity, "emfattachments$maintainSableTick", "()V");
        MethodNode ready = method(entity, "emfattachments$hasSableReducedTickBridge", "()Z");
        MethodNode tick = method(entity, "tick", "()V");
        if (bridge == null || ready == null || tick == null) return false;

        Map<String, MethodNode> nativeMethods = new LinkedHashMap<>();
        for (var expected : CALLBACKS.entrySet()) {
            List<MethodNode> matches = entity.methods.stream()
                    .filter(m -> CALLBACK.equals(m.desc) && (m.access & Opcodes.ACC_STATIC) == 0)
                    .filter(m -> m.name.equals(expected.getKey()) || m.name.endsWith("$" + expected.getKey()))
                    .filter(m -> expected.getValue().equals(mergedOwner(m))).toList();
            if (matches.size() != 1) return false;
            nativeMethods.put(matches.getFirst().name, matches.getFirst());
        }
        List<MethodInsnNode> orderedCalls = new ArrayList<>();
        boolean baseTickSeen = false;
        for (var instruction : tick.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(entity.name) && call.name.equals("baseTick") && call.desc.equals("()V")) {
                baseTickSeen = true;
            }
            if (call.owner.equals(entity.name) && nativeMethods.containsKey(call.name)
                    && call.desc.equals(CALLBACK)) {
                if (!baseTickSeen) return false;
                orderedCalls.add(call);
            }
        }
        if (orderedCalls.size() != CALLBACKS.size()
                || orderedCalls.stream().map(c -> c.name).distinct().count() != CALLBACKS.size()) return false;

        InsnList body = new InsnList();
        for (MethodInsnNode call : orderedCalls) {
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            // The verified native tick passes null and none of these handlers reads CallbackInfo.
            body.add(new InsnNode(Opcodes.ACONST_NULL));
            body.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
        }
        body.add(new InsnNode(Opcodes.RETURN));
        replaceBody(bridge, body, 2);
        InsnList enabled = new InsnList();
        enabled.add(new InsnNode(Opcodes.ICONST_1));
        enabled.add(new InsnNode(Opcodes.IRETURN));
        replaceBody(ready, enabled, 1);
        return true;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                .findFirst().orElse(null);
    }

    private static String mergedOwner(MethodNode method) {
        if (method.visibleAnnotations == null) return null;
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i).equals("mixin")) return (String) annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static void replaceBody(MethodNode method, InsnList body, int maxStack) {
        method.instructions = body;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.maxStack = maxStack;
        method.maxLocals = 1;
    }
}
