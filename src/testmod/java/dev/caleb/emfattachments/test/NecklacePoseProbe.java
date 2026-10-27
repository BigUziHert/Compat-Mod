package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Read-only witness at the real scaled necklace's part-transform boundary. */
public final class NecklacePoseProbe {
    public static boolean active;
    private static final Map<HumanoidModel<?>, Copy> copies = new IdentityHashMap<>();
    private static final List<Map<String, Object>> draws = new ArrayList<>();
    private static Trace current;
    private NecklacePoseProbe() {}

    public static void beginFrame() { copies.clear(); draws.clear(); }
    public static void copied(LivingEntity entity, HumanoidModel<?> model) {
        if (!active || !model.getClass().getName().equals("artifacts.client.item.model.NecklaceModel")) return;
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        if (renderer instanceof LivingEntityRenderer<?, ?> living && living.getModel() instanceof HumanoidModel<?> parent)
            copies.put(model, new Copy(entity.getUUID().toString(), ModelState.state(parent.body), ModelState.state(model.body)));
    }

    public static Scope begin(HumanoidModel<?> model, PoseStack matrices) {
        Trace previous = current;
        if (!active) return () -> current = previous;
        Copy copy = copies.get(model);
        Matrix4f outer = new Matrix4f(matrices.last().pose());
        if (model.young) outer.scale(.5f).translate(0, 24f / 16f, 0);
        Matrix4f body = transform(model.body);
        current = new Trace(model.body, new Matrix4f(outer).mul(body).scale(.51f),
                new Matrix4f(outer).scale(.51f).mul(body), copy, ModelState.state(model.body));
        Trace trace = current;
        return () -> {
            if (draws.size() < 32) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bodyState", trace.before); row.put("partStateRestored", ModelState.same(trace.before, ModelState.state(trace.body)));
                row.put("translationWitness", List.of(trace.body.x, trace.body.y, trace.body.z));
                row.put("transforms", trace.calls); row.put("actualMatrix", trace.actual);
                row.put("expectedMatrix", trace.expected.get(new float[16]));
                row.put("expectedError", trace.error); row.put("unfixedCounterfactualError", error(trace.expected, trace.unfixed));
                row.put("sourceCopyObserved", copy != null);
                if (copy != null) { row.put("entity", copy.entity); row.put("sourceTorso", copy.parent); row.put("copiedTorso", copy.copied); row.put("sourceCopyMatches", ModelState.same(copy.parent, copy.copied)); }
                row.put("camera", Minecraft.getInstance().options.getCameraType().name());
                row.put("shadow", net.neoforged.fml.ModList.get().isLoaded("iris") && Shadow.active());
                draws.add(row);
            }
            current = previous;
        };
    }

    public static void transformed(ModelPart part, PoseStack matrices) {
        if (!active || current == null || current.body != part) return;
        current.calls++;
        current.actual = matrices.last().pose().get(new float[16]);
        current.error = error(current.expected, matrices.last().pose());
    }
    public static List<Map<String, Object>> snapshot() { return List.copyOf(draws); }
    private static Matrix4f transform(ModelPart part) {
        return new Matrix4f().translate(part.x / 16f, part.y / 16f, part.z / 16f)
                .rotate(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot))
                .scale(part.xScale, part.yScale, part.zScale);
    }
    private static float error(Matrix4f a, Matrix4f b) {
        float[] left = a.get(new float[16]), right = b.get(new float[16]); float worst = 0;
        for (int i = 0; i < 16; i++) {
            if (!Float.isFinite(left[i]) || !Float.isFinite(right[i])) return Float.MAX_VALUE;
            worst = Math.max(worst, Math.abs(left[i] - right[i]));
        }
        return worst;
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
    private static final class Shadow {
        static boolean active() { return net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE; }
    }
    private record Copy(String entity, float[] parent, float[] copied) {}
    private static final class Trace {
        final ModelPart body; final Matrix4f expected, unfixed; final Copy copy; final float[] before;
        int calls; float[] actual = new float[0]; float error = Float.MAX_VALUE;
        Trace(ModelPart body, Matrix4f expected, Matrix4f unfixed, Copy copy, float[] before) {
            this.body = body; this.expected = expected; this.unfixed = unfixed; this.copy = copy; this.before = before;
        }
    }
}
