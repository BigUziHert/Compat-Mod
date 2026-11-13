package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.FirstPersonCombat;
import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.util.Vec3f;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;

/** Opt-in observations at the actual PAL/Core boundaries; never changes a pose. */
public final class OverlapCombatProbe {
    public static boolean active, tiltOff, legacyY;
    private static Map<String, Object> current;
    private static final List<Map<String, Object>> draws = new ArrayList<>();
    private static final List<Map<String, Object>> transforms = new ArrayList<>();
    private OverlapCombatProbe() {}

    public static void beginFrame() { current = null; draws.clear(); transforms.clear(); }
    public static boolean local(LivingEntity player) {
        return active && FirstPersonRenderPolicy.isLocalBody(player) && FirstPersonCombat.ownsCombatRendering();
    }
    public static void coreBefore(LivingEntity player, PoseStack pose) {
        if (!local(player)) return;
        current = new LinkedHashMap<>();
        current.put("coreBefore", matrix(pose.last().pose()));
    }
    public static void palBefore(LivingEntity player, PoseStack pose) {
        if (local(player) && current != null) current.put("palBefore", matrix(pose.last().pose()));
    }
    public static void palAfter(LivingEntity player, PoseStack pose) {
        if (local(player) && current != null) current.put("palAfter", matrix(pose.last().pose()));
    }
    public static void coreEntry(LivingEntity player, PoseStack pose) {
        if (local(player) && current != null) current.put("coreBeforeNormalize", matrix(pose.last().pose()));
    }
    public static void coreAfter(LivingEntity player, PoseStack pose) {
        if (!local(player) || current == null) return;
        current.put("coreAfter", matrix(pose.last().pose()));
        if (draws.size() < 8) draws.add(current);
        current = null;
    }
    public static boolean transformBefore(Object processor, String part, TransformType type, Vec3f value, float delta) {
        if (!active || !FirstPersonCombat.modifiesBodyProcessor(processor)) return false;
        boolean bypass = tiltOff && part.equals("body") && type == TransformType.ROTATION;
        if (transforms.size() < 80) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("part", part); row.put("type", type.name()); row.put("native", vector(value));
            row.put("weight", FirstPersonCombat.correctionWeight(part, type, delta)); row.put("bypassed", bypass);
            transforms.add(row);
        }
        return bypass;
    }
    public static void transformAfter(Object processor, String part, TransformType type, Vec3f value) {
        if (!active || !FirstPersonCombat.modifiesBodyProcessor(processor)) return;
        for (int i = transforms.size() - 1; i >= 0; i--) {
            Map<String, Object> row = transforms.get(i);
            if (row.get("part").equals(part) && row.get("type").equals(type.name()) && !row.containsKey("corrected")) {
                row.put("corrected", vector(value)); return;
            }
        }
    }
    public static Map<String, Object> snapshot() {
        return Map.of("draws", List.copyOf(draws), "transforms", List.copyOf(transforms));
    }
    private static List<Float> vector(Vec3f value) {
        return value == null ? List.of() : List.of(value.getX(), value.getY(), value.getZ());
    }
    private static List<Float> matrix(Matrix4f value) {
        float[] array = new float[16]; value.get(array);
        List<Float> result = new ArrayList<>(); for (float element : array) result.add(element); return result;
    }
}
