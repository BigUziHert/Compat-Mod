package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Real integrated-server movement fixtures, never forced animation pose flags. */
public final class ExpandedMovementSequence {
    private static Session active;
    private static volatile long serverTicks;
    private static final java.util.Queue<DelayedTask> delayedTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private record DelayedTask(long tick, Runnable action, CompletableFuture<Void> future) {}
    private ExpandedMovementSequence() {}
    /** Observes optional native AFK state without resetting timers or animation. */
    private static Map<String, Object> watutObservation(LocalPlayer player) {
        if (!ModList.get().isLoaded("watut")) return Map.of("installed", false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("installed", true);
        try {
            Object manager = Class.forName("com.corosus.watut.WatutMod")
                    .getMethod("getPlayerStatusManagerClient").invoke(null);
            Object local = manager.getClass().getMethod("getStatusLocal").invoke(manager);
            Object rendered = manager.getClass().getMethod("getStatus", net.minecraft.world.entity.player.Player.class)
                    .invoke(manager, player);
            for (var entry : Map.of("local", local, "rendered", rendered).entrySet()) {
                Object status = entry.getValue();
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("idle", status.getClass().getMethod("isIdle").invoke(status));
                fields.put("ticksSinceLastAction", status.getClass().getMethod("getTicksSinceLastAction").invoke(status));
                fields.put("idleThresholdTicks", status.getClass().getMethod("getTicksToMarkPlayerIdleSyncedForClient").invoke(status));
                fields.put("idleParticlePresent", status.getClass().getMethod("getParticleIdle").invoke(status) != null);
                result.put(entry.getKey(), fields);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            result.put("observationError", failure.toString());
        }
        return result;
    }
    public static boolean isActive() { return active != null; }
    public static void register() {
        NeoForge.EVENT_BUS.addListener(ExpandedMovementSequence::tick);
        NeoForge.EVENT_BUS.addListener(ExpandedMovementSequence::beforeFrame);
        NeoForge.EVENT_BUS.addListener(ExpandedMovementSequence::afterFrame);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) -> {
            serverTicks++;
            while (!delayedTasks.isEmpty() && delayedTasks.peek().tick <= serverTicks) {
                DelayedTask task = delayedTasks.remove();
                try { task.action.run(); task.future.complete(null); }
                catch (Throwable failure) { task.future.completeExceptionally(failure); }
            }
        });
    }
    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) {
            try {
                boolean bedScreen = mc.screen instanceof net.minecraft.client.gui.screens.InBedChatScreen
                        && active.stageIndex < active.stages.size()
                        && (active.stages.get(active.stageIndex).action.equals("sleep")
                            || (!active.ready && active.stageIndex > 0
                                && active.stages.get(active.stageIndex - 1).action.equals("sleep")));
                if (!active.finishing && (mc.player != active.player || mc.level == null || (mc.screen != null && !bedScreen)))
                    active.finish("ABORTED", "World/player changed or a screen opened");
                if (active.tick()) active = null;
            } catch (Throwable failure) { active.finish("FAIL", failure.toString()); }
            return;
        }
        if (mc.player == null || mc.level == null || mc.screen != null || CombatRenderCounters.active
                || LiveFirstPersonSequence.isActive()) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-expanded-movement.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                    || !mc.hasSingleplayerServer()) throw new IllegalStateException("Requires disposable .local/client integrated world");
            List<String> request = Files.readAllLines(trigger);
            String label = (request.isEmpty() ? "expanded-movement" : request.getFirst()).trim().replaceAll("[^a-zA-Z0-9._-]", "_");
            Files.delete(trigger);
            String safeLabel = label.isBlank() ? "expanded-movement" : label;
            try { active = new Session(mc, safeLabel, request); }
            catch (Throwable failure) {
                Path output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(safeLabel);
                Files.createDirectories(output);
                Files.writeString(output.resolve("movement-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(
                        Map.of("status", "FAIL", "detail", "Native movement startup failed: " + failure,
                                "startupFailure", true, "vehicle", String.valueOf(mc.player.getVehicle()),
                                "usingItem", mc.player.isUsingItem())));
                throw failure;
            }
        } catch (Throwable failure) { LogUtils.getLogger().error("Expanded movement startup failed", failure); }
    }
    private static void beforeFrame(RenderFrameEvent.Pre event) {
        if (active != null && !active.finishing) {
            CombatRenderCounters.active = true; CombatRenderCounters.reset();
            if (active.necklaces) NecklacePoseProbe.beginFrame();
        }
    }
    private static void afterFrame(RenderFrameEvent.Post event) {
        if (active == null || active.finishing || !active.ready) return;
        try { active.frame(); } catch (Throwable failure) { active.finish("FAIL", failure.toString()); }
    }
    private record Stage(String name, String action, String item, CameraType camera, float pitch,
                         float freeYaw, float freePitch, int ticks) {}
    private record AccessoryState(List<ItemStack> items, List<ItemStack> cosmetic, List<Boolean> renders) {}

    private static final class Session {
        final Minecraft mc;
        final LocalPlayer player;
        final Path output;
        final List<Stage> stages = new ArrayList<>();
        final List<Map<String, Object>> captures = new ArrayList<>();
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<String> violations = new ArrayList<>();
        final Map<KeyMapping, Boolean> keys = new LinkedHashMap<>();
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final Map<String, AccessoryState> accessories = new LinkedHashMap<>();
        final Map<String, BlockState> climbStates = new LinkedHashMap<>();
        final List<Entity> spawned = new ArrayList<>();
        final CameraType originalCamera;
        final boolean originalGui, fpmPresent, originalFpm;
        final boolean originalSmoothCamera, originalInvertMouse;
        final boolean necklaces;
        final boolean downward;
        final float originalYaw, originalPitch;
        final Vec3 originalPosition;
        final KeyMapping freelookKey;
        final KeyMapping shrinkKey;
        final Map<Field, Object> unlockedState = new LinkedHashMap<>();
        final Map<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>, Object> unlockedConfig = new LinkedHashMap<>();
        CompletableFuture<?> pending;
        CompletableFuture<Map<String, Object>> serverObservation;
        ServerPlayer serverPlayer;
        ServerLevel serverLevel;
        ListTag inventory;
        GameType originalGameType;
        Vec3 serverPosition;
        float serverYaw, serverPitch;
        int originalFood;
        float originalSaturation;
        boolean originalFlying;
        long originalDayTime;
        int originalSleepingPercentage;
        BlockPos originalRespawnPosition;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> originalRespawnDimension;
        float originalRespawnAngle;
        boolean originalRespawnForced;
        BlockPos floor;
        Vec3 startPosition;
        Vec3 preparedPosition;
        Map<String, Object> climbFixture = Map.of();
        int stageIndex, ticks, frames, waitTicks, restoringTicks, togglePulseTicks, preparationRecoveries;
        boolean ready, finishing, useStarted, freeTurned, toggleOnSent, sawToggleOff, sleepRequested, sawTargetSurface;
        String finalStatus, finalDetail;
        double maxRise, maxDistance;
        boolean sawClimbing, sawSprint, sawPassenger;
        long startNanos = System.nanoTime();

        Session(Minecraft mc, String label, List<String> request) throws Exception {
            this.mc = mc; player = mc.player;
            necklaces = request.stream().skip(1).map(String::trim).anyMatch("necklaces"::equals);
            downward = request.stream().skip(1).map(String::trim).anyMatch("downward"::equals);
            if (necklaces && downward) throw new IllegalArgumentException("Select necklaces or downward, not both");
            if (player.isPassenger() || player.isUsingItem()) throw new IllegalStateException("Start unmounted and without using an item");
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);
            Files.createDirectories(output);
            originalCamera = mc.options.getCameraType(); originalGui = mc.options.hideGui;
            originalSmoothCamera = mc.options.smoothCamera; originalInvertMouse = mc.options.invertYMouse().get();
            mc.options.smoothCamera = false; mc.options.invertYMouse().set(false);
            originalPosition = player.position(); originalYaw = player.getYRot(); originalPitch = player.getXRot();
            fpmPresent = ModList.get().isLoaded("firstperson"); originalFpm = fpmPresent && Fpm.enabled();
            for (KeyMapping key : List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                    mc.options.keyJump, mc.options.keyShift, mc.options.keySprint, mc.options.keyUse, mc.options.keyAttack)) keys.put(key, key.isDown());
            Class<?> unlocked = Class.forName("com.caleb.unlockedcamera.client.UnlockedCameraClient");
            Field keyField = unlocked.getDeclaredField("FREELOOK_KEY"); keyField.setAccessible(true);
            freelookKey = (KeyMapping) keyField.get(null); keys.put(freelookKey, freelookKey.isDown());
            if (freelookKey.isDown()) throw new IllegalStateException("Release freelook before capture");
            shrinkKey = artifacts.registry.ModKeyMappings.TOGGLE_CHARM_OF_SHRINKING;
            keys.put(shrinkKey, shrinkKey.isDown());
            // Release inherited input before asynchronous world/teleport setup,
            // not only after the first case has already begun.
            keys.keySet().forEach(key -> key.setDown(false));
            for (Field field : unlocked.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !java.lang.reflect.Modifier.isFinal(field.getModifiers())
                        && (field.getType().isPrimitive() || field.getType() == CameraType.class)) {
                    field.setAccessible(true); unlockedState.put(field, field.get(null));
                }
            }
            saveConfig(com.caleb.unlockedcamera.client.ClientConfig.TOGGLE_MOD, true);
            saveConfig(com.caleb.unlockedcamera.client.ClientConfig.ENABLE_UNLOCKED_CAMERA, true);
            saveConfig(com.caleb.unlockedcamera.client.ClientConfig.ENABLE_FREELOOK, true);
            saveConfig(com.caleb.unlockedcamera.client.ClientConfig.DISABLE_VANILLA_THIRD_PERSON, false);
            saveConfig(com.caleb.unlockedcamera.client.ClientConfig.FREELOOK_KEEP_DIRECTION, false);
            if (necklaces) buildNecklaceStages(); else if (downward) buildDownwardStages(); else buildStages();
            for (String option : request.stream().skip(1).map(String::trim).toList()) {
                if (option.startsWith("start=")) {
                    String name = option.substring(6); int index = -1;
                    for (int i = 0; i < stages.size(); i++) if (stages.get(i).name.equals(name)) { index = i; break; }
                    if (index < 0) throw new IllegalArgumentException("Unknown start stage " + name);
                    stages.subList(0, index).clear(); report.put("requestedStartStage", name);
                } else if (option.startsWith("only=")) {
                    List<String> names = List.of(option.substring(5).split(","));
                    stages.removeIf(stage -> !names.contains(stage.name));
                    if (stages.size() != names.size()) throw new IllegalArgumentException("Unknown or duplicate only stage: " + option);
                    report.put("requestedOnlyStages", names);
                } else if (option.equals("neutral_translation")) {
                    saveConfig(dev.caleb.emfattachments.config.CompatConfig.ARM_POSITION_X, 0.0);
                    saveConfig(dev.caleb.emfattachments.config.CompatConfig.ARM_POSITION_Y, 0.0);
                    saveConfig(dev.caleb.emfattachments.config.CompatConfig.ARM_POSITION_Z, 0.0);
                    saveConfig(dev.caleb.emfattachments.config.CompatConfig.LOOK_UP_Z_SHIFT, 0.0);
                    report.put("neutralCombatTranslation", true);
                }
            }
            report.put("label", label); report.put("firstPersonInstalled", fpmPresent);
            report.put("necklaceDiagnostic", necklaces);
            report.put("downwardDiagnostic", downward);
            NecklacePoseProbe.active = necklaces;
            report.put("scope", "Native key-driven movement and camera actions in a temporary integrated-server fixture. No forced climbing/sitting pose flags. All changed blocks, inventory, equipment, input and config are restored.");
            report.put("stageCount", stages.size()); report.put("captures", captures); report.put("violations", violations);
            report.put("fixtureTransitionPolicy", "Wait at least 15 actual server ticks for native pose requests; wait up to 45 if still pending. Notify native impact history and clear telemetry only around fixture displacement, synchronize zero motion, and do not suppress active gameplay reactions.");
            report.put("resourcePacks", mc.getResourcePackRepository().getSelectedIds());
            write("PREPARING", "");
            pending = serverTask(() -> prepareWorld());
        }
        <T> void saveConfig(net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<T> value, T forced) {
            unlockedConfig.put(value, value.get()); value.set(forced);
        }
        void buildNecklaceStages() {
            for (String name : List.of("cross_necklace", "panic_necklace", "shock_pendant", "flame_pendant",
                    "thorn_pendant", "charm_of_sinking", "charm_of_shrinking")) {
                String id = "artifacts:" + name;
                add(name + "_standing", "idle", id, CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 40);
                add(name + "_sprinting", "sprint", id, CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 30);
                add(name + "_crouching", "crouch", id, CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 30);
                add(name + "_standing_after", "idle", id, CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 40);
                add(name + "_firstperson_sprint", "sprint", id, CameraType.FIRST_PERSON, 65, 0, 0, 30);
            }
            // An unrelated renderer is a visual control; the Artifacts-only
            // matrix witness must not claim it exercised the patched model.
            add("aether_golden_pendant_standing_control", "idle", "aether:golden_pendant", CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 40);
            add("aether_golden_pendant_sprinting_control", "sprint", "aether:golden_pendant", CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 30);
        }
        void buildDownwardStages() {
            add("down90_idle", "idle", "", CameraType.FIRST_PERSON, 90, 0, 0, 40);
            add("down90_sprint", "sprint", "", CameraType.FIRST_PERSON, 90, 0, 0, 30);
            add("down90_crouch", "crouch", "", CameraType.FIRST_PERSON, 90, 0, 0, 30);
            add("down90_freelook_center", "idle", "", CameraType.FIRST_PERSON, 0, 0, 90, 40);
            add("down90_freelook_left", "idle", "", CameraType.FIRST_PERSON, 0, -65, 90, 40);
            add("down90_freelook_right", "idle", "", CameraType.FIRST_PERSON, 0, 65, 90, 40);
            add("down90_attack_center", "attack", "simplyswords:diamond_greathammer", CameraType.FIRST_PERSON, 0, 0, 90, 18);
            add("down90_attack_left", "attack", "simplyswords:diamond_greathammer", CameraType.FIRST_PERSON, 0, -65, 90, 18);
            add("down90_attack_right", "attack", "simplyswords:diamond_greathammer", CameraType.FIRST_PERSON, 0, 65, 90, 18);
            add("down90_horse", "horse", "", CameraType.FIRST_PERSON, 90, 0, 0, 40);
            add("down90_seat", "seat", "", CameraType.FIRST_PERSON, 90, 0, 0, 40);
            add("down90_halfsize", "shrink", "", CameraType.FIRST_PERSON, 90, 0, 0, 50);
            add("down90_halfsize_freelook", "shrink", "", CameraType.FIRST_PERSON, 0, 65, 90, 50);
            add("down90_eating", "use", "minecraft:bread", CameraType.FIRST_PERSON, 90, 0, 0, 18);
            add("down90_f5_front_control", "idle", "", CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 40);
            add("down90_f5_return", "idle", "", CameraType.FIRST_PERSON, 90, 0, 0, 40);
            add("down90_swim45_control", "swim", "", CameraType.FIRST_PERSON, 45, 0, 0, 24);
            add("down90_halfsize_swim45_control", "shrink_swim", "", CameraType.FIRST_PERSON, 45, 0, 0, 24);
            add("down90_release_level_control", "release", "", CameraType.FIRST_PERSON, 0, 0, 0, 50);
        }
        void buildStages() {
            add("baseline_firstperson", "idle", "", CameraType.FIRST_PERSON, 65, 0, 0, 40);
            add("walk_firstperson", "walk", "", CameraType.FIRST_PERSON, 55, 0, 0, 30);
            add("sprint_firstperson", "sprint", "", CameraType.FIRST_PERSON, 55, 0, 0, 30);
            add("sprint_thirdperson", "sprint", "", CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 30);
            add("freelook_down", "idle", "", CameraType.FIRST_PERSON, 0, 0, 70, 40);
            add("freelook_left_down", "idle", "", CameraType.FIRST_PERSON, 0, -65, 55, 40);
            add("freelook_right_down", "idle", "", CameraType.FIRST_PERSON, 0, 65, 55, 40);
            add("freelook_up", "idle", "", CameraType.FIRST_PERSON, 0, 0, -65, 40);
            add("freelook_release", "release", "", CameraType.FIRST_PERSON, 0, 0, 0, 50);
            add("freelook_bow", "use", "minecraft:bow", CameraType.FIRST_PERSON, 0, 55, 45, 18);
            add("freelook_food", "use", "minecraft:bread", CameraType.FIRST_PERSON, 0, -55, 45, 18);
            add("freelook_shield", "use", "minecraft:shield", CameraType.FIRST_PERSON, 0, 55, 45, 18);
            add("freelook_attack_down", "attack", "simplyswords:diamond_greathammer", CameraType.FIRST_PERSON, 0, 0, 65, 18);
            add("freelook_attack_left", "attack", "simplyswords:diamond_greathammer", CameraType.FIRST_PERSON, 0, -65, 45, 18);
            add("freelook_attack_right", "attack", "simplyswords:diamond_greathammer", CameraType.FIRST_PERSON, 0, 65, 45, 18);
            add("freelook_attack_release", "release", "", CameraType.FIRST_PERSON, 0, 0, 0, 50);
            add("f5_baseline", "idle", "", CameraType.FIRST_PERSON, 15, 0, 0, 30);
            add("f5_vanilla_back_running", "f5_walk", "", CameraType.THIRD_PERSON_BACK, 15, 0, 0, 30);
            add("f5_unlocked_running", "f5_walk", "", CameraType.THIRD_PERSON_BACK, 15, 0, 0, 30);
            add("f5_front_running", "f5_walk", "", CameraType.THIRD_PERSON_FRONT, 15, 0, 0, 30);
            add("f5_return_firstperson", "f5_walk", "", CameraType.FIRST_PERSON, 55, 0, 0, 30);
            add("horse_firstperson", "horse", "", CameraType.FIRST_PERSON, 55, 0, 0, 40);
            add("horse_thirdperson", "horse", "", CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 40);
            add("create_seat_firstperson", "seat", "", CameraType.FIRST_PERSON, 55, 0, 0, 40);
            add("create_seat_thirdperson", "seat", "", CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 40);
            add("shrinking_artifact_firstperson", "shrink", "", CameraType.FIRST_PERSON, 65, 0, 0, 50);
            add("shrinking_artifact_thirdperson", "shrink", "", CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 50);
            add("shrinking_artifact_freelook", "shrink", "", CameraType.FIRST_PERSON, 0, 55, 60, 50);
            add("shrinking_artifact_toggle_off", "shrink_off", "", CameraType.FIRST_PERSON, 65, 0, 0, 50);
            add("shrinking_artifact_toggle_on", "shrink_on", "", CameraType.FIRST_PERSON, 65, 0, 0, 50);
            add("swim_firstperson", "swim", "", CameraType.FIRST_PERSON, 35, 0, 0, 24);
            add("swim_thirdperson", "swim", "", CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 24);
            add("shrinking_swim_firstperson", "shrink_swim", "", CameraType.FIRST_PERSON, 45, 0, 0, 24);
            add("shrinking_swim_thirdperson", "shrink_swim", "", CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 24);
            add("crawl_firstperson", "crawl", "", CameraType.FIRST_PERSON, 45, 0, 0, 24);
            add("crawl_thirdperson", "crawl", "", CameraType.THIRD_PERSON_FRONT, 10, 0, 0, 24);
            add("shrinking_crawl_firstperson", "shrink_crawl", "", CameraType.FIRST_PERSON, 45, 0, 0, 24);
            add("sleep_firstperson", "sleep", "", CameraType.FIRST_PERSON, 0, 0, 0, 30);
            add("wake_firstperson", "idle", "", CameraType.FIRST_PERSON, 65, 0, 0, 40);
            add("elytra_firstperson", "flight", "", CameraType.FIRST_PERSON, -10, 0, 0, 18);
            add("elytra_thirdperson", "flight", "", CameraType.THIRD_PERSON_FRONT, -10, 0, 0, 18);
            add("wall_without_climbing_item_control", "wall_control", "", CameraType.FIRST_PERSON, 40, 0, 0, 40);
            add("centipede_leggings_wall_firstperson", "wall_item", "alexsmobs:centipede_leggings", CameraType.FIRST_PERSON, 40, 0, 0, 40);
            add("centipede_leggings_wall_thirdperson", "wall_item", "alexsmobs:centipede_leggings", CameraType.THIRD_PERSON_BACK, 5, 0, 0, 40);
            List<String> climbable = new ArrayList<>();
            BuiltInRegistries.BLOCK.getTag(BlockTags.CLIMBABLE).ifPresent(tag -> tag.forEach(holder -> climbable.add(BuiltInRegistries.BLOCK.getKey(holder.value()).toString())));
            climbable.sort(String::compareTo);
            report.put("runtimeClimbableTag", List.copyOf(climbable));
            List<String> hookSurfaces = new ArrayList<>();
            Map<String, String> excludedHooks = new LinkedHashMap<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                try {
                    Class<?> owner = block.getClass().getMethod("isLadder", BlockState.class,
                            net.minecraft.world.level.LevelReader.class, BlockPos.class, net.minecraft.world.entity.LivingEntity.class).getDeclaringClass();
                    String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                    if (!owner.getName().startsWith("net.neoforged.neoforge.common.extensions.") && owner != Block.class && !climbable.contains(id)) {
                        if (block instanceof org.violetmoon.quark.content.building.block.HollowPillarBlock) {
                            // Native Quark requires the eye X/Z inside [1/8,7/8].
                            // Discovery must not depend on the current player's
                            // fractional location left by a preceding fixture.
                            hookSurfaces.add(id);
                            climbStates.put(id, block.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
                        } else if (block instanceof TrapDoorBlock || block instanceof net.mehvahdjukaar.supplementaries.common.block.blocks.AbstractRopeBlock) {
                            hookSurfaces.add(id);
                        } else {
                            BlockState usable = null;
                            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                                if (state.isLadder(mc.level, player.blockPosition(), player)) { usable = state; break; }
                            }
                            if (usable != null) { hookSurfaces.add(id); climbStates.put(id, usable); }
                            else excludedHooks.put(id, owner.getName() + ": no declared block state is climbable in the normal world context; generic forwarding/material-dependent hook, not an intrinsic ladder surface");
                        }
                    }
                } catch (NoSuchMethodException ignored) { }
            }
            hookSurfaces.sort(String::compareTo);
            report.put("nativeIsLadderOverrideCandidates", hookSurfaces);
            report.put("nonClimbableForwardingHooks", excludedHooks);
            for (String id : hookSurfaces) if (!climbable.contains(id)) climbable.add(id);
            report.put("allClimbableSurfaceIds", List.copyOf(climbable));
            report.put("climbableSurfaceDiscovery", "Runtime tag plus intrinsic native hooks; vertical Quark hollow logs use the native centered-eye precondition independent of initial player position.");
            for (String id : climbable) add("climb_" + id.replace(':', '_'), "climb", id, CameraType.FIRST_PERSON, 40, 0, 0, 24);
            for (String id : List.of("minecraft:ladder", "minecraft:vine", "minecraft:scaffolding"))
                add("climb_thirdperson_" + id.replace(':', '_'), "climb", id, CameraType.THIRD_PERSON_BACK, 5, 0, 0, 24);
            add("restored_scale_firstperson", "idle", "", CameraType.FIRST_PERSON, 65, 0, 0, 50);
        }
        void add(String name, String action, String item, CameraType camera, float pitch, float yaw, float freePitch, int duration) {
            stages.add(new Stage(name, action, item, camera, pitch, yaw, freePitch, duration));
        }
        CompletableFuture<Void> serverTask(Runnable action) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            mc.getSingleplayerServer().execute(() -> { try { action.run(); future.complete(null); } catch (Throwable failure) { future.completeExceptionally(failure); } });
            return future;
        }
        CompletableFuture<Void> afterNativePoseDrain(Runnable action) {
            // Sable launch first requests a client pose, then can create its
            // session up to ten server ticks later. Drain that request before
            // the next independent fixture releases/teleports the player.
            CompletableFuture<Void> future = new CompletableFuture<>();
            long deadline = serverTicks + 45;
            delayedTasks.add(new DelayedTask(serverTicks + 15,
                    () -> completeAfterNativePoseDrain(action, future, deadline), new CompletableFuture<>()));
            return future;
        }
        void completeAfterNativePoseDrain(Runnable action, CompletableFuture<Void> future, long deadline) {
            try {
                if (serverPlayer != null && ExpandedRagdollRecovery.pending(serverPlayer)) {
                    if (serverTicks >= deadline) throw new IllegalStateException("Native pose request remained pending after 45 server ticks");
                    delayedTasks.add(new DelayedTask(serverTicks + 5,
                            () -> completeAfterNativePoseDrain(action, future, deadline), new CompletableFuture<>()));
                    return;
                }
                action.run(); future.complete(null);
            } catch (Throwable failure) { future.completeExceptionally(failure); }
        }
        void prepareWorld() {
            serverPlayer = mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID());
            if (serverPlayer == null) throw new IllegalStateException("Server player absent");
            serverLevel = serverPlayer.serverLevel(); serverPosition = serverPlayer.position();
            serverYaw = serverPlayer.getYRot(); serverPitch = serverPlayer.getXRot();
            originalGameType = serverPlayer.gameMode.getGameModeForPlayer();
            originalFlying = serverPlayer.getAbilities().flying;
            originalDayTime = serverLevel.getDayTime();
            originalSleepingPercentage = serverLevel.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
            originalRespawnPosition = serverPlayer.getRespawnPosition(); originalRespawnDimension = serverPlayer.getRespawnDimension();
            originalRespawnAngle = serverPlayer.getRespawnAngle(); originalRespawnForced = serverPlayer.isRespawnForced();
            originalFood = serverPlayer.getFoodData().getFoodLevel(); originalSaturation = serverPlayer.getFoodData().getSaturationLevel();
            inventory = serverPlayer.getInventory().save(new ListTag());
            AccessoriesCapability cap = AccessoriesCapability.get(serverPlayer);
            if (cap == null) throw new IllegalStateException("Server Accessories capability absent");
            cap.getContainers().forEach((name, container) -> {
                List<ItemStack> items = new ArrayList<>(), cosmetics = new ArrayList<>();
                for (int i = 0; i < container.getSize(); i++) { items.add(container.getAccessories().getItem(i).copy()); cosmetics.add(container.getCosmeticAccessories().getItem(i).copy()); }
                accessories.put(name, new AccessoryState(items, cosmetics, new ArrayList<>(container.renderOptions())));
            });
            floor = serverPlayer.blockPosition().offset(48, 8, 48);
            for (int x = -8; x <= 8; x++) for (int z = -32; z <= 8; z++) {
                setBlock(floor.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                for (int y = 1; y <= 20; y++) setBlock(floor.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
            serverPlayer.setGameMode(GameType.SURVIVAL);
            serverPlayer.getAbilities().flying = false; serverPlayer.onUpdateAbilities();
            serverPlayer.getFoodData().setFoodLevel(10);
            prepareStage();
        }
        void setBlock(BlockPos pos, BlockState state) {
            if (!blocks.containsKey(pos)) {
                if (serverLevel.getBlockEntity(pos) != null) throw new IllegalStateException("Fixture would replace block entity at " + pos);
                blocks.put(pos.immutable(), serverLevel.getBlockState(pos));
            }
            serverLevel.setBlock(pos, state, 2);
        }
        void prepareStage() {
            Stage stage = stages.get(stageIndex);
            // This reset is fixture displacement, not gameplay acceleration.
            // Clear native motion history without suppressing the next action.
            ExpandedReactionTransition.displaced(serverPlayer);
            ExpandedRagdollRecovery.release(serverPlayer);
            // Sleep tests use real night below. Return subsequent gameplay
            // captures to daylight; the original world time is restored at end.
            if (!stage.action.equals("sleep")) serverLevel.setDayTime(6000);
            serverPlayer.stopUsingItem(); serverPlayer.stopRiding();
            if (serverPlayer.isSleeping()) serverPlayer.stopSleepInBed(true, true);
            serverPlayer.stopFallFlying();
            spawned.forEach(Entity::discard); spawned.clear();
            serverPlayer.getInventory().clearContent();
            AccessoriesCapability cap = AccessoriesCapability.get(serverPlayer);
            cap.getContainers().values().forEach(container -> { container.getAccessories().clearContent(); container.getCosmeticAccessories().clearContent(); });
            equipAccessory(serverPlayer, "aeronautics:aviators_goggles");
            if (necklaces) {
                equipAccessory(serverPlayer, stage.item);
                serverPlayer.getFoodData().setFoodLevel(20);
            }
            if (stage.action.startsWith("shrink")) equipAccessory(serverPlayer, "artifacts:charm_of_shrinking");
            if (stage.action.equals("wall_item")) serverPlayer.setItemSlot(EquipmentSlot.LEGS, item(stage.item));
            if (stage.action.equals("use") || stage.action.equals("attack")) {
                serverPlayer.setItemInHand(InteractionHand.MAIN_HAND, item(stage.item));
                serverPlayer.getInventory().setItem(9, Items.ARROW.getDefaultInstance());
                serverPlayer.getFoodData().setFoodLevel(10);
            }
            // Clear the previous real water/tunnel/ladder fixture between cases.
            for (int x = -4; x <= 4; x++) for (int h = 1; h <= 18; h++) for (int z = -22; z <= 6; z++) setBlock(floor.offset(x, h, z), Blocks.AIR.defaultBlockState());
            setBlock(floor, Blocks.STONE.defaultBlockState());
            climbFixture = Map.of();
            double x = floor.getX() + .5, y = floor.getY() + 1.01, z = floor.getZ() + 3.5;
            if (stage.action.startsWith("wall_")) {
                // Ordinary, untagged stone: the item's real EntityTick handler
                // supplies upward motion on horizontal collision. No ladder,
                // tagged climbable block, jump input or forced climbing flag.
                for (int wx = -2; wx <= 2; wx++) for (int h = 1; h <= 17; h++)
                    setBlock(floor.offset(wx, h, -1), Blocks.STONE.defaultBlockState());
                z = floor.getZ() + .45;
            }
            if (stage.action.equals("climb")) {
                Vec3 spawn = prepareClimb(stage.item);
                x = spawn.x; y = spawn.y; z = spawn.z;
            }
            if (stage.action.endsWith("swim")) {
                for (int sy = 1; sy <= 6; sy++) {
                    for (int sz = -21; sz <= 5; sz++) { setBlock(floor.offset(-3, sy, sz), Blocks.STONE.defaultBlockState()); setBlock(floor.offset(3, sy, sz), Blocks.STONE.defaultBlockState()); }
                    for (int sx = -3; sx <= 3; sx++) { setBlock(floor.offset(sx, sy, -21), Blocks.STONE.defaultBlockState()); setBlock(floor.offset(sx, sy, 5), Blocks.STONE.defaultBlockState()); }
                }
                for (int sx = -2; sx <= 2; sx++) for (int sz = -20; sz <= 4; sz++) for (int sy = 1; sy <= 5; sy++)
                    setBlock(floor.offset(sx, sy, sz), Blocks.WATER.defaultBlockState());
                y = floor.getY() + 3;
            }
            if (stage.action.endsWith("crawl")) {
                // A real low trapdoor ceiling makes vanilla updatePlayerPose select
                // crawling. The shrinking variant uses an additional low collision
                // slab so a half-height standing body cannot fit either.
                for (int sz = -20; sz <= 4; sz++) {
                    if (stage.action.startsWith("shrink")) {
                        setBlock(floor.offset(0, 1, sz), Blocks.STONE_SLAB.defaultBlockState());
                        y = floor.getY() + 1.51;
                        setBlock(floor.offset(0, 2, sz), Blocks.OAK_TRAPDOOR.defaultBlockState()
                                .setValue(BlockStateProperties.HALF, Half.BOTTOM).setValue(BlockStateProperties.OPEN, false));
                    } else setBlock(floor.offset(0, 1, sz), Blocks.OAK_TRAPDOOR.defaultBlockState()
                            .setValue(BlockStateProperties.HALF, Half.TOP).setValue(BlockStateProperties.OPEN, false));
                }
            }
            if (stage.action.equals("flight")) {
                serverPlayer.setItemSlot(EquipmentSlot.CHEST, Items.ELYTRA.getDefaultInstance());
                y = floor.getY() + 18;
            }
            preparedPosition = new Vec3(x, y, z);
            serverPlayer.teleportTo(serverLevel, x, y, z, 180, stage.pitch);
            serverPlayer.setDeltaMovement(Vec3.ZERO); serverPlayer.fallDistance = 0;
            serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(serverPlayer));
            ExpandedReactionTransition.displaced(serverPlayer);
            if (stage.action.equals("horse")) {
                Horse horse = EntityType.HORSE.create(serverLevel);
                if (horse == null) throw new IllegalStateException("Horse creation failed");
                horse.moveTo(x, y, z, 180, 0); horse.setTamed(true); horse.setNoAi(true);
                horse.equipSaddle(Items.SADDLE.getDefaultInstance(), SoundSource.PLAYERS);
                serverLevel.addFreshEntity(horse); spawned.add(horse);
                if (!serverPlayer.startRiding(horse, true)) throw new IllegalStateException("Native horse mount failed");
            } else if (stage.action.equals("seat")) {
                BlockPos seat = floor.offset(0, 1, 3);
                setBlock(seat, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:red_seat")).defaultBlockState());
                com.simibubi.create.content.contraptions.actors.seat.SeatBlock.sitDown(serverLevel, seat, serverPlayer);
                if (serverPlayer.getVehicle() == null) throw new IllegalStateException("Native Create seat failed");
                spawned.add(serverPlayer.getVehicle());
            } else if (stage.action.equals("sleep")) {
                BlockPos bed = floor.offset(0, 1, 3);
                setBlock(bed, Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH).setValue(BlockStateProperties.BED_PART, BedPart.FOOT));
                setBlock(bed.south(), Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH).setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
                serverLevel.setDayTime(18000);
                serverLevel.getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(101, mc.getSingleplayerServer());
                // Wait for a normal world tick to update isDay before the native
                // sleep attempt; changing dayTime does not update cached daylight.
            }
            serverPlayer.inventoryMenu.broadcastChanges();
        }
        Vec3 prepareClimb(String id) {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
            BlockState state = climbState(climbStates.getOrDefault(id, block.defaultBlockState()));
            double y = floor.getY() + 1.01;
            String construction = "Vertical native column with a solid backing wall";
            if (id.equals("biomesoplenty:stringy_cobweb")) {
                // This is a diagonal strand, not a vertical vine. Both endpoints
                // need solid supports and every intermediate segment needs its
                // diagonal predecessor/successor to survive normal neighbor updates.
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
                for (int h = 1; h <= 7; h++) {
                    BlockPos pos = floor.offset(0, h, 1 - h);
                    setBlock(pos.north(), Blocks.STONE.defaultBlockState());
                    setBlock(pos, state);
                }
                setBlock(floor.offset(0, 8, -6), Blocks.STONE.defaultBlockState());
                construction = "Native diagonal NORTH cobweb chain, supported endpoints and backing steps; forward input only";
            } else {
                for (int h = 1; h <= 17; h++) setBlock(floor.offset(0, h, -1), Blocks.STONE.defaultBlockState());
                if (block instanceof net.minecraft.world.level.block.GrowingPlantBlock
                        && (id.startsWith("deep_aether:golden_vines") || id.startsWith("deeperdarker:sculk_tendrils")
                            || id.startsWith("minecraft:twisting_vines") || id.startsWith("biomesoplenty:flesh_tendons"))) {
                    try {
                        Field directionField = net.minecraft.world.level.block.GrowingPlantBlock.class.getDeclaredField("growthDirection");
                        directionField.setAccessible(true);
                        Direction direction = (Direction) directionField.get(block);
                        Block head = (Block) plantMethod(block, "getHeadBlock");
                        Block body = (Block) plantMethod(block, "getBodyBlock");
                        if (id.startsWith("deep_aether:golden_vines")) {
                            setBlock(floor.below(), Blocks.STONE.defaultBlockState());
                            setBlock(floor, Blocks.SAND.defaultBlockState());
                        }
                        // A tip above/below its body remains the exact registered
                        // head ID. Stacking tips silently converts them to bodies.
                        if (direction == Direction.DOWN) {
                            BlockState ceiling = id.startsWith("biomesoplenty:flesh_tendons")
                                    ? BuiltInRegistries.BLOCK.getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                                            ResourceLocation.parse("biomesoplenty:flesh")))
                                        .flatMap(tag -> tag.stream().filter(holder -> holder.value().defaultBlockState().isSolidRender(serverLevel, floor.above(4))).findFirst())
                                        .orElseThrow(() -> new IllegalStateException("No native flesh-tendon ceiling support")).value().defaultBlockState()
                                    : Blocks.STONE.defaultBlockState();
                            setBlock(floor.above(4), ceiling);
                        }
                        for (int h = 1; h <= 3; h++) {
                            boolean tip = direction == Direction.UP ? h == 3 : h == 1;
                            BlockState plantState = climbState((tip ? head : body).defaultBlockState());
                            if (plantState.hasProperty(BlockStateProperties.AGE_25)) plantState = plantState.setValue(BlockStateProperties.AGE_25, 25);
                            setBlock(floor.above(h), plantState);
                        }
                        construction = "Three-block natural " + direction + " head/body chain with native soil/ceiling support";
                    } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot inspect native growing plant " + id, failure); }
                } else if (id.equals("farmersdelight:tomatoes_on_rope") || id.equals("supplementaries:rope_tomatoes")) {
                    setBlock(floor, Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7));
                    if (state.hasProperty(vectorwing.farmersdelight.common.block.TomatoBlock.ROPELOGGED))
                        state = state.setValue(vectorwing.farmersdelight.common.block.TomatoBlock.ROPELOGGED, true);
                    state = state.setValue(vectorwing.farmersdelight.common.block.TomatoBlock.VINE_AGE, 3);
                    // The basal crop can itself contain rope: vanilla crop
                    // support accepts farmland, higher crops accept TomatoBlock.
                    // This keeps the target surface at the player's real feet.
                    for (int h = 1; h <= 3; h++) setBlock(floor.above(h), state);
                    construction = "Three mature native rope-crop sections rooted on farmland";
                } else if (id.equals("quark:cut_vine")) {
                    setBlock(floor.above(), state);
                    for (int h = 2; h <= 17; h++) setBlock(floor.above(h), Blocks.VINE.defaultBlockState().setValue(BlockStateProperties.NORTH, true));
                    construction = "Cut vine below native vanilla vine with matching NORTH attachment";
                } else if (id.endsWith(":pulley_magnet") || id.endsWith(":chain_pulley_magnet")) {
                    String ropeId = id.startsWith("bits_n_bobs:") ? "bits_n_bobs:chain_rope" : "create:rope";
                    BlockState rope = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(ropeId)).defaultBlockState();
                    setBlock(floor.above(), state);
                    for (int h = 2; h <= 17; h++) setBlock(floor.above(h), rope);
                    setBlock(floor.above(18), Blocks.STONE.defaultBlockState());
                    // The pulley magnet is the terminal plate, never repeated as
                    // a stack of obstructing floors. Start above its native plate.
                    var shape = state.getCollisionShape(serverLevel, floor.above());
                    y = floor.getY() + 1.01 + (shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y));
                    construction = "Single native terminal magnet with matching pulley rope above";
                } else {
                    for (int h = 1; h <= 17; h++) {
                        BlockState actual = block instanceof TrapDoorBlock && h % 2 == 1
                                ? Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH) : state;
                        setBlock(floor.above(h), actual);
                    }
                    BlockState anchor = id.equals("twilightforest:root_strand")
                            ? BuiltInRegistries.BLOCK.getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                                    ResourceLocation.parse("twilightforest:plants_hang_on")))
                                .flatMap(tag -> tag.stream().filter(holder -> holder.value().defaultBlockState().isSolidRender(serverLevel, floor.above(18))).findFirst())
                                .orElseThrow(() -> new IllegalStateException("No native root support block")).value().defaultBlockState()
                            : Blocks.STONE.defaultBlockState();
                    setBlock(floor.above(18), anchor);
                }
            }
            // A standard player centered at z=.45 intersects a 3/16 ladder
            // panel, and a centered rope can be wider still. Ask the native
            // collision system for a clear approach, preserving hollow-log
            // cavities rather than approximating their bounds as a solid cube.
            double x = floor.getX() + .5, z = floor.getZ() + .45;
            boolean clear = false;
            for (double offset : new double[]{.45, .55, .65, .75, .85, .95}) {
                double candidateZ = floor.getZ() + offset;
                var box = EntityType.PLAYER.getDimensions().makeBoundingBox(x, y, candidateZ);
                if (serverLevel.noCollision(serverPlayer, box)) { z = candidateZ; clear = true; break; }
            }
            if (!clear && !(block instanceof ScaffoldingBlock)) throw new IllegalStateException("No collision-free native approach for " + id);
            climbFixture = Map.of("construction", construction, "spawn", List.of(x, y, z),
                    "collisionFreeApproach", clear, "targetBlockState", state.toString());
            return new Vec3(x, y, z);
        }
        static Object plantMethod(Block block, String name) throws ReflectiveOperationException {
            for (Class<?> type = block.getClass(); type != null; type = type.getSuperclass()) {
                try { var method = type.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(block); }
                catch (NoSuchMethodException ignored) { }
            }
            throw new NoSuchMethodException(name);
        }
        static BlockState climbState(BlockState state) {
            if (state.getBlock() instanceof TrapDoorBlock) state = state.setValue(BlockStateProperties.OPEN, true);
            if (state.getBlock() instanceof net.mehvahdjukaar.supplementaries.common.block.blocks.AbstractRopeBlock rope) {
                state = rope.setConnection(Direction.UP, state, true);
                state = rope.setConnection(Direction.DOWN, state, true);
            }
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
            if (state.hasProperty(BlockStateProperties.FACING)) state = state.setValue(BlockStateProperties.FACING, Direction.SOUTH);
            if (state.hasProperty(BlockStateProperties.NORTH)) state = state.setValue(BlockStateProperties.NORTH, true);
            if (state.hasProperty(ScaffoldingBlock.DISTANCE)) state = state.setValue(ScaffoldingBlock.DISTANCE, 0);
            if (state.hasProperty(BlockStateProperties.WATERLOGGED)) state = state.setValue(BlockStateProperties.WATERLOGGED, false);
            return state;
        }
        static ItemStack item(String id) {
            ItemStack stack = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();
            if (stack.isEmpty()) throw new IllegalStateException("Unknown item " + id); return stack;
        }
        static void equipAccessory(ServerPlayer player, String id) {
            ItemStack stack = item(id); AccessoriesCapability cap = AccessoriesCapability.get(player);
            var slots = AccessoriesAPI.getStackSlotTypes(player, stack).stream()
                    .filter(slot -> !io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(slot.name()))
                    .filter(slot -> cap.getContainer(slot) != null && cap.getContainer(slot).getSize() > 0)
                    .sorted(java.util.Comparator.comparing(slot -> slot.name())).toList();
            if (slots.isEmpty()) throw new IllegalStateException("No native accessory slot for " + id);
            AccessoriesContainer container = cap.getContainer(slots.getFirst());
            container.getAccessories().setItem(0, stack);
            if (!container.renderOptions().isEmpty()) container.renderOptions().set(0, true);
        }
        boolean tick() throws Exception {
            if (finishing) {
                if (pending != null && !pending.isDone()) return false;
                if (pending != null) {
                    try { pending.join(); } catch (Throwable failure) { finalStatus = "FAIL"; finalDetail += "; server restore failed: " + failure; }
                    pending = null; restoreClient();
                }
                if (++restoringTicks < 12) return false;
                report.put("restoredClientScale", player.getScale());
                report.put("restoredCamera", mc.options.getCameraType().name());
                report.put("elapsedSeconds", (System.nanoTime() - startNanos) / 1e9);
                write(finalStatus, finalDetail); return true;
            }
            if (!ready) {
                if (!pending.isDone()) return false;
                pending.join();
                Stage stage = stages.get(stageIndex);
                if (++waitTicks < (stage.action.equals("flight") ? 4 : 12)) return false;
                if (player.getVehicle() != null && BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).getNamespace().equals("sable_player_ragdoll")) {
                    if (++preparationRecoveries > 2) throw new IllegalStateException("Native ragdoll persisted during independent fixture preparation");
                    report.put("nativePreparationRecoveries", ((Number) report.getOrDefault("nativePreparationRecoveries", 0)).intValue() + 1);
                    waitTicks = 0;
                    pending = afterNativePoseDrain(this::prepareStage);
                    return false;
                }
                if (stage.action.equals("sleep") && !sleepRequested) {
                    sleepRequested = true; waitTicks = 0;
                    pending = serverTask(() -> {
                        var result = serverPlayer.startSleepInBed(floor.offset(0, 1, 3));
                        if (!serverPlayer.isSleeping()) throw new IllegalStateException("Native bed sleep rejected: " + result.left().map(Enum::name).orElse("unknown"));
                    });
                    return false;
                }
                if (stage.action.equals("horse") || stage.action.equals("seat")) { if (!player.isPassenger()) { if (waitTicks < 100) return false; throw new IllegalStateException("Mount packet not received"); } }
                else if (!stage.action.equals("flight") && !stage.action.equals("sleep") && !stage.action.endsWith("swim")
                        && player.position().distanceTo(preparedPosition) > 2) {
                    if (waitTicks < 100) return false; throw new IllegalStateException("Teleport did not synchronize");
                }
                enterClientStage(stage); ready = true; startPosition = player.position();
            }
            Stage stage = stages.get(stageIndex);
            keys.keySet().forEach(key -> key.setDown(false));
            boolean moving = stage.action.equals("walk") || stage.action.equals("sprint") || stage.action.equals("f5_walk") || stage.action.equals("climb") || stage.action.startsWith("wall_") || stage.action.endsWith("swim") || stage.action.endsWith("crawl");
            mc.options.keyUp.setDown(moving);
            mc.options.keySprint.setDown(stage.action.equals("sprint") || stage.action.endsWith("swim"));
            mc.options.keyShift.setDown(stage.action.equals("crouch"));
            // Farmland is 15/16 high: a native jump enters the rope-crop cell
            // above it. Release jump immediately; subsequent rise is climbing.
            boolean tomatoEntry = stage.item.equals("farmersdelight:tomatoes_on_rope") || stage.item.equals("supplementaries:rope_tomatoes");
            mc.options.keyJump.setDown(stage.action.equals("climb") && (stage.item.contains("scaffolding") || tomatoEntry && ticks < 2));
            boolean freelook = stage.freeYaw != 0 || stage.freePitch != 0;
            freelookKey.setDown(freelook);
            if (freelook && !freeTurned) {
                double sensitivity = mc.options.sensitivity().get() * .6 + .2;
                double factor = sensitivity * sensitivity * sensitivity * 8 * .15;
                if (!com.caleb.unlockedcamera.client.UnlockedCameraClient.freelookMouseTurn(stage.freeYaw / factor, stage.freePitch / factor, 1.0 / 60))
                    throw new IllegalStateException("Native freelook input was not consumed");
                freeTurned = true;
            }
            if (stage.action.equals("use")) {
                mc.options.keyUse.setDown(true);
                if (!useStarted) {
                    if (!BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString().equals(stage.item)) return false;
                    mc.gameMode.useItem(player, InteractionHand.MAIN_HAND); useStarted = true;
                }
            }
            if (stage.action.equals("attack") && !useStarted) {
                if (!ExpandedCombatActions.ready(player, item(stage.item), pending, waitTicks)) return false;
                ExpandedCombatActions.startAttack(mc, ExpandedCombatActions.comboFor(player, "bettercombat:two_handed_slam"));
                useStarted = true;
            }
            if (stage.action.equals("flight") && !useStarted && !player.onGround() && player.getDeltaMovement().y < 0) {
                if (!player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) return false;
                if (player.tryToStartFallFlying()) {
                    player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    useStarted = true;
                }
            }
            if (stage.action.equals("shrink_on")) {
                sawToggleOff |= Math.abs(player.getScale() - 1) < .01;
                if (!toggleOnSent && ticks >= 15) { toggleShrink(); toggleOnSent = true; }
            }
            shrinkKey.setDown(togglePulseTicks > 0);
            if (togglePulseTicks > 0) togglePulseTicks--;
            ticks++;
            sawClimbing |= player.onClimbable(); sawSprint |= player.isSprinting(); sawPassenger |= player.isPassenger();
            if (stage.action.equals("climb") && player.onClimbable()
                    && BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(player.blockPosition()).getBlock()).toString().equals(stage.item)) sawTargetSurface = true;
            maxRise = Math.max(maxRise, player.getY() - startPosition.y);
            maxDistance = Math.max(maxDistance, player.position().subtract(startPosition).horizontalDistance());
            return false;
        }
        void enterClientStage(Stage stage) throws Exception {
            mc.options.hideGui = false; if (fpmPresent) Fpm.enabled(true);
            keys.keySet().forEach(key -> key.setDown(false)); player.stopUsingItem();
            if (stage.action.equals("f5_walk")) KeyMapping.click(mc.options.keyTogglePerspective.getKey());
            else mc.options.setCameraType(stage.camera);
            player.setYRot(180); player.yRotO = 180; player.setXRot(stage.pitch); player.xRotO = stage.pitch;
            if (stage.action.equals("shrink_off")) toggleShrink();
            if (stage.action.equals("shrink_on")) { toggleShrink(); /* Off then on through native packets, separated by normal client ticks below. */ }
        }
        void toggleShrink() throws Exception {
            // Artifacts listens for isDown rising edges, not consumeClick.
            // Its own registered client handler sends the actual toggle packet.
            togglePulseTicks = 2;
        }
        void frame() throws Exception {
            frames++;
            Stage stage = stages.get(stageIndex);
            boolean shortPlant = stage.action.equals("climb") && (stage.item.startsWith("deep_aether:golden_vines")
                    || stage.item.startsWith("deeperdarker:sculk_tendrils") || stage.item.startsWith("minecraft:twisting_vines")
                    || stage.item.startsWith("biomesoplenty:flesh_tendons")
                    || stage.item.equals("farmersdelight:tomatoes_on_rope") || stage.item.equals("supplementaries:rope_tomatoes"));
            boolean activeTarget = shortPlant && ticks >= 8 && maxRise > .25 && player.onClimbable()
                    && BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(player.blockPosition()).getBlock()).toString().equals(stage.item);
            // Tips and crops have finite native support heights. Capture while
            // actually traversing the target, before stepping off the top.
            if ((serverObservation == null && !activeTarget && ticks < stage.ticks) || frames < 8) return;
            if (serverObservation == null) {
                serverObservation = new CompletableFuture<>();
                var observation = serverObservation;
                mc.getSingleplayerServer().execute(() -> {
                    try {
                        Map<String, Object> nativeState = new LinkedHashMap<>(Map.of("scale", serverPlayer.getScale(), "climbing", serverPlayer.onClimbable(),
                            "swimming", serverPlayer.isSwimming(), "sleeping", serverPlayer.isSleeping(), "fallFlying", serverPlayer.isFallFlying(),
                            "passenger", serverPlayer.isPassenger(), "pose", serverPlayer.getPose().name(), "position", serverPlayer.position().toString(),
                            "touchingStoneWall", serverLevel.getBlockState(serverPlayer.blockPosition().north()).is(Blocks.STONE), "equippedLegs", BuiltInRegistries.ITEM.getKey(serverPlayer.getItemBySlot(EquipmentSlot.LEGS).getItem()).toString()));
                        nativeState.put("sprinting", serverPlayer.isSprinting());
                        nativeState.put("crouching", serverPlayer.isCrouching());
                        if (necklaces) {
                            nativeState.put("necklaceEquipped", AccessoriesCapability.get(serverPlayer).getContainers().values().stream()
                                    .anyMatch(c -> { for (int i = 0; i < c.getSize(); i++) if (BuiltInRegistries.ITEM.getKey(c.getAccessories().getItem(i).getItem()).toString().equals(stage.item)) return true; return false; }));
                        }
                        observation.complete(nativeState);
                    }
                    catch (Throwable failure) { observation.completeExceptionally(failure); }
                });
                return;
            }
            if (!serverObservation.isDone()) return;
            Map<String, Object> serverState = serverObservation.join();
            if (stage.action.equals("climb") && (!sawClimbing || maxRise < .25)) violations.add(stage.name + ": actual climb not entered (onClimbable=" + sawClimbing + ", rise=" + maxRise + ")");
            if (stage.action.equals("climb") && !sawTargetSurface) violations.add(stage.name + ": player never traversed the target climbable surface");
            if (shortPlant && !player.onClimbable()) violations.add(stage.name + ": short surface was no longer being climbed at capture");
            if (stage.action.equals("wall_control") && maxRise > .25) violations.add(stage.name + ": control climbed without the native climbing item");
            if (stage.action.equals("wall_item")) {
                // ServerPlayer accepts native client movement packets rather
                // than running LocalPlayer collision travel; its transient
                // horizontalCollision flag is not the authoritative contact.
                if (maxRise < .5 || !player.horizontalCollision || !(Boolean) serverState.get("touchingStoneWall"))
                    violations.add(stage.name + ": native equipped wall climb did not move upward against the wall");
                if (!BuiltInRegistries.ITEM.getKey(player.getItemBySlot(EquipmentSlot.LEGS).getItem()).toString().equals(stage.item)
                        || !stage.item.equals(serverState.get("equippedLegs"))) violations.add(stage.name + ": native climbing leggings not equipped on both sides");
                if (sawClimbing || mc.level.getBlockState(player.blockPosition()).is(BlockTags.CLIMBABLE))
                    violations.add(stage.name + ": fixture used a tagged climbable surface instead of item-only wall climbing");
            }
            if ((stage.action.equals("walk") || stage.action.equals("sprint") || stage.action.equals("f5_walk")) && maxDistance < .4) violations.add(stage.name + ": movement not entered");
            if (stage.action.equals("sprint") && !sawSprint) violations.add(stage.name + ": native sprint not entered");
            if (stage.action.equals("crouch") && (!player.isCrouching() || !(Boolean) serverState.get("crouching")))
                violations.add(stage.name + ": actual crouch not active on both sides at capture");
            if (stage.action.endsWith("swim") && (!player.isSwimming() || !(Boolean) serverState.get("swimming"))) violations.add(stage.name + ": native swimming not entered on both sides");
            if (stage.action.endsWith("crawl") && (!player.isVisuallyCrawling() || player.isInWater())) violations.add(stage.name + ": real dry crawl not entered");
            if (stage.action.equals("sleep") && (!player.isSleeping() || !(Boolean) serverState.get("sleeping"))) violations.add(stage.name + ": native bed sleep missing");
            if (stage.action.equals("flight") && (!player.isFallFlying() || !(Boolean) serverState.get("fallFlying"))) violations.add(stage.name + ": native elytra flight missing");
            if (stage.action.equals("shrink_on") && !sawToggleOff) violations.add(stage.name + ": native toggle-off transition was not observed");
            if ((stage.action.equals("horse") || stage.action.equals("seat")) && !sawPassenger) violations.add(stage.name + ": passenger missing");
            double expectedScale = (stage.action.startsWith("shrink") && !stage.action.equals("shrink_off"))
                    || (necklaces && stage.item.equals("artifacts:charm_of_shrinking")) ? .5 : 1;
            if (Math.abs(player.getScale() - expectedScale) > .01) violations.add(stage.name + ": scale " + player.getScale() + " != " + expectedScale);
            if (Math.abs(((Number) serverState.get("scale")).doubleValue() - expectedScale) > .01) violations.add(stage.name + ": server scale disagrees");
            if (mc.options.getCameraType() != stage.camera) violations.add(stage.name + ": unexpected native camera " + mc.options.getCameraType());
            var camera = mc.gameRenderer.getMainCamera();
            float yawDelta = Mth.wrapDegrees(camera.getYRot() - player.getYRot());
            float pitchDelta = camera.getXRot() - player.getXRot();
            if ((stage.freeYaw != 0 || stage.freePitch != 0) && (Math.abs(yawDelta - stage.freeYaw) > 5 || Math.abs(pitchDelta - stage.freePitch) > 5)) violations.add(stage.name + ": native freelook angle mismatch");
            if (stage.action.equals("use") && (!player.isUsingItem() || player.getTicksUsingItem() < 10)) violations.add(stage.name + ": native item use not sustained");
            if (stage.action.equals("attack") && !ExpandedCombatActions.attackActive(player)) violations.add(stage.name + ": native attack was not active at capture");
            String file = String.format(Locale.ROOT, "%03d-%s.png", stageIndex + 1, stage.name);
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(file)); }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name); row.put("action", stage.action); row.put("itemOrSurface", stage.item); row.put("screenshot", file);
            row.put("camera", mc.options.getCameraType().name()); row.put("detached", camera.isDetached()); row.put("playerYaw", player.getYRot()); row.put("playerPitch", player.getXRot());
            row.put("cameraYaw", camera.getYRot()); row.put("cameraPitch", camera.getXRot()); row.put("scale", player.getScale()); row.put("eyeHeight", player.getEyeHeight());
            row.put("clientPosition", player.position().toString()); row.put("cameraPosition", camera.getPosition().toString());
            row.put("nativeOnGround", player.onGround()); row.put("clientBlockBelow", mc.level.getBlockState(player.blockPosition().below()).toString());
            row.put("clientFeetBlock", mc.level.getBlockState(player.blockPosition()).toString());
            row.put("clientHeadBlock", mc.level.getBlockState(BlockPos.containing(player.getEyePosition())).toString());
            row.put("nativePose", player.getPose().name()); row.put("sawClimbing", sawClimbing); row.put("rise", maxRise); row.put("distance", maxDistance); row.put("sawSprint", sawSprint);
            row.put("sawTargetClimbableSurface", sawTargetSurface);
            if (stage.action.equals("climb")) row.put("nativeClimbFixture", climbFixture);
            if (stage.action.equals("climb")) row.put("nativeClimbingAtCapture", player.onClimbable());
            row.put("horizontalCollision", player.horizontalCollision);
            row.put("equippedLegs", BuiltInRegistries.ITEM.getKey(player.getItemBySlot(EquipmentSlot.LEGS).getItem()).toString());
            row.put("vehicle", player.getVehicle() == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString());
            row.put("usingItem", player.isUsingItem()); row.put("useTicks", player.getTicksUsingItem()); row.put("renderCounts", CombatRenderCounters.snapshot());
            row.put("serverState", serverState);
            if (downward && fpmPresent) {
                Vec3 offset = Fpm.offset();
                row.put("nativeFirstPersonOffset", List.of(offset.x, offset.y, offset.z));
            }
            Map<String, Object> actualAccessories = new LinkedHashMap<>();
            AccessoriesCapability.get(player).getContainers().forEach((name, container) -> {
                for (int i = 0; i < container.getSize(); i++) {
                    ItemStack stack = container.getAccessories().getItem(i);
                    if (!stack.isEmpty()) actualAccessories.put(name + ":" + i, Map.of("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                            "disabledByArtifactToggle", stack.has(artifacts.registry.ModDataComponents.DISABLED_BY_TOGGLE.get())));
                }
            });
            row.put("actualEquippedAccessories", actualAccessories);
            if (necklaces) {
                row.put("watutObservation", watutObservation(player));
                List<Map<String, Object>> necklaceDraws = NecklacePoseProbe.snapshot();
                row.put("necklaceDraws", necklaceDraws);
                row.put("nativeSprintingAtCapture", player.isSprinting());
                row.put("nativeCrouchingAtCapture", player.isCrouching());
                if (!(Boolean) serverState.get("necklaceEquipped")) violations.add(stage.name + ": necklace not equipped on server");
                if (actualAccessories.values().stream().noneMatch(value -> ((Map<?, ?>) value).get("item").equals(stage.item)))
                    violations.add(stage.name + ": necklace not synchronized to client");
                if (stage.action.equals("sprint") && (!player.isSprinting() || !(Boolean) serverState.get("sprinting")))
                    violations.add(stage.name + ": actual sprint not active on both sides at capture");
                if (stage.action.equals("crouch") && (!player.isCrouching() || !(Boolean) serverState.get("crouching")))
                    violations.add(stage.name + ": actual crouch not active on both sides at capture");
                if (stage.item.startsWith("artifacts:")) {
                    if (necklaceDraws.isEmpty()) violations.add(stage.name + ": no actual necklace draw matrix observed");
                    for (var draw : necklaceDraws) {
                        if (((Number) draw.get("transforms")).intValue() < 1 || ((Number) draw.get("expectedError")).doubleValue() > .00001
                                || !Boolean.TRUE.equals(draw.get("sourceCopyMatches")) || !Boolean.TRUE.equals(draw.get("partStateRestored")))
                            violations.add(stage.name + ": necklace draw differs from copied torso anchor or mutated pose");
                    }
                } else if (!necklaceDraws.isEmpty()) violations.add(stage.name + ": unrelated pendant unexpectedly entered Artifacts probe");
            }
            if (fpmPresent && mc.options.getCameraType().isFirstPerson() && player.isVisuallySwimming()) {
                double nativeY = player.xRotO > 0 && player.isUnderWater() ? .6f * Math.sin(Math.toRadians(player.xRotO)) : .01f * -Math.sin(Math.toRadians(player.xRotO));
                double scaledY = Fpm.offset().y;
                row.put("nativePreScaleY", nativeY); row.put("actualScaledY", scaledY); row.put("expectedScaledY", nativeY * player.getScale());
                row.put("externalOffsetHandlers", Fpm.handlers());
                if (Fpm.handlers() == 0 && CombatRenderCounters.bodyPasses > 0 && Math.abs(scaledY - nativeY * player.getScale()) > 1e-5) violations.add(stage.name + ": native vertical offset did not scale");
            }
            captures.add(row); write("RUNNING", "");
            keys.keySet().forEach(key -> key.setDown(false)); player.stopUsingItem(); ready = false;
            if (++stageIndex == stages.size()) { finish(violations.isEmpty() ? "CAPTURED" : "FAIL", "Review actual movement screenshots; violations retain unsupported or failed states"); return; }
            ticks = frames = waitTicks = togglePulseTicks = preparationRecoveries = 0; maxRise = maxDistance = 0;
            sawClimbing = sawSprint = sawPassenger = useStarted = freeTurned = toggleOnSent = sawToggleOff = sleepRequested = sawTargetSurface = false;
            serverObservation = null;
            pending = afterNativePoseDrain(this::prepareStage);
        }
        void finish(String status, String detail) {
            if (finishing) return;
            NecklacePoseProbe.active = false;
            finishing = true; finalStatus = status; finalDetail = detail;
            keys.keySet().forEach(key -> key.setDown(false)); player.stopUsingItem(); CombatRenderCounters.active = false;
            pending = afterNativePoseDrain(() -> {
                if (serverPlayer == null) return;
                ExpandedReactionTransition.displaced(serverPlayer);
                ExpandedRagdollRecovery.release(serverPlayer);
                serverPlayer.stopUsingItem(); serverPlayer.stopRiding(); spawned.forEach(Entity::discard);
                if (serverPlayer.isSleeping()) serverPlayer.stopSleepInBed(true, true);
                serverPlayer.stopFallFlying();
                if (inventory != null) serverPlayer.getInventory().load(inventory);
                AccessoriesCapability cap = AccessoriesCapability.get(serverPlayer);
                accessories.forEach((name, saved) -> {
                    AccessoriesContainer container = cap.getContainers().get(name); if (container == null) return;
                    for (int i = 0; i < Math.min(container.getSize(), saved.items.size()); i++) {
                        container.getAccessories().setItem(i, saved.items.get(i).copy()); container.getCosmeticAccessories().setItem(i, saved.cosmetic.get(i).copy());
                    }
                    for (int i = 0; i < Math.min(container.renderOptions().size(), saved.renders.size()); i++) container.renderOptions().set(i, saved.renders.get(i));
                });
                if (originalGameType != null) serverPlayer.setGameMode(originalGameType);
                serverPlayer.getAbilities().flying = originalFlying; serverPlayer.onUpdateAbilities();
                serverPlayer.getFoodData().setFoodLevel(originalFood); serverPlayer.getFoodData().setSaturation(originalSaturation);
                if (serverPosition != null) serverPlayer.teleportTo(serverLevel, serverPosition.x, serverPosition.y, serverPosition.z, serverYaw, serverPitch);
                serverPlayer.setDeltaMovement(Vec3.ZERO); serverPlayer.fallDistance = 0;
                serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(serverPlayer));
                ExpandedReactionTransition.displaced(serverPlayer);
                blocks.forEach((pos, state) -> serverLevel.setBlock(pos, state, 2));
                serverLevel.setDayTime(originalDayTime);
                serverLevel.getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(originalSleepingPercentage, mc.getSingleplayerServer());
                serverPlayer.setRespawnPosition(originalRespawnDimension, originalRespawnPosition, originalRespawnAngle, originalRespawnForced, false);
                serverPlayer.inventoryMenu.broadcastChanges();
                report.put("serverInventoryRestored", inventory == null || inventory.equals(serverPlayer.getInventory().save(new ListTag())));
                report.put("changedBlocksRestored", blocks.entrySet().stream().allMatch(entry -> serverLevel.getBlockState(entry.getKey()).equals(entry.getValue())));
            });
        }
        @SuppressWarnings({"rawtypes", "unchecked"}) void restoreClient() throws Exception {
            keys.forEach(KeyMapping::setDown); mc.options.setCameraType(originalCamera); mc.options.hideGui = originalGui;
            mc.options.smoothCamera = originalSmoothCamera; mc.options.invertYMouse().set(originalInvertMouse);
            if (fpmPresent) Fpm.enabled(originalFpm);
            for (var entry : unlockedConfig.entrySet()) ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) entry.getKey()).set(entry.getValue());
            for (var entry : unlockedState.entrySet()) entry.getKey().set(null, entry.getValue());
            player.setYRot(originalYaw); player.setXRot(originalPitch);
            report.put("inputRestored", keys.entrySet().stream().allMatch(entry -> entry.getKey().isDown() == entry.getValue()));
            report.put("unlockedConfigRestored", unlockedConfig.entrySet().stream().allMatch(entry -> java.util.Objects.equals(entry.getKey().get(), entry.getValue())));
        }
        void write(String status, String detail) throws IOException {
            report.put("status", status); report.put("detail", detail);
            report.put("currentStage", stageIndex < stages.size() ? stages.get(stageIndex).name : "complete");
            Files.writeString(output.resolve("movement-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }
    private static final class Fpm {
        static boolean enabled() { return dev.tr7zw.firstperson.api.FirstPersonAPI.isEnabled(); }
        static void enabled(boolean enabled) { dev.tr7zw.firstperson.api.FirstPersonAPI.setEnabled(enabled); }
        static Vec3 offset() { return dev.tr7zw.firstperson.FirstPersonModelCore.instance.getLogicHandler().getOffset(); }
        static int handlers() { return dev.tr7zw.firstperson.api.FirstPersonAPI.getPlayerOffsetHandlers().size(); }
    }
}
