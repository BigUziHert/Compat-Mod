package dev.caleb.emfattachments.compat;

import java.io.IOException;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

final class MixinDiscoveryFixture {
    private final Class<?> pluginClass = pluginWithFixtureMetadata();

    IMixinConfigPlugin selector(Predicate<String> installed, BiPredicate<String, String> atLeast,
                                 Map<String, DefaultArtifactVersion> exactVersions) {
        try {
            pluginClass.getField("fixtureVersions").set(null, exactVersions);
            var constructor = pluginClass.getDeclaredConstructor(Predicate.class, BiPredicate.class);
            constructor.setAccessible(true);
            return (IMixinConfigPlugin) constructor.newInstance(installed, atLeast);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // Supply discovered versions in an isolated copy. This exercises the real selector and exact-version
    // comparison without initializing FML, altering its process-wide discovery state, or changing product code.
    static Class<?> pluginWithFixtureMetadata() {
        String owner = "dev/caleb/emfattachments/compat/OptionalMixinPlugin";
        ClassNode plugin = new ClassNode();
        try (var stream = MixinDiscoveryFixture.class.getResourceAsStream("/" + owner + ".class")) {
            if (stream == null) throw new AssertionError(owner);
            new ClassReader(stream).accept(plugin, ClassReader.SKIP_DEBUG);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        plugin.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "fixtureVersions", "Ljava/util/Map;", null, null));
        var lookup = plugin.methods.stream().filter(m -> m.name.equals("discoveredVersion")).findFirst().orElseThrow();
        lookup.instructions.clear();
        lookup.tryCatchBlocks.clear();
        lookup.visitFieldInsn(Opcodes.GETSTATIC, owner, "fixtureVersions", "Ljava/util/Map;");
        lookup.visitVarInsn(Opcodes.ALOAD, 0);
        lookup.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        lookup.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "ofNullable",
                "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        lookup.visitInsn(Opcodes.ARETURN);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        plugin.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(MixinDiscoveryFixture.class.getClassLoader()) {
            Class<?> definePlugin() {
                // The selector and its package-private Sable gate must share a runtime package.
                String linker = "dev.caleb.emfattachments.compat.SableReducedTickLinker";
                try (var input = getResourceAsStream(linker.replace('.', '/') + ".class")) {
                    if (input == null) throw new AssertionError(linker);
                    byte[] linked = input.readAllBytes();
                    defineClass(linker, linked, 0, linked.length);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
                return defineClass(owner.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.definePlugin();
    }
}
