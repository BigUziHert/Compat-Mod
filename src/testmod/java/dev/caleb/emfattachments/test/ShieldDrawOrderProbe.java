package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Actual draw submissions, including deferred batch flushes, during native shield use. */
public final class ShieldDrawOrderProbe {
    private static final Map<String, List<Map<String, Object>>> captures = new LinkedHashMap<>();
    private static final List<Map<String, Object>> frame = new ArrayList<>();
    private static String frameKey;
    private static int ordinal;
    private static boolean previousActive;
    private ShieldDrawOrderProbe() {}
    public static void register() {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
            boolean active = ExpandedLiveWearableSequence.isActive();
            if (active && !previousActive) captures.clear();
            previousActive = active;
            frame.clear(); frameKey = null; ordinal = 0;
        });
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> saveFrame());
    }
    public static void draw(RenderType type, int vertices) {
        ordinal++;
        var current = AttachmentTestMod.current;
        var mc = Minecraft.getInstance();
        if (current == null || mc.player == null || !ExpandedLiveWearableSequence.isActive()
                || !mc.player.isUsingItem() || mc.player.getTicksUsingItem() < 10) return;
        if (!current.item.equals("aether:shield_of_repulsion") && !current.item.equals("deep_aether:wind_shield")) return;
        if (ModList.get().isLoaded("iris") && net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE) return;
        String key = current.item + "/" + current.pose;
        if (captures.getOrDefault(key, List.of()).size() >= 3) return;
        String description = type.toString();
        String skin = mc.player.getSkin().texture().toString();
        boolean skinDraw = description.contains(skin);
        boolean shieldDraw = description.contains("shield_of_repulsion") || description.contains("wind_shield");
        if (!skinDraw && !shieldDraw) return;
        frameKey = key;
        var row = new LinkedHashMap<String, Object>();
        row.put("ordinal", ordinal); row.put("skin", skinDraw); row.put("shield", shieldDraw);
        row.put("vertices", vertices); row.put("type", description);
        row.put("rawFirstPersonBody", ModList.get().isLoaded("firstperson") && Fpm.raw());
        frame.add(row);
    }
    private static void saveFrame() {
        if (frameKey == null || frame.isEmpty()) return;
        var records = captures.computeIfAbsent(frameKey, key -> new ArrayList<>());
        if (records.size() >= 3) return;
        var mc = Minecraft.getInstance();
        var row = new LinkedHashMap<String, Object>();
        row.put("camera", mc.options.getCameraType().name());
        row.put("nativeTicksUsingItem", mc.player.getTicksUsingItem());
        row.put("draws", List.copyOf(frame)); records.add(row);
        try {
            var output = mc.gameDirectory.toPath().resolve("emfattachments-tests/shield-draw-order.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                    "scope", "Actual RenderType.draw submissions in chronological order; first three active-use frames per shield/camera. No draw or flush behavior altered.",
                    "captures", captures)));
        } catch (Exception error) { throw new IllegalStateException("Cannot save native shield draw order", error); }
    }
    private static final class Fpm {
        static boolean raw() { return dev.tr7zw.firstperson.FirstPersonModelCore.instance.isRenderingPlayerRaw(); }
    }
}
