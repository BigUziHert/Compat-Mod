package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.leo.sableplayerragdoll.api.DespawnCondition;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.leo.sableplayerragdoll.api.RagdollLaunchOptions;
import dev.leo.sableplayerragdoll.neoforge.config.RagdollClientConfig;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Separate native ragdoll camera/geometry fixture; does not change the movement suite. */
public final class ExpandedRagdollSequence {
    private static Session active;
    private static volatile long serverTicks;
    private ExpandedRagdollSequence() {}
    public static void register() {
        RagdollCameraProbe.register();
        if (!ModList.get().isLoaded("sable_player_ragdoll")) return;
        NeoForge.EVENT_BUS.addListener(ExpandedRagdollSequence::tick);
        NeoForge.EVENT_BUS.addListener(ExpandedRagdollSequence::beforeFrame);
        NeoForge.EVENT_BUS.addListener(ExpandedRagdollSequence::afterFrame);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) -> serverTicks++);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, RenderPlayerEvent.Pre.class, event -> {
            if (active != null && RagdollRenderCounters.active && event.getEntity() == Minecraft.getInstance().player
                    && event.isCanceled() && RagdollRenderCounters.mainPass()) RagdollRenderCounters.sourcePlayerCanceled++;
        });
    }
    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (active != null) { if (active.tick()) active = null; return; }
            if (mc.player == null || mc.level == null || mc.screen != null || CombatRenderCounters.active) return;
            Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-expanded-ragdoll.txt");
            if (!Files.exists(trigger)) return;
            if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                    || !mc.hasSingleplayerServer()) throw new IllegalStateException("Requires disposable integrated .local/client world");
            List<String> lines = Files.readAllLines(trigger);
            String label = lines.getFirst().trim().replaceAll("[^a-zA-Z0-9._-]", "_");
            boolean shrink=lines.stream().anyMatch(s->s.trim().equals("shrink"));
            boolean diagnostic=shrink || lines.stream().anyMatch(s->s.trim().equals("diagnostic"));
            boolean nativePose=lines.stream().anyMatch(s->s.trim().equals("native_pose"));
            boolean overlayProbe=lines.stream().anyMatch(s->s.trim().equals("overlay_probe"));
            boolean pairedClaws=lines.stream().anyMatch(s->s.trim().equals("paired_claws"));
            Files.delete(trigger);
            String safeLabel=label.isBlank()?"expanded-ragdoll":label;
            try { active = new Session(mc, safeLabel, shrink, diagnostic, nativePose, overlayProbe, pairedClaws); }
            catch(Throwable failure) {
                Path output=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(safeLabel);
                Files.createDirectories(output);
                Files.writeString(output.resolve("ragdoll-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(
                        Map.of("status","FAIL","detail","Native ragdoll startup failed: "+failure,"startupFailure",true)));
                throw failure;
            }
        } catch (Throwable failure) {
            if (active == null) LogUtils.getLogger().error("Native ragdoll fixture startup failed", failure);
            else active.finish("FAIL", failure.toString());
        }
    }
    private static void beforeFrame(RenderFrameEvent.Pre event) {
        if (active != null) { CombatRenderCounters.active = true; CombatRenderCounters.reset(); }
    }
    private static void afterFrame(RenderFrameEvent.Post event) {
        if (active == null) return;
        try { active.frame(); } catch (Throwable failure) { active.finish("FAIL", failure.toString()); }
    }
    private record AccessoryState(List<ItemStack> normal, List<ItemStack> cosmetic, List<Boolean> flags) {}
    private static final class Session {
        final Minecraft mc;
        final boolean shrink, diagnostic, nativePose, overlayProbe, pairedClaws;
        String clawSlot;
        final Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState> blocks=new LinkedHashMap<>();
        final Vec3 origin;
        volatile float serverScale;
        int view;
        final Path output;
        final CameraType camera;
        final boolean gui, ragdollCamera, invisible;
        final Vec3 position;
        final float yaw, pitch;
        final Map<String, AccessoryState> accessories = new LinkedHashMap<>();
        final Map<KeyMapping, Boolean> keys = new LinkedHashMap<>();
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<Map<String, Object>> captures = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        ServerPlayer player;
        ListTag inventory;
        GameType gameType;
        boolean flying, launched, finishing, clientRestored, countingStarted, restoreStarted;
        long cleanupAfterServerTick; volatile long cleanupGraceThroughServerTick;
        CompletableFuture<Void> pending;
        CompletableFuture<Map<String, Object>> observation;
        int stage, ticks, cleanupTicks;
        String status, detail;
        Session(Minecraft mc, String label, boolean shrink, boolean diagnostic, boolean nativePose, boolean overlayProbe, boolean pairedClaws) throws Exception {
            this.mc = mc; this.shrink=shrink; this.diagnostic=diagnostic; this.nativePose=nativePose; this.overlayProbe=overlayProbe; this.pairedClaws=pairedClaws;
            if (mc.player.isPassenger() || mc.player.isUsingItem()) throw new IllegalStateException("Start recovered and without an active use action");
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label); Files.createDirectories(output);
            camera = mc.options.getCameraType(); gui = mc.options.hideGui;
            ragdollCamera = RagdollClientConfig.USE_FIRST_PERSON_CAMERA.get(); invisible = mc.player.isInvisible();
            position = mc.player.position(); yaw = mc.player.getYRot(); pitch = mc.player.getXRot();
            origin=diagnostic?new Vec3(256.5,mc.level.getMinBuildHeight()+13,256.5):position;
            report.put("shrinkingCharm",shrink); report.put("controlledNativePose",diagnostic&&!nativePose); report.put("nativeAsyncPose",nativePose||!diagnostic);
            for (KeyMapping key : List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                    mc.options.keyJump, mc.options.keyShift, mc.options.keySprint, mc.options.keyUse, mc.options.keyAttack)) keys.put(key, key.isDown());
            var originalKeys=new LinkedHashMap<String,Boolean>(); keys.forEach((key,down)->originalKeys.put(key.getName(),down));
            report.put("originalInputStates",originalKeys); report.put("originalClientPosition",position.toString());
            report.put("originalClientMotion",mc.player.getDeltaMovement().toString());
            keys.keySet().forEach(key -> key.setDown(false));
            report.put("scope", "Real native RagdollAPI launch, dedicated physical limb renderer and supported firstperson/contraption camera settings. Pose source is recorded in controlledNativePose/nativeAsyncPose; no mods disabled.");
            var drawScopeControls = RagdollDrawScopeProbe.run();
            report.put("bridgedAccessoryScopeControls", drawScopeControls);
            if (!"PASS".equals(drawScopeControls.get("status"))) failures.add("Bridged accessory scope controls failed: " + drawScopeControls.get("error"));
            report.put("pairedClaws", pairedClaws);
            if (pairedClaws) {
                var clawControls = RagdollClawProbe.run(); report.put("nativePairedClawControls", clawControls);
                if (!"PASS".equals(clawControls.get("status"))) failures.add("Native paired claw controls failed: " + clawControls.get("detail"));
            }
            report.put("firstPersonInstalled", ModList.get().isLoaded("firstperson"));
            RagdollCameraProbe.resetHistory();
            report.put("captures", captures); report.put("failures", failures); report.put("resourcePacks", mc.getResourcePackRepository().getSelectedIds());
            mc.options.hideGui = false; CombatRenderCounters.active = true;
            pending = task(() -> {
                player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                inventory = player.getInventory().save(new ListTag()); gameType = player.gameMode.getGameModeForPlayer(); flying = player.getAbilities().flying;
                AccessoriesCapability.get(player).getContainers().forEach((name, container) -> {
                    List<ItemStack> normal = new ArrayList<>(), cosmetic = new ArrayList<>();
                    for (int i = 0; i < container.getSize(); i++) { normal.add(container.getAccessories().getItem(i).copy()); cosmetic.add(container.getCosmeticAccessories().getItem(i).copy()); }
                    accessories.put(name, new AccessoryState(normal, cosmetic, new ArrayList<>(container.renderOptions())));
                });
                prepare();
            });
            write("PREPARING", "");
        }
        CompletableFuture<Void> task(Runnable action) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            mc.getSingleplayerServer().execute(() -> { try { action.run(); result.complete(null); } catch (Throwable t) { result.completeExceptionally(t); } });
            return result;
        }
        void prepare() {
            ExpandedRagdollRecovery.release(player); player.stopUsingItem(); player.stopRiding();
            player.getInventory().clearContent();
            var cap = AccessoriesCapability.get(player);
            cap.getContainers().values().forEach(c -> { c.getAccessories().clearContent(); c.getCosmeticAccessories().clearContent(); });
            if(diagnostic) {
                var floor=net.minecraft.core.BlockPos.containing(origin).below();
                for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) for(int y=0;y<=7;y++) {
                    var pos=floor.offset(x,y,z); blocks.putIfAbsent(pos,player.serverLevel().getBlockState(pos));
                    player.serverLevel().setBlockAndUpdate(pos,y==0?net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState():net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
            if(shrink) {
                ItemStack charm=BuiltInRegistries.ITEM.get(ResourceLocation.parse("artifacts:charm_of_shrinking")).getDefaultInstance();
                var chosen=AccessoriesAPI.getStackSlotTypes(player,charm).stream().filter(s->!io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(s.name()))
                    .filter(s->cap.getContainer(s)!=null && cap.getContainer(s).getSize()>0).sorted(java.util.Comparator.comparing(s->s.name())).findFirst().orElseThrow();
                cap.getContainer(chosen).getAccessories().setItem(0,charm); cap.getContainer(chosen).renderOptions().set(0,true);
                report.put("shrinkingCharmSlot",chosen.name()+":0");
                report.put("shrinkingCharmDisabled",charm.has(artifacts.registry.ModDataComponents.DISABLED_BY_TOGGLE.get()));
            }
            ItemStack goggles = BuiltInRegistries.ITEM.get(ResourceLocation.parse("aeronautics:aviators_goggles")).getDefaultInstance();
            var slot = AccessoriesAPI.getStackSlotTypes(player, goggles).stream().filter(s -> !io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(s.name()))
                    .filter(s -> cap.getContainer(s) != null && cap.getContainer(s).getSize() > 0).findFirst().orElseThrow();
            var container = cap.getContainer(slot); container.getAccessories().setItem(0, goggles); container.renderOptions().set(0, true);
            if (pairedClaws) {
                ItemStack blue=BuiltInRegistries.ITEM.get(ResourceLocation.parse("artifacts:digging_claws")).getDefaultInstance();
                ItemStack green=BuiltInRegistries.ITEM.get(ResourceLocation.parse("artifacts:feral_claws")).getDefaultInstance();
                var greenSlots=AccessoriesAPI.getStackSlotTypes(player,green);
                var hand=AccessoriesAPI.getStackSlotTypes(player,blue).stream()
                        .filter(s->s.name().equals("hand") && greenSlots.stream().anyMatch(g->g.name().equals(s.name())))
                        .filter(s->cap.getContainer(s)!=null && cap.getContainer(s).getSize()>=2).findFirst().orElseThrow();
                clawSlot=hand.name(); var hands=cap.getContainer(hand);
                hands.getAccessories().setItem(0,blue); hands.getAccessories().setItem(1,green);
                hands.renderOptions().set(0,true); hands.renderOptions().set(1,true);
            }
            player.setItemSlot(EquipmentSlot.HEAD, Items.DIAMOND_HELMET.getDefaultInstance());
            player.setItemSlot(EquipmentSlot.MAINHAND, Items.IRON_SWORD.getDefaultInstance());
            player.setItemSlot(EquipmentSlot.FEET, BuiltInRegistries.ITEM.get(ResourceLocation.parse("cataclysm:cursium_boots")).getDefaultInstance());
            player.setGameMode(GameType.CREATIVE); player.getAbilities().flying = false; player.onUpdateAbilities();
            player.teleportTo(player.serverLevel(), origin.x, origin.y, origin.z, 0, 65);
            player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0; player.inventoryMenu.broadcastChanges();
        }
        boolean tick() throws Exception {
            if (pending != null) {
                if (!pending.isDone()) return false;
                try { pending.join(); }
                catch (java.util.concurrent.CompletionException failure) {
                    if (!finishing) throw failure;
                    status = "FAIL"; detail += "; native restore failed: " + failure;
                }
                finally { pending = null; }
            }
            if (finishing) {
                if (!restoreStarted) {
                    // The native API requests the current animated pose and can
                    // create the session up to ten server ticks later. Drain
                    // that native request before recovery, including failures.
                    if (serverTicks < cleanupAfterServerTick) return false;
                    restoreStarted = true; pending = task(this::restoreServer); return false;
                }
                keys.keySet().forEach(key->key.setDown(false));
                if(serverTicks<cleanupGraceThroughServerTick || ++cleanupTicks < 20) return false;
                if (!clientRestored) {
                    mc.options.setCameraType(camera); mc.options.hideGui = gui; RagdollClientConfig.USE_FIRST_PERSON_CAMERA.set(ragdollCamera);
                    mc.player.setYRot(yaw); mc.player.setXRot(pitch); clientRestored = true;
                    keys.forEach(KeyMapping::setDown);
                    report.put("inputRestored", keys.entrySet().stream().allMatch(e -> e.getKey().isDown() == e.getValue()));
                    report.put("nativeClientSeatReleased", ExpandedRagdollRecovery.clientRestored(mc.player));
                    report.put("nativeClientVisibilityRestored", mc.player.isInvisible() == invisible);
                    report.put("nativeCameraConfigRestored", RagdollClientConfig.USE_FIRST_PERSON_CAMERA.get() == ragdollCamera);
                    report.put("cleanupReactionGraceExpired",serverTicks>=cleanupGraceThroughServerTick);
                    report.put("restoredClientPosition",mc.player.position().toString());
                    report.put("restoredClientMotion",mc.player.getDeltaMovement().toString());
                    report.put("screenOverlayHistory",RagdollCameraProbe.history());
                    if (!ExpandedRagdollRecovery.clientRestored(mc.player) || mc.player.isInvisible() != invisible) {
                        status = "FAIL"; detail += "; native client ragdoll state did not restore";
                    }
                    CombatRenderCounters.active = false; RagdollRenderCounters.active = false;
                }
                write(status, detail); return true;
            }
            if (mc.screen != null) { finish("ABORTED", "Screen opened"); return false; }
            keys.keySet().forEach(key -> key.setDown(false));
            ticks++;
            if(shrink) mc.getSingleplayerServer().execute(()->serverScale=player.getScale());
            if (!launched) {
                if (ticks < 20 || !ExpandedRagdollRecovery.clientRestored(mc.player)) return false;
                if (pairedClaws) {
                    var hands=AccessoriesCapability.get(mc.player).getContainers().get(clawSlot);
                    boolean synced=hands!=null && hands.getSize()>=2
                            && BuiltInRegistries.ITEM.getKey(hands.getAccessories().getItem(0).getItem()).toString().equals("artifacts:digging_claws")
                            && BuiltInRegistries.ITEM.getKey(hands.getAccessories().getItem(1).getItem()).toString().equals("artifacts:feral_claws");
                    if (!synced) { if(ticks>200) throw new IllegalStateException("Paired native claws did not synchronize"); return false; }
                    report.put("pairedClawEquipment",Map.of("slot",clawSlot,"index0","artifacts:digging_claws","index1","artifacts:feral_claws","clientMainArm",mc.player.getMainArm().name(),"clientSynchronized",true));
                }
                if(shrink && (Math.abs(mc.player.getScale()-.5)>.001 || Math.abs(serverScale-.5)>.001)) {
                    if(ticks>200) throw new IllegalStateException("Native shrinking charm scale failed to synchronize");
                    return false;
                }
                RagdollClientConfig.USE_FIRST_PERSON_CAMERA.set(stage == 0); mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.player.setYRot(0); mc.player.setXRot(65); mc.player.yRotO = 0; mc.player.xRotO = 65;
                RagdollRenderCounters.reset(); RagdollRenderCounters.active = false;
                pending = task(() -> {
                    var options = RagdollLaunchOptions.builder().autoSeat(true).lockDismount(true).despawnConditions(List.of(DespawnCondition.afterTicks(240))).build();
                    // This overload intentionally returns null while obtaining
                    // the native client pose. Observe the resulting real seat
                    // and session instead of treating its return as failure.
                    if(diagnostic&&!nativePose) RagdollAPI.launch(player, Vec3.ZERO, options,
                        new dev.leo.sableplayerragdoll.api.RagdollPoseSnapshot(dev.leo.sableplayerragdoll.api.RagdollLimbOptions.defaults(),0));
                    else RagdollAPI.launch(player, Vec3.ZERO, options);
                });
                launched = true; ticks = 0;
            }
            if (launched && !countingStarted && ticks >= 30 && !ExpandedRagdollRecovery.clientRestored(mc.player)
                    && (stage == 0) == mc.options.getCameraType().isFirstPerson()) {
                RagdollRenderCounters.reset(); RagdollRenderCounters.active = true; countingStarted = true;
            }
            if (ticks > 180) throw new IllegalStateException("Native ragdoll/camera did not settle");
            return false;
        }
        void frame() throws Exception {
            if (finishing || !launched || !countingStarted || ticks < 55 || pending != null) return;
            if (observation == null) {
                observation = new CompletableFuture<>(); var result = observation;
                mc.getSingleplayerServer().execute(() -> result.complete(Map.of("nativeSession", ExpandedRagdollRecovery.active(player), "invisible", player.isInvisible(),
                        "vehicle", player.getVehicle() == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString(), "position", player.position().toString(), "scale", player.getScale())));
                return;
            }
            if (!observation.isDone()) return;
            Map<String, Object> server = observation.join();
            String name = stage == 0 ? "native_ragdoll_firstperson" : "native_ragdoll_contraption_camera";
            if (!(Boolean) server.get("nativeSession") || ExpandedRagdollRecovery.clientRestored(mc.player)) failures.add(name + ": native session/seat missing");
            if ((stage == 0) != mc.options.getCameraType().isFirstPerson()) failures.add(name + ": wrong actual native camera " + mc.options.getCameraType());
            if (stage == 0 && RagdollRenderCounters.partCount("HEAD") != 0) failures.add(name + ": local head layers rendered in main firstperson pass");
            if (stage == 0 && RagdollRenderCounters.itemCount("aeronautics:aviators_goggles") != 0) failures.add(name + ": local goggles rendered through a native ragdoll accessory path");
            if (stage == 1 && RagdollRenderCounters.partCount("HEAD") == 0) failures.add(name + ": thirdperson head geometry was not observed");
            if (stage == 1 && RagdollRenderCounters.itemCount("aeronautics:aviators_goggles") == 0) failures.add(name + ": thirdperson goggles rendering was not observed");
            if (RagdollRenderCounters.partCount("TORSO") == 0) failures.add(name + ": no native torso layer observed");
            if (CombatRenderCounters.vanillaItems != 0 || CombatRenderCounters.bodyItems != 0) failures.add(name + ": source-player item geometry duplicated native ragdoll");
            if(diagnostic && stage==0) name+="_view"+view;
            String file = String.format("%02d-%s.png", captures.size()+1, name);
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(file)); }
            Map<String, Object> row = new LinkedHashMap<>(); row.put("stage", name); row.put("file", file); row.put("actualCamera", mc.options.getCameraType().name());
            row.put("clientScale",mc.player.getScale()); row.put("cameraDiagnostics",RagdollCameraProbe.capture(mc.getTimer().getGameTimeDeltaPartialTick(false)));
            row.put("detached", mc.gameRenderer.getMainCamera().isDetached()); row.put("clientPosition", mc.player.position().toString());
            row.put("cameraPosition", mc.gameRenderer.getMainCamera().getPosition().toString()); row.put("serverState", server);
            row.put("nativeRagdollGeometry", RagdollRenderCounters.snapshot()); row.put("sourcePlayerRenderCalls", CombatRenderCounters.snapshot());
            if(overlayProbe && stage==0 && view==0) {
                var probe=RagdollBlockOverlayProbe.run(output);report.put("blockOverlayOriginProbe",probe);
                if("FAIL".equals(probe.get("status")) || !Boolean.TRUE.equals(probe.get("stateRestored"))) failures.add("Block overlay probe failed: "+probe);
            }
            captures.add(row); write("RUNNING", ""); RagdollRenderCounters.active = false;
            if(diagnostic && stage==0 && view<2) {
                view++; mc.player.setXRot(view==1?0:-55); mc.player.xRotO=mc.player.getXRot();
                ticks=35; observation=null; RagdollRenderCounters.reset(); RagdollRenderCounters.active=true; return;
            }
            if (++stage == 2) { finish(failures.isEmpty() ? "CAPTURED" : "FAIL", "Review both native ragdoll camera captures"); return; }
            launched = false; countingStarted = false; ticks = 0; observation = null; pending = task(this::prepare);
        }
        void finish(String finalStatus, String finalDetail) {
            if (finishing) return;
            finishing = true; status = finalStatus; detail = finalDetail; RagdollRenderCounters.active = false;
            cleanupAfterServerTick = serverTicks + 15;
        }
        void restoreServer() {
                if (player == null) return;
                boolean grace=ExpandedReactionTransition.begin(player);
                ExpandedRagdollRecovery.release(player); player.stopUsingItem(); player.stopRiding();
                if (inventory != null) player.getInventory().load(inventory);
                var cap = AccessoriesCapability.get(player);
                accessories.forEach((name, saved) -> {
                    var container = cap.getContainers().get(name);
                    for (int i = 0; i < saved.normal.size(); i++) { container.getAccessories().setItem(i, saved.normal.get(i)); container.getCosmeticAccessories().setItem(i, saved.cosmetic.get(i)); container.renderOptions().set(i, saved.flags.get(i)); }
                });
                blocks.forEach((pos,state)->player.serverLevel().setBlockAndUpdate(pos,state));
                if (gameType != null) player.setGameMode(gameType); player.getAbilities().flying = flying; player.onUpdateAbilities();
                player.teleportTo(player.serverLevel(), position.x, position.y, position.z, yaw, pitch); player.setDeltaMovement(Vec3.ZERO); player.inventoryMenu.broadcastChanges();
                cleanupGraceThroughServerTick=serverTicks+ExpandedReactionTransition.GRACE_TICKS+5;
                report.put("cleanupReactionGraceTicks",grace?ExpandedReactionTransition.GRACE_TICKS:0);
                report.put("restoredServerPosition",player.position().toString()); report.put("restoredServerMotion",player.getDeltaMovement().toString());
                report.put("nativeServerSessionReleased", !ExpandedRagdollRecovery.active(player));
                report.put("serverInventoryRestored", inventory == null || inventory.equals(player.getInventory().save(new ListTag())));
                if (ExpandedRagdollRecovery.active(player) || inventory != null && !inventory.equals(player.getInventory().save(new ListTag())))
                    throw new IllegalStateException("Native server ragdoll/inventory restoration failed");
        }
        void write(String state, String message) throws Exception {
            report.put("status", state); report.put("detail", message);
            Files.writeString(output.resolve("ragdoll-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }
}
