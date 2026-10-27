package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.wispforest.accessories.api.slot.SlotReference;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import traben.entity_model_features.models.animation.state.EMFState;
import traben.entity_model_features.models.parts.EMFModelPart;

/** Native first-person Artifacts witnesses; deliberately has no compatibility-helper dependency. */
public final class NativeHandAccessoryProbe {
    public static boolean active;
    private static Source current;
    private static final List<Map<String,Object>> rows = new ArrayList<>();
    private NativeHandAccessoryProbe() {}
    public static void beginFrame() { rows.clear(); }
    public static List<Map<String,Object>> snapshot() { return List.copyOf(rows); }

    public static Scope source(HumanoidArm side, ItemStack stack, SlotReference reference, EntityModel<?> model) {
        Source previous = current;
        if (!active) return () -> {};
        if (reference.entity() != Minecraft.getInstance().player || !(model instanceof PlayerModel<?> player)) {
            current=null;return () -> current=previous;
        }
        ModelPart part = side == HumanoidArm.LEFT ? player.leftArm : player.rightArm;
        var state = EMFState.state();
        Source source = new Source(part, ModelState.state(part)); current = source;
        source.row.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        source.row.put("slot", reference.slotName()); source.row.put("slotIndex", reference.slot()); source.row.put("side", side.name());
        source.row.put("sourcePose", source.pose); source.row.put("sourceModel", model.getClass().getName());
        source.row.put("sourceAnimatedEmf", part instanceof EMFModelPart emf && emf.getRoot().hasAnimation());
        source.row.put("nativeFirstPersonHandState", state != null && state.isFirstPersonHand());
        source.row.put("nativeStateLocalPlayer", state != null && (Object)state.emfEntity() == reference.entity());
        source.row.put("draws", source.draws);
        return () -> {
            source.row.put("sourcePoseUnchanged", ModelState.same(source.pose, ModelState.state(part)));
            source.row.put("sourcePoseAfter", ModelState.state(part));
            if (!source.draws.isEmpty() && rows.size() < 16) rows.add(source.row);
            current = previous;
        };
    }

    public static Scope glove(ModelPart part, String renderer) {
        if (!active || current == null) return () -> {};
        Source source = current; Draw previous = source.draw; Draw draw = new Draw(part); source.draw = draw;
        draw.row.put("renderer", renderer); draw.row.put("beforeNativeReset", ModelState.state(part));
        return () -> {
            draw.row.put("actualPartDraws", draw.calls); draw.row.put("vertices", draw.vertices); draw.row.put("invalidVertices", draw.invalid);
            draw.row.put("drawPoses", draw.poses); draw.row.put("worstSourcePoseError", draw.error);
            draw.row.put("nativeResetObserved", draw.reset != null); draw.row.put("nativeResetPose", draw.reset);
            draw.row.put("afterNativeCall", ModelState.state(part));
            draw.row.put("nativeResetPoseRestored", draw.reset != null && ModelState.same(draw.reset, ModelState.state(part)));
            source.draws.add(draw.row); source.draw = previous;
        };
    }

    /** Injected before the protected draw invocation, before any production wrapper can alter it. */
    public static void resetObserved() {
        if (active && current != null && current.draw != null) current.draw.reset = ModelState.state(current.draw.part);
    }
    public static void part(ModelPart part) {
        if (!active || current == null || current.draw == null || current.draw.part != part) return;
        Draw draw = current.draw; draw.calls++;
        float[] actual = ModelState.state(part); if (draw.poses.size() < 4) draw.poses.add(actual);
        draw.error = Math.max(draw.error, error(current.pose, actual));
    }
    public static MultiBufferSource buffers(MultiBufferSource original) {
        if (!active || current == null || current.draw == null) return original;
        Draw draw = current.draw;
        return type -> new Counter(original.getBuffer(type), draw);
    }
    private static float error(float[] a, float[] b) {
        float result = 0; for (int i=0;i<a.length;i++) { if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return Float.MAX_VALUE; result = Math.max(result, Math.abs(a[i]-b[i])); } return result;
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
    private static final class Source {
        final ModelPart part; final float[] pose; final Map<String,Object> row = new LinkedHashMap<>();
        final List<Map<String,Object>> draws = new ArrayList<>(); Draw draw;
        Source(ModelPart part,float[] pose) { this.part=part;this.pose=pose; }
    }
    private static final class Draw {
        final ModelPart part; final Map<String,Object> row=new LinkedHashMap<>(); final List<float[]> poses=new ArrayList<>();
        float[] reset; int calls,vertices,invalid; float error;
        Draw(ModelPart part) { this.part=part; }
    }
    private record Counter(VertexConsumer delegate, Draw draw) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x,float y,float z) { draw.vertices++;if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))draw.invalid++;delegate.addVertex(x,y,z);return this; }
        @Override public VertexConsumer setColor(int r,int g,int b,int a) { delegate.setColor(r,g,b,a);return this; }
        @Override public VertexConsumer setUv(float u,float v) { delegate.setUv(u,v);return this; }
        @Override public VertexConsumer setUv1(int u,int v) { delegate.setUv1(u,v);return this; }
        @Override public VertexConsumer setUv2(int u,int v) { delegate.setUv2(u,v);return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) { delegate.setNormal(x,y,z);return this; }
    }
}
