package dev.caleb.emfattachments.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class SableReducedTickLinkerTest {
    private static final String DESC = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
    private static final List<String> ORDER = List.of("sable$updateLastSubLevelId",
            "sable$updateSubLevelPosition", "sable$tickInject");

    @Test void callsNativeHandlersWithTheirActualNamesInNativeTickOrder() {
        ClassNode entity = fixture(ORDER);
        assertTrue(SableReducedTickLinker.link(entity));
        MethodNode bridge = method(entity, "emfattachments$maintainSableTick");
        assertEquals(ORDER.stream().map(n -> "handler$generated$" + n).toList(),
                calls(bridge).stream().map(c -> c.name).toList());
        assertEquals(List.of(Opcodes.INVOKESPECIAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKEVIRTUAL),
                calls(bridge).stream().map(MethodInsnNode::getOpcode).toList());
        assertEquals(Opcodes.ICONST_1, method(entity, "emfattachments$hasSableReducedTickBridge")
                .instructions.getFirst().getOpcode());
        assertFalse(calls(bridge).stream().anyMatch(c -> c.name.equals("tick") || c.name.equals("aiStep")),
                "Maintenance must not duplicate a full tick or remote interpolation");
    }

    @Test void preservesObservedOrderInsteadOfAssumingMixinRegistrationOrder() {
        List<String> order = List.of(ORDER.get(2), ORDER.get(0), ORDER.get(1));
        ClassNode entity = fixture(order);
        assertTrue(SableReducedTickLinker.link(entity));
        assertEquals(order.stream().map(n -> "handler$generated$" + n).toList(),
                calls(method(entity, "emfattachments$maintainSableTick")).stream().map(c -> c.name).toList());
    }

    @Test void missingAmbiguousOrForeignHandlersLeaveFullTickFallbackIntact() {
        ClassNode missing = fixture(ORDER);
        missing.methods.removeIf(m -> m.name.endsWith(ORDER.get(1)));
        assertNotLinked(missing);

        ClassNode ambiguous = fixture(ORDER);
        ambiguous.methods.add(callback("handler$duplicate$" + ORDER.get(1), ORDER.get(1)));
        assertNotLinked(ambiguous);

        ClassNode foreign = fixture(ORDER);
        method(foreign, "handler$generated$" + ORDER.get(1)).visibleAnnotations.getFirst()
                .values.set(1, "another.mod.EntityMixin");
        assertNotLinked(foreign);
    }

    @Test void duplicateOrMissingNativeTickCallsCannotActivateMaintenance() {
        ClassNode duplicate = fixture(ORDER);
        method(duplicate, "tick").instructions.insert(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                duplicate.name, "handler$generated$" + ORDER.get(1), DESC, false));
        assertNotLinked(duplicate);

        ClassNode missing = fixture(ORDER);
        MethodNode tick = method(missing, "tick");
        tick.instructions.remove(calls(tick).getLast());
        assertNotLinked(missing);
    }

    @Test void unverifiedVersionsAndMissingModsKeepOnlyExistingFullTickBridge() {
        String reduced = "dev.caleb.emfattachments.mixin.SablePlayerReducedTickMixin";
        String access = "dev.caleb.emfattachments.mixin.SableReducedTickAccessMixin";
        for (String mixin : List.of(reduced, access)) {
            assertTrue(plugin("2.0.6", "1.12.0").shouldApplyMixin("unused", mixin));
            assertTrue(plugin("2.0.6", "1.12.1").shouldApplyMixin("unused", mixin));
            assertFalse(plugin("2.0.5", "1.12.0").shouldApplyMixin("unused", mixin));
            assertFalse(plugin("2.0.7", "1.12.0").shouldApplyMixin("unused", mixin));
            assertFalse(plugin("2.0.6", "1.11.3").shouldApplyMixin("unused", mixin));
            assertFalse(plugin("2.0.6", "1.12.2").shouldApplyMixin("unused", mixin));
            assertFalse(plugin("2.0.6", "1.13.0").shouldApplyMixin("unused", mixin));
            assertFalse(new OptionalMixinPlugin(mod -> !mod.equals("sable"), (mod, min) -> false)
                    .shouldApplyMixin("unused", mixin));
        }
        assertTrue(plugin("2.0.6", "1.11.3").shouldApplyMixin("unused",
                "dev.caleb.emfattachments.mixin.SablePlayerEntityCullingMixin"));
    }

    private static OptionalMixinPlugin plugin(String sable, String culling) {
        Map<String, String> versions = Map.of("sable", sable, "entityculling", culling);
        return new OptionalMixinPlugin(versions::containsKey, (mod, min) ->
                new DefaultArtifactVersion(versions.get(mod)).compareTo(new DefaultArtifactVersion(min)) >= 0);
    }

    private static void assertNotLinked(ClassNode entity) {
        assertFalse(SableReducedTickLinker.link(entity));
        assertEquals(Opcodes.ICONST_0, method(entity, "emfattachments$hasSableReducedTickBridge")
                .instructions.getFirst().getOpcode());
        assertTrue(calls(method(entity, "emfattachments$maintainSableTick")).isEmpty());
    }

    private static ClassNode fixture(List<String> order) {
        ClassNode node = new ClassNode();
        node.name = "net/minecraft/world/entity/Entity";
        MethodNode tick = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        tick.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "baseTick", "()V", false));
        for (String name : order) {
            MethodNode callback = callback("handler$generated$" + name, name);
            node.methods.add(callback);
            tick.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            tick.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            tick.instructions.add(new MethodInsnNode(name.equals("sable$tickInject")
                    ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKESPECIAL, node.name, callback.name, DESC, false));
        }
        tick.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(tick);
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, "emfattachments$maintainSableTick", "()V", null, null);
        bridge.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(bridge);
        MethodNode ready = new MethodNode(Opcodes.ACC_PUBLIC, "emfattachments$hasSableReducedTickBridge", "()Z", null, null);
        ready.instructions.add(new InsnNode(Opcodes.ICONST_0));
        ready.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(ready);
        return node;
    }

    private static MethodNode callback(String generatedName, String sourceName) {
        MethodNode callback = new MethodNode(Opcodes.ACC_PRIVATE, generatedName, DESC, null, null);
        AnnotationNode merged = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        merged.values = new ArrayList<>(List.of("mixin", SableReducedTickLinker.CALLBACKS.get(sourceName)));
        callback.visibleAnnotations = new ArrayList<>(List.of(merged));
        return callback;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) calls.add(call);
        return calls;
    }
}
