package dev.caleb.emfattachments.compat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises optional Core linkage and live settings without a Minecraft client. */
public class EmfSableCompatibilityTest {
    private static final String HELPER = "dev.caleb.emfattachments.compat.EmfSableCompatibility";
    private static final String CORE = "strm.emfcompat.core.EMFCompatCore";
    private static final String CONFIG = "strm.emfcompat.core.EMFCompatConfig";

    @Test void heightCapabilityStartsAtCore240() {
        assertFalse(EmfSableCompatibility.supportsVersion(null));
        for (String old : new String[]{"2.2.0", "2.3.2", "2.3.99", "2.4.0-beta"}) {
            assertFalse(EmfSableCompatibility.supportsVersion(new DefaultArtifactVersion(old)), old);
        }
        for (String supported : new String[]{"2.4.0", "2.4.1", "2.10.0", "3.0.0"}) {
            assertTrue(EmfSableCompatibility.supportsVersion(new DefaultArtifactVersion(supported)), supported);
        }
    }

    @Test void absentOrOlderCoreNeverLinksOptionalClasses() throws Exception {
        for (String version : new String[]{null, "2.3.2", "2.4.0-beta"}) {
            var fixture = new IsolatedHelper(version);
            for (boolean enabled : new boolean[]{true, false}) {
                ConfigBoundary.compatEnabled = ConfigBoundary.steadyHeight = enabled;
                assertFalse(fixture.hasSteadyHeight());
                assertFalse(fixture.enabled());
            }
            assertEquals(0, fixture.coreLoads.get(), "Absent/older Core APIs must never be resolved");
            assertEquals(0, fixture.globalReads.get());
            assertEquals(0, fixture.configReads.get());
        }
    }

    @Test void capabilityDiscoveryDoesNotLinkCoreEvenWhenSupported() throws Exception {
        var fixture = new IsolatedHelper("2.4.0");
        assertTrue(fixture.hasSteadyHeight());
        assertTrue(fixture.hasSteadyHeight());
        assertEquals(0, fixture.coreLoads.get(), "Metadata discovery alone must not initialize optional Core APIs");
        assertEquals(0, fixture.globalReads.get());
        assertEquals(0, fixture.configReads.get());
    }

    @Test void liveSwitchesDoNotChangeTheInstalledCapabilityOrRequireReloading() throws Exception {
        for (String version : new String[]{"2.4.0", "2.10.0"}) {
            var fixture = new IsolatedHelper(version);
            ConfigBoundary.compatEnabled = ConfigBoundary.steadyHeight = true;
            assertTrue(fixture.enabled());
            ConfigBoundary.compatEnabled = false;
            assertFalse(fixture.enabled());
            assertTrue(fixture.hasSteadyHeight(), "Global off must not select the legacy deck correction");
            ConfigBoundary.compatEnabled = true;
            ConfigBoundary.steadyHeight = false;
            assertFalse(fixture.enabled());
            assertTrue(fixture.hasSteadyHeight(), "Feature off must not select the legacy deck correction");
            ConfigBoundary.steadyHeight = true;
            assertTrue(fixture.enabled());
            assertEquals(2, fixture.coreLoads.get(), "The two optional API classes load only once each");
            assertEquals(4, fixture.globalReads.get());
            assertEquals(3, fixture.configReads.get(), "Global off short-circuits the feature setting");
        }
    }

    private static final class IsolatedHelper extends ClassLoader {
        final AtomicInteger coreLoads = new AtomicInteger();
        final AtomicInteger globalReads = new AtomicInteger();
        final AtomicInteger configReads = new AtomicInteger();
        final boolean supported;
        final Method hasSteadyHeight, enabled;

        IsolatedHelper(String version) throws Exception {
            super(EmfSableCompatibilityTest.class.getClassLoader());
            supported = EmfSableCompatibility.supportsVersion(version == null ? null : new DefaultArtifactVersion(version));
            ConfigBoundary.globalReads = globalReads;
            ConfigBoundary.configReads = configReads;
            Class<?> helper = loadClass(HELPER);
            hasSteadyHeight = helper.getMethod("hasSteadyHeight");
            enabled = helper.getMethod("steadyHeightEnabled");
        }

        boolean hasSteadyHeight() throws Exception { return (boolean) hasSteadyHeight.invoke(null); }
        boolean enabled() throws Exception { return (boolean) enabled.invoke(null); }

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
                            if (!supported || !(name.equals(CORE) || name.equals(CONFIG))) {
                                throw new ClassNotFoundException("Optional Core API intentionally unavailable: " + name);
                            }
                            bytes = configurationBoundary(name);
                        } else if (name.equals(HELPER + "$Availability")) {
                            // Replace only immutable mod discovery; both production
                            // public methods and the lazy Core branch execute unchanged.
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

    private static byte[] configurationBoundary(String name) {
        boolean config = name.equals(CONFIG);
        String method = config ? "getBoolean" : "isCompatEnabled";
        String descriptor = config ? "(Ljava/lang/String;Z)Z" : "()Z";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor body = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method, descriptor, null, null);
        body.visitCode();
        if (config) {
            body.visitVarInsn(Opcodes.ALOAD, 0);
            body.visitVarInsn(Opcodes.ILOAD, 1);
        }
        body.visitMethodInsn(Opcodes.INVOKESTATIC, ConfigBoundary.class.getName().replace('.', '/'), method, descriptor, false);
        body.visitInsn(Opcodes.IRETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static final class ConfigBoundary {
        static boolean compatEnabled, steadyHeight;
        static AtomicInteger globalReads, configReads;
        public static boolean isCompatEnabled() {
            globalReads.incrementAndGet();
            return compatEnabled;
        }
        public static boolean getBoolean(String key, boolean defaultValue) {
            assertEquals("core.sableSteadyHeight", key);
            assertTrue(defaultValue);
            configReads.incrementAndGet();
            return steadyHeight;
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream bytes = EmfSableCompatibilityTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(bytes, name);
            return bytes.readAllBytes();
        }
    }
}
