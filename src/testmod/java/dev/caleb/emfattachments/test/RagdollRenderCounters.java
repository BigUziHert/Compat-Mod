package dev.caleb.emfattachments.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.fml.ModList;

/** Main-camera calls in the real dedicated ragdoll renderer, not pose simulations. */
public final class RagdollRenderCounters {
    public static boolean active;
    private static boolean localOwner;
    private static final Map<String, Integer> parts = new LinkedHashMap<>();
    private static final Map<String, Integer> items = new LinkedHashMap<>();
    private static final Map<String, Integer> vertices = new LinkedHashMap<>();
    private static int invalidVertices;
    public static int sourcePlayerCanceled;
    private RagdollRenderCounters() {}
    public static void reset() { parts.clear(); items.clear(); vertices.clear(); invalidVertices = 0; sourcePlayerCanceled = 0; RagdollModelPartProbe.reset(); }
    public static boolean isLocalActive() { return active && localOwner && mainPass(); }
    public static boolean begin(UUID owner) {
        boolean previous = localOwner;
        localOwner = active && Minecraft.getInstance().player != null && Minecraft.getInstance().player.getUUID().equals(owner);
        return previous;
    }
    public static void end(boolean previous) { localOwner = previous; }
    public static boolean mainPass() {
        return !ModList.get().isLoaded("iris") || !net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();
    }
    public static void part(String name) { if (active && localOwner && mainPass()) parts.merge(name, 1, Integer::sum); }
    public static void item(String slot, String id) { if (active && localOwner && mainPass()) items.merge(slot + ":" + id, 1, Integer::sum); }
    public static MultiBufferSource buffers(String key, MultiBufferSource original) {
        if (!active || !localOwner || !mainPass()) return original;
        return type -> new RagdollVertexConsumer(original.getBuffer(type), key);
    }
    public static void vertex(String key, float x, float y, float z) {
        vertices.merge(key, 1, Integer::sum);
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) invalidVertices++;
    }
    public static Map<String, Object> snapshot() {
        return Map.of("nativePartLayerCalls", new LinkedHashMap<>(parts), "nativeAccessoryCalls", new LinkedHashMap<>(items),
                "nativeAccessoryVertices", new LinkedHashMap<>(vertices), "nonFiniteVertices", invalidVertices, "nativeSourcePlayerCancellations", sourcePlayerCanceled,
                "modelPartVisibility", RagdollModelPartProbe.snapshot(), "modelPartTransformsXYZRotationScale", RagdollModelPartProbe.transforms(),
                "modelPartClasses",RagdollModelPartProbe.classes());
    }
    public static int partCount(String part) { return parts.getOrDefault(part, 0); }
    public static int itemCount(String id) {
        return items.entrySet().stream().filter(e -> e.getKey().endsWith(":" + id)).mapToInt(Map.Entry::getValue).sum();
    }
}
