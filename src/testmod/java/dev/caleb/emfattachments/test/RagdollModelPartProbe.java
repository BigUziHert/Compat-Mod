package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollPartBlockEntityRenderer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;

/** Distinguishes a physical HEAD block from head geometry leaking through another block's model. */
public final class RagdollModelPartProbe {
    private static final Map<String, Integer> visibility = new LinkedHashMap<>();
    private static final Map<String, java.util.List<Float>> transforms = new LinkedHashMap<>();
    private static final Map<String,String> classes = new LinkedHashMap<>();
    private RagdollModelPartProbe() {}
    public static void reset() { visibility.clear(); transforms.clear(); classes.clear(); }
    public static Map<String, Integer> snapshot() { return new LinkedHashMap<>(visibility); }
    public static Map<String, java.util.List<Float>> transforms() { return new LinkedHashMap<>(transforms); }
    public static Map<String,String> classes() { return new LinkedHashMap<>(classes); }
    public static String key(ModelPart part) {
        if (!RagdollRenderCounters.isLocalActive()) return null;
        var model = RagdollPartBlockEntityRenderer.currentModel();
        var physical = RagdollPartBlockEntityRenderer.activeBodyPart();
        if (model == null || physical == null) return null;
        String name = part == model.head ? "HEAD" : part == model.hat ? "HAT" : part == model.body ? "TORSO"
                : part == model.jacket ? "JACKET" : part == model.leftArm ? "LEFT_ARM" : part == model.rightArm ? "RIGHT_ARM"
                : part == model.leftLeg ? "LEFT_LEG" : part == model.rightLeg ? "RIGHT_LEG" : null;
        return name == null ? null : "model_part/" + physical.name() + "/" + name;
    }
    public static void state(String key, String when, ModelPart part) {
        if (key != null) {
            classes.put(key,part.getClass().getName());
            visibility.merge(key + "/" + when + "/visible=" + part.visible, 1, Integer::sum);
            transforms.put(key + "/" + when, java.util.List.of(part.x,part.y,part.z,part.xRot,part.yRot,part.zRot,part.xScale,part.yScale,part.zScale));
        }
    }
    public static VertexConsumer vertices(String key, VertexConsumer original) {
        return key == null ? original : new RagdollVertexConsumer(original, key);
    }
}
