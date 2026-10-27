package dev.caleb.emfattachments.test;

import net.minecraft.client.model.geom.ModelPart;

/** Shared numerical helpers; kept outside mixin classes so normal code can call them. */
public final class ModelState {
    private static final java.lang.reflect.Field CUBES = cubesField();
    private ModelState() { }
    public static float[] state(ModelPart p) { return new float[]{p.x,p.y,p.z,p.xRot,p.yRot,p.zRot,p.xScale,p.yScale,p.zScale}; }
    public static boolean same(float[] a, float[] b) { for (int i=0;i<a.length;i++) if (!Float.isFinite(a[i]) || !Float.isFinite(b[i]) || Math.abs(a[i]-b[i])>0.00001F) return false; return true; }
    private static java.lang.reflect.Field cubesField() { try { var field=ModelPart.class.getDeclaredField("cubes");field.setAccessible(true);return field; }catch(ReflectiveOperationException error){throw new ExceptionInInitializerError(error);} }
    public static int cubeCount(ModelPart part) { try { return ((java.util.List<?>)CUBES.get(part)).size(); }catch(IllegalAccessException error){throw new IllegalStateException(error);} }
}
