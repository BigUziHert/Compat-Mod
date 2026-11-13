package dev.caleb.emfattachments.compat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production optional-API guard without initializing a Minecraft client. */
public class EmfCrouchCompatibilityTest {
    private static final String HELPER = "dev.caleb.emfattachments.compat.EmfCrouchCompatibility";
    private static final String NORMALIZER = "strm.emfcompat.core.CrouchNormalizer";

    @Test void onlyVerifiedCoreVersionsAdvertiseTheNormalizerContract() {
        assertFalse(EmfCrouchCompatibility.supportsVersion(null));
        for (String old : new String[]{"2.2.0", "2.3.1"}) {
            assertFalse(EmfCrouchCompatibility.supportsVersion(new DefaultArtifactVersion(old)), old);
        }
        for (String supported : new String[]{"2.3.2", "2.4.0", "2.10.0", "3.0.0"}) {
            assertTrue(EmfCrouchCompatibility.supportsVersion(new DefaultArtifactVersion(supported)), supported);
        }
    }

    @Test void absentOrOlderCoreNeverLinksItsOptionalApi() throws Exception {
        for (String version : new String[]{null, "2.3.1"}) {
            var fixture = new IsolatedHelper(version);
            for (boolean enabled : new boolean[]{true, false}) {
                ConfigBoundary.compatEnabled = ConfigBoundary.crouchFix = enabled;
                assertFalse(fixture.owns(), "Fallback must remain available for " + version);
            }
            assertEquals(0, fixture.coreLoads.get(), "Unsupported Core must never be resolved");
            assertEquals(0, fixture.configReads.get(), "Unsupported Core must never read upstream settings");
        }
    }

    @Test void actualUpstreamGlobalAndCrouchSettingsAreReadLiveWithoutReloadingTheHelper() throws Exception {
        for (String version : new String[]{"2.3.2", "2.4.0"}) {
            var fixture = new IsolatedHelper(version);
            ConfigBoundary.compatEnabled = ConfigBoundary.crouchFix = true;
            assertTrue(fixture.owns());
            ConfigBoundary.compatEnabled = false;
            assertFalse(fixture.owns(), "The global upstream switch restores our fallback");
            ConfigBoundary.compatEnabled = true;
            ConfigBoundary.crouchFix = false;
            assertFalse(fixture.owns(), "Disabling only upstream crouch correction restores our fallback");
            ConfigBoundary.crouchFix = true;
            assertTrue(fixture.owns(), "Re-enabling upstream correction hands height back immediately");
            assertEquals(1, fixture.coreLoads.get());
            assertEquals(3, fixture.configReads.get(), "The installed global switch short-circuits its config lookup");
        }
    }

    private static final class IsolatedHelper extends ClassLoader {
        final AtomicInteger coreLoads = new AtomicInteger();
        final AtomicInteger configReads = new AtomicInteger();
        final boolean supported;
        final Method owns;

        IsolatedHelper(String version) throws Exception {
            super(EmfCrouchCompatibilityTest.class.getClassLoader());
            supported = EmfCrouchCompatibility.supportsVersion(version == null ? null : new DefaultArtifactVersion(version));
            ConfigBoundary.reads = configReads;
            owns = loadClass(HELPER).getMethod("ownsCrouchHeight");
        }

        boolean owns() throws Exception { return (boolean) owns.invoke(null); }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(HELPER) && !name.startsWith(HELPER + "$") && !name.startsWith("strm.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> result = findLoadedClass(name);
                if (result == null) {
                    try {
                        byte[] bytes;
                        if (name.startsWith("strm.")) {
                            coreLoads.incrementAndGet();
                            if (!supported || !name.equals(NORMALIZER)) {
                                throw new ClassNotFoundException("Optional Core API intentionally unavailable: " + name);
                            }
                            bytes = nativeEnabledPredicate();
                        } else if (name.equals(HELPER + "$Availability")) {
                            ClassNode availability = new ClassNode();
                            availability.version = Opcodes.V21;
                            availability.access = Opcodes.ACC_PUBLIC;
                            availability.name = name.replace('.', '/');
                            availability.superName = "java/lang/Object";
                            availability.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                                    "SUPPORTED", "Z", null, supported ? 1 : 0));
                            ClassWriter writer = new ClassWriter(0);
                            availability.accept(writer);
                            bytes = writer.toByteArray();
                        } else bytes = resource(name.replace('.', '/') + ".class");
                        result = defineClass(name, bytes, 0, bytes.length);
                    } catch (ClassNotFoundException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new ClassNotFoundException(name, failure);
                    }
                }
                if (resolve) resolveClass(result);
                return result;
            }
        }
    }

    /** Keeps every installed isEnabled instruction; replaces only its external configuration inputs. */
    private static byte[] nativeEnabledPredicate() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(resource(NORMALIZER.replace('.', '/') + ".class")).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        node.methods.removeIf(method -> !method.name.equals("isEnabled"));
        assertEquals(1, node.methods.size());
        node.fields.clear();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new Remapper() {
            @Override public String map(String name) {
                return name.equals("strm/emfcompat/core/EMFCompatCore") || name.equals("strm/emfcompat/core/EMFCompatConfig")
                        ? ConfigBoundary.class.getName().replace('.', '/') : name;
            }
        }));
        return writer.toByteArray();
    }

    public static final class ConfigBoundary {
        static boolean compatEnabled, crouchFix;
        static AtomicInteger reads;
        public static boolean isCompatEnabled() { return compatEnabled; }
        public static boolean getBoolean(String key, boolean defaultValue) {
            assertEquals("core.crouchFix", key);
            assertTrue(defaultValue);
            reads.incrementAndGet();
            return crouchFix;
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream bytes = EmfCrouchCompatibilityTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(bytes, name);
            return bytes.readAllBytes();
        }
    }
}
