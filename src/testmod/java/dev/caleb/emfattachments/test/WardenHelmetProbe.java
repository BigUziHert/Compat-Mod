package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.kyanite.deeperdarker.client.render.WardenHelmetRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Direct native extra-helmet layer checks; separate from vanilla armor/Accessories. */
public final class WardenHelmetProbe {
    public static boolean active;
    public static int bakes;
    public static void register() { NeoForge.EVENT_BUS.addListener(WardenHelmetProbe::tick); }
    private static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !mc.hasSingleplayerServer()) return;
        var trigger = mc.gameDirectory.toPath().resolve("emfattachments-warden-probe.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(java.nio.file.Path.of(".local","client"))) throw new IllegalStateException("Disposable client required");
            String label = Files.readString(trigger).trim().replaceAll("[^a-zA-Z0-9._-]", "_"); Files.delete(trigger);
            var out = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label); Files.createDirectories(out);
            Files.writeString(out.resolve("warden-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(run()));
        } catch (Throwable ex) { com.mojang.logging.LogUtils.getLogger().error("Warden helmet probe failed", ex); }
    }
    private static Map<String,Object> run() {
        var mc = Minecraft.getInstance(); var player = mc.player; var core = FirstPersonModelCore.instance;
        var original = player.getInventory().armor.get(EquipmentSlot.HEAD.getIndex()).copy();
        var originalChest = player.getInventory().armor.get(EquipmentSlot.CHEST.getIndex()).copy();
        var camera = mc.options.getCameraType(); var cameraEntity = mc.getCameraEntity();
        boolean raw = core.isRenderingPlayerRaw(), extract = FirstPersonModelCore.cameraEntityExtract, enabled = FirstPersonAPI.isEnabled();
        var report = new LinkedHashMap<String,Object>(); var rows = new ArrayList<Map<String,Object>>(); var failures = new ArrayList<String>();
        report.put("scope", "Actual DeeperDarker layer and native Player head-slot lookup: local body hides; remote player, extraction, disabled FPM, third-person raw flag and other camera contexts preserve equipment. Repeated model draws must reuse the bake.");
        report.put("rows", rows); report.put("failures", failures);
        try {
            player.setItemSlot(EquipmentSlot.HEAD, BuiltInRegistries.ITEM.get(ResourceLocation.parse("deeperdarker:warden_helmet")).getDefaultInstance());
            player.setItemSlot(EquipmentSlot.CHEST,Items.DIAMOND_CHESTPLATE.getDefaultInstance());
            var remote = new RemotePlayer(mc.level,new GameProfile(new UUID(0,0x3ad193),"HeadScopeProbe"));
            remote.setPos(player.position());
            remote.setItemSlot(EquipmentSlot.HEAD,BuiltInRegistries.ITEM.get(ResourceLocation.parse("deeperdarker:warden_helmet")).getDefaultInstance());
            remote.setItemSlot(EquipmentSlot.CHEST,Items.DIAMOND_CHESTPLATE.getDefaultInstance());
            var parent = (PlayerRenderer)(Object)mc.getEntityRenderDispatcher().getRenderer(player);
            var layer = new WardenHelmetRenderer<>(parent, mc.getEntityModels());
            FirstPersonAPI.setEnabled(true); mc.setCameraEntity(player); active = true; bakes = 0;
            for (String context : new String[]{"third-person-first","third-person-repeat","local-body","remote-during-body",
                    "camera-extraction","remote-camera-extraction","extraction-only","disabled-fpm","remote-disabled-fpm",
                    "third-person-raw-flag","remote-third-person-raw-flag","other-camera-entity","detached-main-camera","third-person-restored"}) {
                boolean remoteContext = context.startsWith("remote-");
                boolean firstCamera = !context.startsWith("third-person") && !context.equals("remote-third-person-raw-flag");
                boolean broadFlag = !context.equals("third-person-first") && !context.equals("third-person-repeat") && !context.equals("third-person-restored") && !context.equals("extraction-only");
                boolean extractionContext = context.contains("extraction");
                var renderedPlayer = remoteContext ? remote : player;
                var currentCameraEntity = context.equals("other-camera-entity") ? remote : player;
                FirstPersonAPI.setEnabled(!context.contains("disabled-fpm")); core.setRenderingPlayer(broadFlag); FirstPersonModelCore.cameraEntityExtract = extractionContext;
                mc.setCameraEntity(currentCameraEntity);
                mc.options.setCameraType(firstCamera ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
                mc.gameRenderer.getMainCamera().setup(mc.level,currentCameraEntity,!firstCamera || context.equals("detached-main-camera"),false,0);
                boolean rawBefore = core.isRenderingPlayerRaw(), extractionBefore = FirstPersonModelCore.cameraEntityExtract;
                var headLookup = renderedPlayer.getItemBySlot(EquipmentSlot.HEAD);
                var chestLookup = renderedPlayer.getItemBySlot(EquipmentSlot.CHEST);
                Counter count = new Counter(); int before = bakes;
                try (var ignored = new EmfProbeContext(renderedPlayer)) { layer.render(new PoseStack(), type -> count, 0xF000F0, renderedPlayer, 0, 0, 0, renderedPlayer.tickCount, 0, 0); }
                boolean shouldHide = context.equals("local-body");
                var row = new LinkedHashMap<String,Object>(); row.put("context", context); row.put("vertices", count.vertices); row.put("invalid", count.invalid); row.put("modelBakes", bakes-before); row.put("expectedHidden", shouldHide);
                row.put("headLookupEmpty",headLookup.isEmpty()); row.put("headLookupItem",BuiltInRegistries.ITEM.getKey(headLookup.getItem()).toString());
                row.put("renderedEntity",remoteContext?"remote":"local");
                row.put("nativeRenderFlagsPreserved",rawBefore == core.isRenderingPlayerRaw() && extractionBefore == FirstPersonModelCore.cameraEntityExtract);
                if (headLookup.isEmpty() != shouldHide) failures.add(context + " native head lookup scope wrong");
                if (!chestLookup.is(Items.DIAMOND_CHESTPLATE)) failures.add(context + " unrelated chest lookup changed");
                if (rawBefore != core.isRenderingPlayerRaw() || extractionBefore != FirstPersonModelCore.cameraEntityExtract) failures.add(context + " changed native render flags");
                if (count.invalid != 0 || (shouldHide ? count.vertices != 0 : count.vertices == 0)) failures.add(context + " unexpected geometry: " + count.vertices);
                if (!context.equals("third-person-first") && bakes != before) failures.add(context + " rebaked stable model");
                rows.add(row);
            }
            report.put("totalBakes",bakes); report.put("status",failures.isEmpty()?"PASS":"FAIL");
        } finally {
            active=false; player.setItemSlot(EquipmentSlot.HEAD,original); player.setItemSlot(EquipmentSlot.CHEST,originalChest); FirstPersonAPI.setEnabled(enabled); core.setRenderingPlayer(raw); FirstPersonModelCore.cameraEntityExtract=extract;
            mc.options.setCameraType(camera); mc.setCameraEntity(cameraEntity); mc.gameRenderer.getMainCamera().setup(mc.level,cameraEntity,!camera.isFirstPerson(),camera.isMirrored(),0);
            report.put("stateRestored",ItemStack.matches(original,player.getInventory().armor.get(EquipmentSlot.HEAD.getIndex()))
                    && ItemStack.matches(originalChest,player.getInventory().armor.get(EquipmentSlot.CHEST.getIndex())));
        }
        return report;
    }
    private static class Counter implements VertexConsumer {
        int vertices,invalid;
        public VertexConsumer addVertex(float x,float y,float z) {vertices++; if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))invalid++;return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        public VertexConsumer setUv(float u,float v){return this;}
        public VertexConsumer setUv1(int u,int v){return this;}
        public VertexConsumer setUv2(int u,int v){return this;}
        public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
}
