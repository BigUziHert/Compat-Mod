package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.compat.CollisionAxes;
import java.io.IOException;
import java.lang.reflect.Method;
import net.bettercombat.client.collision.OrientedBoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.junit.jupiter.api.Assertions.*;

class BetterCombatCollisionTest {
    private static final String NATIVE = "net.bettercombat.client.collision.OrientedBoundingBox";

    private static CallbackInfoReturnable<Boolean> productionHook(Vec3 axis) throws Exception {
        Method hook = BetterCombatZeroAxisMixin.class.getDeclaredMethod("emfattachments$ignoreSignedZeroAxis",
                Vec3[].class, Vec3[].class, Vec3.class, CallbackInfoReturnable.class);
        hook.setAccessible(true);
        var result = new CallbackInfoReturnable<Boolean>("Separated", true);
        hook.invoke(null, null, null, axis, result);
        return result;
    }

    @Test void actualHookIgnoresEverySignedZeroCombinationButNoTinyOrNonfiniteAxis() throws Exception {
        for (double x : new double[]{0.0, -0.0})
            for (double y : new double[]{0.0, -0.0})
                for (double z : new double[]{0.0, -0.0}) {
                    var result = productionHook(new Vec3(x, y, z));
                    assertTrue(result.isCancelled());
                    assertFalse(result.getReturnValue());
                }
        for (double value : new double[]{Double.MIN_VALUE, -Double.MIN_VALUE, 1.0e-200,
                -1.0e-200, 1, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (Vec3 axis : new Vec3[]{new Vec3(value, 0, -0.0), new Vec3(-0.0, value, 0), new Vec3(0, -0.0, value)}) {
                assertFalse(CollisionAxes.isZero(axis));
                assertFalse(productionHook(axis).isCancelled(), "Nonzero native SAT path must remain untouched: " + axis);
            }
        }
    }

    @Test void installedNativeGeometryReproducesCardinalMissAndCorrectionRestoresOverlap() throws Exception {
        Class<?> corrected = correctedInstalledClass();
        var origin = new Vec3(192.5, 97.35000001192093, 192.5);
        var size = new Vec3(4.5, .75, 2.25); // Native Bulwark HORIZONTAL_PLANE at range2.25.
        for (float pitch : new float[]{0, 55}) {
            for (double distance : new double[]{.75, .9, 1.2, 1.5}) {
                var target = targetAtDistance(distance);
                var original = new OrientedBoundingBox(origin, size, pitch, 180).offsetAlongAxisZ(size.z / 2).updateVertex();
                assertFalse(original.intersects(target), "Exact installed class must reproduce the recorded miss");
                assertTrue(intersects(corrected, origin, size, pitch, 180, target), "Real overlapping cardinal boxes must intersect");
                assertFalse(intersects(corrected, origin, size, pitch, 180, target.move(20, 0, 0)), "Distant boxes must remain separated");
            }
        }
    }

    @Test void nativeNonzeroAxisAndFaceTouchingSemanticsArePreserved() throws Exception {
        Class<?> corrected = correctedInstalledClass();
        var origin = new Vec3(192.5, 97.35000001192093, 192.5);
        var size = new Vec3(4.5, .75, 2.25);
        for (float yaw : new float[]{135, 175, 179}) for (float pitch : new float[]{0, 55}) {
            var forward = Vec3.directionFromRotation(0, yaw);
            for (double distance : new double[]{.75, 1.2, 1.5, 1.8, 2.1, 6}) {
                var p = new Vec3(192.5, 96, 192.5).add(forward.scale(distance));
                var target = new AABB(p.x-.3, p.y, p.z-.3, p.x+.3, p.y+1.95, p.z+.3);
                var original = new OrientedBoundingBox(origin, size, pitch, yaw).offsetAlongAxisZ(size.z/2).updateVertex();
                assertEquals(original.intersects(target), intersects(corrected, origin, size, pitch, yaw, target));
            }
        }
        var unit = new AABB(0, 0, 0, 1, 1, 1);
        Object correctedUnit = corrected.getConstructor(AABB.class).newInstance(unit);
        corrected.getMethod("updateVertex").invoke(correctedUnit);
        for (AABB other : new AABB[]{new AABB(1,0,0,2,1,1),new AABB(0,1,0,1,2,1),new AABB(0,0,1,1,1,2)}) {
            boolean nativeResult = new OrientedBoundingBox(unit).updateVertex().intersects(other);
            assertFalse(nativeResult, "Native face contact deliberately does not intersect");
            assertEquals(nativeResult, corrected.getMethod("intersects", AABB.class).invoke(correctedUnit, other));
        }
    }

    private static AABB targetAtDistance(double distance) {
        return new AABB(192.19999998807907, 96, 192.5-distance-.30000001192093,
                192.80000001192093, 97.95000004768372, 192.5-distance+.30000001192093);
    }

    private static boolean intersects(Class<?> type, Vec3 origin, Vec3 size, float pitch, float yaw, AABB target) throws Exception {
        Object box = type.getConstructor(Vec3.class, Vec3.class, float.class, float.class).newInstance(origin, size, pitch, yaw);
        type.getMethod("offsetAlongAxisZ", double.class).invoke(box, size.z/2);
        type.getMethod("updateVertex").invoke(box);
        return (boolean)type.getMethod("intersects", AABB.class).invoke(box, target);
    }

    /** Uses every installed collision instruction; inserts only the production zero-axis predicate. */
    private static Class<?> correctedInstalledClass() throws IOException, ClassNotFoundException {
        var node = new ClassNode();
        try (var bytes = OrientedBoundingBox.class.getResourceAsStream("OrientedBoundingBox.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(node, 0);
        }
        MethodNode method = node.methods.stream().filter(m -> m.name.equals("Separated")).findFirst().orElseThrow();
        var start = new InsnList(); var unchanged = new LabelNode();
        start.add(new VarInsnNode(Opcodes.ALOAD, 2));
        start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/caleb/emfattachments/compat/CollisionAxes", "isZero", "(Lnet/minecraft/world/phys/Vec3;)Z", false));
        start.add(new JumpInsnNode(Opcodes.IFEQ, unchanged));
        start.add(new InsnNode(Opcodes.ICONST_0)); start.add(new InsnNode(Opcodes.IRETURN)); start.add(unchanged);
        method.instructions.insert(start);
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer); byte[] patched = writer.toByteArray();
        var loader = new ClassLoader(BetterCombatCollisionTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(NATIVE)) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) type = defineClass(name, patched, 0, patched.length);
                if (resolve) resolveClass(type);
                return type;
            }
        };
        return loader.loadClass(NATIVE);
    }
}
