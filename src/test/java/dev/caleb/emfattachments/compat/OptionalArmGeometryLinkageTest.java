package dev.caleb.emfattachments.compat;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptionalArmGeometryLinkageTest {
    @Test void sharedArmScopesLoadWithoutEitherGunOrCombatApis() throws Exception {
        AtomicInteger forbiddenLoads = new AtomicInteger();
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader isolated = new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.bettercombat.") || name.startsWith("dev.kosmx.playerAnim.")
                        || name.startsWith("io.redspace.irons_artifice.")) {
                    forbiddenLoads.incrementAndGet();
                    throw new ClassNotFoundException("Optional API intentionally absent: " + name);
                }
                if (!name.startsWith("dev.caleb.emfattachments.client.FirstPersonArmGeometry")
                        && !name.startsWith("dev.caleb.emfattachments.client.ScopedGeometryMask")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) {
                        try (InputStream bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (bytes == null) throw new ClassNotFoundException(name);
                            byte[] source = bytes.readAllBytes();
                            found = defineClass(name, source, 0, source.length);
                        } catch (IOException failure) {
                            throw new ClassNotFoundException(name, failure);
                        }
                    }
                    if (resolve) resolveClass(found);
                    return found;
                }
            }
        };
        Class<?> geometry = Class.forName("dev.caleb.emfattachments.client.FirstPersonArmGeometry", true, isolated);
        var open = geometry.getDeclaredMethod("openScope", LivingEntity.class, boolean.class);
        open.setAccessible(true);
        try (var body = (AutoCloseable) open.invoke(null, null, true);
             var item = (AutoCloseable) geometry.getMethod("suspendForHeldItems").invoke(null)) {
            assertNotNull(body); assertNotNull(item);
        }
        assertEquals(0, forbiddenLoads.get(), "Shared scope initialization must not resolve optional animation APIs");
    }
}
