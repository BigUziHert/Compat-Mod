package dev.caleb.emfattachments.compat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Checks the actual upstream bytecode: compiling alone cannot validate a string injection target. */
class EntityCullingTickContractTest {
    private static final String UPSTREAM = "dev/tr7zw/entityculling/mixin/ClientWorldMixin";
    private static final String BRIDGE = "dev/caleb/emfattachments/mixin/SablePlayerEntityCullingMixin";

    @Test void installedTickCallbackHasExactlyOneMatchingRequiredInvocation() throws Exception {
        assertTickContract(classNode(UPSTREAM));
    }

    @Test void previous1113TickCallbackStillHasExactlyOneMatchingRequiredInvocation() throws Exception {
        Path previous = Path.of(System.getProperty("entityCullingLegacyJar",
                ".local/client/mods/entityculling-neoforge-1.11.3-mc1.21.1.jar"));
        assumeTrue(Files.isRegularFile(previous), "Previous Entity Culling jar is an optional local fixture");
        try (ZipFile jar = new ZipFile(previous.toFile())) {
            var entry = jar.getEntry(UPSTREAM + ".class");
            assertNotNull(entry);
            try (InputStream bytes = jar.getInputStream(entry)) {
                assertTickContract(readClass(bytes));
            }
        }
    }

    private static void assertTickContract(ClassNode upstream) throws Exception {
        MethodNode bridge = classNode(BRIDGE).methods.stream()
                .filter(m -> m.name.equals("emfattachments$keepSubLevelPlayersTicking"))
                .findFirst().orElseThrow();
        AnnotationNode handler = annotation(bridge, "Lcom/bawnorton/mixinsquared/TargetHandler;");
        assertEquals(UPSTREAM.replace('/', '.'), value(handler, "mixin"));
        assertEquals("tickEntity", value(handler, "name"), "Keep the exception scoped to full entity ticks");
        MethodNode tick = upstream.methods.stream()
                .filter(m -> m.name.equals(value(handler, "name"))).findFirst().orElseThrow();
        assertEquals("(Lnet/minecraft/world/entity/Entity;"
                + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", tick.desc);

        AnnotationNode injection = annotation(bridge,
                "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
        assertEquals(1, value(injection, "require"));
        assertEquals(1, value(injection, "expect"));
        assertEquals(1, value(injection, "allow"));
        List<String> targets = new ArrayList<>();
        for (Object point : (List<?>) value(injection, "at")) {
            AnnotationNode at = (AnnotationNode) point;
            assertEquals("INVOKE", value(at, "value"));
            assertEquals(false, value(at, "remap"));
            targets.add((String) value(at, "target"));
        }
        int matches = 0;
        for (var instruction : tick.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && targets.contains("L" + call.owner + ";" + call.name + call.desc)) {
                assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
                matches++;
            }
        }
        assertEquals(1, matches, "The strict tick-only injection must match exactly once in the upstream callback");
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        fail("Missing annotation value " + key);
        return null;
    }

    private static ClassNode classNode(String name) throws Exception {
        try (InputStream bytes = EntityCullingTickContractTest.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
            assertNotNull(bytes, name);
            return readClass(bytes);
        }
    }

    private static ClassNode readClass(InputStream bytes) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
}
