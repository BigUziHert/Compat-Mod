package dev.caleb.emfattachments.perf;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.fml.ModList;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Offline client-render comparison with equipment, native Sable carry and a fixed observer. */
public final class EquippedCrowdBenchmark {
    private static final UUID OFFLINE_ID = UUID.nameUUIDFromBytes("OfflinePlayer:AttachmentTest".getBytes(StandardCharsets.UTF_8));
    private static final boolean IRIS = ModList.get().isLoaded("iris");
    private static final boolean SABLE_REDUCED_BENCHMARK = Boolean.getBoolean("emfattachments.sableReducedBenchmark");
    private static final Scene[] SABLE_REDUCED_SCENES = {
            new Scene("deck-open-8", 8, true, true, false, 18),
            new Scene("deck-open-24", 24, true, true, false, 18),
            new Scene("deck-occluded-8", 8, true, true, true, 18),
            new Scene("deck-occluded-24", 24, true, true, true, 18)
    };
    private static final Scene[] SCENES = {
            new Scene("local-lookdown-80", 0, false, false, false, 80),
            new Scene("world-idle-8", 8, false, false, false, 18),
            new Scene("world-idle-24", 24, false, false, false, 18),
            new Scene("world-walk-8", 8, true, false, false, 18),
            new Scene("world-walk-24", 24, true, false, false, 18),
            new Scene("deck-walk-8", 8, true, true, false, 18),
            new Scene("deck-walk-24", 24, true, true, false, 18),
            new Scene("deck-occluded-walk-8", 8, true, true, true, 18)
    };
    private static final Scene[] PERSPECTIVE_SCENES = {
            new Scene("world-first", 0, false, false, false, 18),
            new Scene("world-switches", 0, false, false, false, 18, false, CameraType.FIRST_PERSON, true),
            new Scene("world-third", 0, false, false, false, 18, false, CameraType.THIRD_PERSON_BACK, false),
            new Scene("world-crowd-third-24", 24, true, false, false, 18, false, CameraType.THIRD_PERSON_BACK, false),
            new Scene("deck-first", 0, false, true, false, 18),
            new Scene("deck-switches", 0, false, true, false, 18, false, CameraType.FIRST_PERSON, true),
            new Scene("deck-third", 0, false, true, false, 18, false, CameraType.THIRD_PERSON_BACK, false),
            new Scene("deck-crowd-first-24", 24, true, true, false, 18),
            new Scene("deck-crowd-third-24", 24, true, true, false, 18, false, CameraType.THIRD_PERSON_BACK, false),
            new Scene("near-deck-first", 0, false, false, false, -25, true, CameraType.FIRST_PERSON, false),
            new Scene("near-deck-third", 0, false, false, false, -25, true, CameraType.THIRD_PERSON_BACK, false)
    };
    private static volatile Session active;
    private static int polls, nativeFrames;

    private EquippedCrowdBenchmark() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EquippedCrowdBenchmark::tick);
        NeoForge.EVENT_BUS.addListener(EquippedCrowdBenchmark::serverTick);
        NeoForge.EVENT_BUS.addListener(EquippedCrowdBenchmark::preFrame);
        NeoForge.EVENT_BUS.addListener(EquippedCrowdBenchmark::postFrame);
        NeoForge.EVENT_BUS.addListener(EquippedCrowdBenchmark::worldStage);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EquippedCrowdBenchmark::cameraAngles);
    }

    private static void worldStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL && mc.player != null
                && mc.level != null && event.getCamera().getEntity() == mc.player) {
            nativeFrames++;
            Session s = active;
            if (s != null && s.capturingCounters() && event.getCamera() == mc.gameRenderer.getMainCamera()
                    && (!IRIS || !IrisPass.shadow())) s.observeActualView();
        }
    }

    private static void cameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Session s = active;
        if (s == null || !s.cameraLockAllowed() || event.getCamera() != s.mc.gameRenderer.getMainCamera()
                || (IRIS && IrisPass.shadow())) return;
        s.lockCamera();
        event.setYaw(s.expectedYaw());
        event.setPitch(s.expectedPitch());
        event.setRoll(0);
        if (s.measuring) s.measuredCameraAngleLocks++;
    }

    private static boolean disposable(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.hasSingleplayerServer()
                && !mc.getSingleplayerServer().isPublished()
                && mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                && mc.getUser().getType() == User.Type.LEGACY && "AttachmentTest".equals(mc.getUser().getName())
                && OFFLINE_ID.equals(mc.getUser().getProfileId()) && OFFLINE_ID.equals(mc.player.getUUID());
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) {
            try { active.tick(); } catch (Throwable failure) { active.finish("FAIL", describe(failure)); }
            return;
        }
        Path stop = mc.gameDirectory.toPath().resolve("emfattachments-perf-stop.txt");
        if (mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                && Files.isRegularFile(stop)) {
            try { Files.delete(stop); mc.stop(); } catch (Exception failure) { throw new IllegalStateException(failure); }
            return;
        }
        if (++polls % 20 != 0 || !disposable(mc) || mc.screen != null || !mc.isWindowActive() || nativeFrames < 20
                || !mc.levelRenderer.isSectionCompiled(mc.player.blockPosition())) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-equipped-benchmark.txt");
        if (!Files.isRegularFile(trigger)) return;
        Path output = mc.gameDirectory.toPath().resolve("emfattachments-tests/equipped-crowd");
        try {
            List<String> lines = Files.readAllLines(trigger);
            String label = (lines.isEmpty() ? "equipped-crowd" : lines.getFirst()).trim().replaceAll("[^a-zA-Z0-9._-]", "_");
            if (label.isBlank() || label.equals(".") || label.equals("..")) throw new IllegalArgumentException("Invalid run label");
            int warmup = 10, measure = 20;
            boolean cleanupOnly = false, nowheelComparison = false, visibilityProbe = false, probeOnly = false, perspectiveComparison = false;
            String perspectiveSceneFilter = ".*";
            boolean perspectiveIncludeGroundCrowd = false;
            for (String line : lines) {
                if (line.startsWith("warmupSeconds=")) warmup = Integer.parseInt(line.substring(14).trim());
                if (line.startsWith("measureSeconds=")) measure = Integer.parseInt(line.substring(15).trim());
                if (line.equals("cleanupOnly=true")) cleanupOnly = true;
                if (line.equals("comparison=nowheel")) nowheelComparison = true;
                if (line.equals("visibilityProbe=true")) visibilityProbe = true;
                if (line.equals("probeOnly=true")) probeOnly = true;
                if (line.equals("perspectiveComparison=true")) perspectiveComparison = true;
                if (line.startsWith("perspectiveSceneFilter=")) perspectiveSceneFilter = line.substring(23).trim();
                if (line.equals("perspectiveIncludeGroundCrowd=true")) perspectiveIncludeGroundCrowd = true;
            }
            if (warmup < 5 || warmup > 60 || measure < 5 || measure > 60) throw new IllegalArgumentException("Durations must be 5..60 seconds");
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);
            if (Files.exists(output.resolve("equipped-crowd-frames.json"))) throw new IllegalArgumentException("Refusing to replace an existing benchmark report");
            Files.createDirectories(output); Files.delete(trigger);
            if ((visibilityProbe || probeOnly) && !nowheelComparison) throw new IllegalArgumentException("Comparison probes require comparison=nowheel");
            if (perspectiveComparison && (cleanupOnly || nowheelComparison || visibilityProbe || probeOnly))
                throw new IllegalArgumentException("Perspective suite cannot be combined with historical comparison/probe modes");
            Session next = new Session(mc, output, label, warmup, measure, cleanupOnly, nowheelComparison, visibilityProbe || probeOnly, probeOnly,
                    perspectiveComparison, perspectiveSceneFilter, perspectiveIncludeGroundCrowd);
            active = next; next.prepare();
        } catch (Throwable failure) {
            if (active != null) active.finish("FAIL", describe(failure));
            else try {
                Files.createDirectories(output);
                Files.writeString(output.resolve("equipped-crowd-frames.json"), new GsonBuilder().setPrettyPrinting().create().toJson(
                        Map.of("status", "FAIL", "finished", true, "detail", describe(failure))));
            } catch (Exception ignored) {}
        }
    }

    private static void serverTick(ServerTickEvent.Pre event) {
        Session s = active;
        if (s == null || s.finishing || event.getServer() != s.server || s.platform == null) return;
        try { s.drivePlatform(); } catch (Throwable failure) { s.serverFailure = failure.toString(); }
    }

    private static void preFrame(RenderFrameEvent.Pre event) {
        Session s = active;
        if (s != null) { s.switchPerspectiveIfDue(); s.lockCamera(); }
        if (s != null && s.capturingCounters()) { s.frameSeenMask = 0; s.frameReturnedCalls = 0; s.frameLocalReturns = 0; }
    }

    private static void postFrame(RenderFrameEvent.Post event) {
        Session s = active;
        if (s != null && !s.finishing) try { s.frame(System.nanoTime()); }
        catch (Throwable failure) { s.finish("FAIL", describe(failure)); }
    }

    /** No buffers, GL queries, collections or report writes in these measured render hooks. */
    public static void playerRendered(AbstractClientPlayer entity) {
        Session s = active;
        if (s == null || !s.capturingCounters()) return;
        if (entity != s.local && !(entity instanceof CrowdPlayer)) return;
        if (IRIS && IrisPass.shadow()) { s.shadowReturns++; return; }
        if (entity == s.local) { s.frameLocalReturns++; s.localBodyReturns++; return; }
        CrowdPlayer clone = (CrowdPlayer)entity;
        if (clone.owner != s || clone.scene != s.scene) return;
        s.frameSeenMask |= 1L << clone.index; s.frameReturnedCalls++; s.renderReturns[clone.index]++;
    }

    public static void entityFullyTicked(Entity entity) {
        Session s = active;
        if (s == null || !s.capturingCounters()) return;
        if (entity == s.local) { s.localFullTicks++; return; }
        if (entity instanceof CrowdPlayer clone && clone.owner == s && clone.scene == s.scene) s.fullTicks[clone.index]++;
    }

    public static void accessoryRendered(LivingEntity entity) {
        Session s = active;
        if (s == null || !s.capturingCounters()) return;
        if (entity != s.local && !(entity instanceof CrowdPlayer)) return;
        if (IRIS && IrisPass.shadow()) return;
        if (entity == s.local) { s.localAccessoryDispatches++; return; }
        CrowdPlayer clone = (CrowdPlayer)entity;
        if (clone.owner == s && clone.scene == s.scene) s.accessoryDispatches[clone.index]++;
    }

    private static final class IrisPass {
        static boolean shadow() { return net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass(); }
    }

    private static final class FirstPersonState {
        static boolean enabled() { return dev.tr7zw.firstperson.api.FirstPersonAPI.isEnabled(); }
    }

    /** Installed mod's native F5 entry, cached outside measured frames. */
    private static final class UnlockedState {
        static final boolean installed = ModList.get().isLoaded("unlockedcamera");
        static final java.lang.reflect.Method enter;
        static final java.lang.reflect.Field active;
        static {
            try {
                Class<?> type = installed ? Class.forName("com.caleb.unlockedcamera.client.UnlockedCameraClient") : null;
                enter = type == null ? null : type.getDeclaredMethod("enterCamera", Minecraft.class);
                active = type == null ? null : type.getDeclaredField("active");
                if (enter != null) { enter.setAccessible(true); active.setAccessible(true); }
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
        static void enter(Minecraft mc) {
            try { if (enter != null) enter.invoke(null, mc); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native UnlockedCamera entry failed", failure); }
        }
        static boolean active() {
            try { return active != null && active.getBoolean(null); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        static void leave() {
            try { if (active != null) active.setBoolean(null, false); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
    }

    private record Scene(String id, int players, boolean walk, boolean deck, boolean occluded, float pitch,
                         boolean nearDeck, CameraType camera, boolean switches) {
        Scene(String id, int players, boolean walk, boolean deck, boolean occluded, float pitch) {
            this(id, players, walk, deck, occluded, pitch, false, CameraType.FIRST_PERSON, false);
        }
    }
    private record Grid(Vec3 observer, float yaw, List<Vec3> positions) {}

    /** Default skins use the native getSkin path, including Aether/Deep Aether cape replacements. */
    private static final class CrowdPlayer extends RemotePlayer {
        final Session owner;
        final Scene scene;
        final int index;
        final double baseX, baseY, baseZ;
        final PlayerTeam team = new PlayerTeam(new Scoreboard(), "equipped-perf");
        BenchmarkLoadouts.Applied gear;
        long movementTicks;
        double cumulativePath, lastX, lastY, lastZ;
        Vec3 lastPlotTarget;

        CrowdPlayer(Session s, int index, int id, Vec3 position, Vec3 reference) {
            super(s.level, profile(index));
            owner = s; scene = s.scene; this.index = index;
            baseX = reference.x; baseY = reference.y; baseZ = reference.z;
            team.setNameTagVisibility(Team.Visibility.NEVER);
            setId(id); setPos(position); setOldPosAndRot(); setNoGravity(true); setOnGround(true);
            setYRot(scene.deck ? 180 : s.grid.yaw + 180); yHeadRot = yHeadRotO = yBodyRot = yBodyRotO = getYRot();
            xCloak = xCloakO = lastX = getX(); yCloak = yCloakO = lastY = getY(); zCloak = zCloakO = lastZ = getZ();
        }

        @Override public PlayerTeam getTeam() { return team; }
        @Override public boolean isModelPartShown(PlayerModelPart part) { return true; }

        @Override public void tick() {
            if (owner.finishing || owner.scene != scene) { super.tick(); return; }
            // This suite feeds only packet-like targets from ClientTick.Post. Native
            // full/reduced processing alone must advance plot and world positions.
            if (owner.sableReducedBenchmark) { super.tick(); return; }
            // A skipped actor must not be repaired by a fixture position/plot update during functional probes.
            if (owner.visibility != null) { super.tick(); return; }
            double t = owner.clientMotionTicks, phase = index * .63;
            double x = baseX + (scene.walk ? .6 * Math.sin(t * .08 + phase) : 0);
            double z = baseZ + (scene.walk ? .3 * Math.cos(t * .08 + phase) : 0);
            if (scene.deck) ((EntityStickExtension)(Object)this).sable$setPlotPosition(new Vec3(x, baseY, z));
            super.tick();
            if (!scene.deck) setPos(x, baseY, z);
            double dx = getX() - lastX, dy = getY() - lastY, dz = getZ() - lastZ;
            setDeltaMovement(dx, dy, dz); setOnGround(true);
            if (scene.walk) {
                float heading = (float)Math.toDegrees(Math.atan2(-Math.cos(t * .08 + phase), -.5 * Math.sin(t * .08 + phase)));
                setYRot(heading); yBodyRot = heading; yHeadRot = heading + (float)(15 * Math.sin(t * .035 + phase));
                walkAnimation.update(.65F, .4F);
            } else walkAnimation.update(0, .4F);
            movementTicks++; cumulativePath += Math.sqrt(dx * dx + dy * dy + dz * dz);
            lastX = getX(); lastY = getY(); lastZ = getZ();
        }

        @Override public void aiStep() {
            super.aiStep();
            if (owner.sableReducedBenchmark && owner.measuring && owner.scene == scene)
                owner.aiSteps[index]++;
        }
    }

    private static GameProfile profile(int index) {
        PlayerSkin.Model wanted = index % 2 == 0 ? PlayerSkin.Model.WIDE : PlayerSkin.Model.SLIM;
        for (int attempt = 0; attempt < 128; attempt++) {
            UUID id = UUID.nameUUIDFromBytes(("EquippedBenchmark:" + index + ":" + attempt).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(id, "EquipPerf" + index);
            if (DefaultPlayerSkin.get(profile).model() == wanted) return profile;
        }
        throw new IllegalStateException("No native default skin for requested arm model");
    }

    private static final class Session {
        final Minecraft mc;
        final ClientLevel level;
        final LocalPlayer local;
        final net.minecraft.client.server.IntegratedServer server;
        final Path output;
        final int warmupSeconds, measureSeconds;
        final Grid grid;
        final Vec3 deckObserver, platformBase, nearObserver;
        final Scene[] scenes;
        final boolean perspectiveComparison;
        final boolean sableReducedBenchmark = SABLE_REDUCED_BENCHMARK;
        final CameraType originalCamera;
        final Entity originalCameraEntity;
        final float originalYaw, originalPitch;
        final boolean originalClientNoGravity;
        final boolean originalClientFlying, cleanupOnly, nowheelComparison, visibilityProbe, probeOnly;
        final NowheelTelemetry nowheel;
        final Vec3 originalClientMovement, originalClientInheritedVelocity;
        final UUID originalClientLastSupport;
        final Map<KeyMapping, Boolean> keys = new LinkedHashMap<>();
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<Map<String, Object>> stages = new ArrayList<>();
        final List<Map<String, Object>> behaviorFailures = new ArrayList<>();
        final List<CrowdPlayer> clones = new ArrayList<>();
        final List<Integer> retiredCloneIds = new ArrayList<>();
        final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        final long[] gcCountBefore = new long[collectors.size()], gcTimeBefore = new long[collectors.size()];
        final long[] gcCountAfter = new long[collectors.size()], gcTimeAfter = new long[collectors.size()];
        final long[] intervals = new long[250_000], renderReturns = new long[24], fullTicks = new long[24], accessoryDispatches = new long[24];
        final long[] aiSteps = new long[24], plotTargetUpdates = new long[24], motionSamples = new long[24],
                movingSamples = new long[24], culledSamples = new long[24], outOfCameraSamples = new long[24], lostSupportSamples = new long[24];
        final double[] measuredPaths = new double[24], maxPlotWorldErrors = new double[24], maxTargetLags = new double[24];
        final int[] frameCameras = new int[250_000], switchFrames = new int[64], switchFrom = new int[64], switchTo = new int[64];
        final long[] switchOffsets = new long[64];
        final long[] predicateSamples = new long[24], predicateTrue = new long[24], dynamicTrue = new long[24];
        final long deadline;
        final Map<BlockPos, BlockState> originalWall = new LinkedHashMap<>();
        volatile ServerSubLevel platform;
        volatile UUID platformId;
        volatile Scene scene;
        volatile String serverFailure;
        volatile boolean finishing;
        volatile int driveTicks;
        SubLevel clientDeck;
        Snapshot serverSnapshot;
        BenchmarkLoadouts.Applied serverGear, clientGear;
        CompletableFuture<?> preparing, stagePreparation, restoration;
        CompletableFuture<RestoreAudit> authoritativeRestore, authoritativeCheck;
        Map<String, Object> preparationEvidence;
        Map<String, Object> serverBeforeRestoration;
        int sceneIndex, ticks, readyTicks, clientMotionTicks, samples, minSeen, maxSeen, restoreTicks;
        long phaseDeadline, measurementStart, previousFrame, frameSeenMask, frameReturnedCalls, frameLocalReturns;
        long totalReturns, shadowReturns, fullyRenderedFrames, missingFrames, localRenderedFrames, localBodyReturns,
                localFullTicks, localAccessoryDispatches, heapBefore, heapAfter, measuredTicks, localTrackedTicks,
                localUntrackedTicks, firstPersonFrames, thirdPersonFrames, thirdPersonBodyFrames;
        CameraType expectedCamera;
        int switchCount;
        long nextSwitchAt;
        double minimumCameraEyeDistance, maximumCameraEyeDistance;
        double maxLocalPlotDrift;
        long measuredActualViewSamples, measuredCameraAngleLocks;
        float maxActualViewYawError, maxActualViewPitchError;
        boolean measuring, waitingStage, waitingInitial = true, warmed, stageReady, allRetiredGearRestored = true, continuedDeckRide;
        int stageDriveTickAtStart;
        int ownedClonesCreated;
        String finalStatus, finalDetail;
        long restorationDeadline;
        boolean clientMotionRestored;
        volatile VisibilityProbe visibility;

        Session(Minecraft mc, Path output, String label, int warmup, int measure, boolean cleanupOnly,
                boolean nowheelComparison, boolean visibilityProbe, boolean probeOnly,
                boolean perspectiveComparison, String perspectiveSceneFilter, boolean perspectiveIncludeGroundCrowd) {
            if (!disposable(mc) || mc.player.isPassenger() || mc.player.isUsingItem() || !mc.player.onGround())
                throw new IllegalStateException("Benchmark requires the isolated, grounded, unmounted offline player");
            if (tracking(mc.player) != null || ((EntityStickExtension)mc.player).sable$getPlotPosition() != null)
                throw new IllegalStateException("Benchmark must start on ordinary world ground, not a pre-existing Sable support");
            if (mc.options.framerateLimit().get() < 260 || mc.options.enableVsync().get())
                throw new IllegalStateException("Benchmark requires uncapped maxFps>=260 and VSync disabled; no settings changed");
            this.mc = mc; level = mc.level; local = mc.player; server = mc.getSingleplayerServer(); this.output = output;
            warmupSeconds = warmup; measureSeconds = measure;
            this.perspectiveComparison = perspectiveComparison;
            if (sableReducedBenchmark && (perspectiveComparison || cleanupOnly || nowheelComparison || visibilityProbe || probeOnly))
                throw new IllegalArgumentException("Reduced-tick benchmark cannot be combined with another suite/probe mode");
            scenes = sableReducedBenchmark ? SABLE_REDUCED_SCENES : perspectiveComparison ? Arrays.stream(PERSPECTIVE_SCENES)
                    .filter(s -> perspectiveIncludeGroundCrowd || !s.id.equals("world-crowd-third-24"))
                    .filter(s -> s.id.matches(perspectiveSceneFilter)).toArray(Scene[]::new) : SCENES;
            if (scenes.length == 0) throw new IllegalArgumentException("No perspective scenes matched the requested filter");
            this.cleanupOnly = cleanupOnly;
            this.nowheelComparison = nowheelComparison; this.visibilityProbe = visibilityProbe; this.probeOnly = probeOnly;
            nowheel = nowheelComparison ? new NowheelTelemetry() : null;
            if (nowheelComparison) {
                boolean ours = ModList.get().isLoaded("emfattachments"), other = ModList.get().isLoaded("nowheel");
                if (ours == other || ModList.get().isLoaded("firstperson"))
                    throw new IllegalStateException("Nowheel comparison requires exactly one of ours/Nowheel and no FirstPerson");
                Map<String, String> versions = NowheelTelemetry.versions();
                if (ours && !"1.2.5".equals(versions.get("emfattachments")) || other && !"2.1.1".equals(versions.get("nowheel")))
                    throw new IllegalStateException("Unexpected comparison mod version: " + versions);
                report.put("comparisonMode", "nowheel"); report.put("nowheelInstalled", other); report.put("modVersions", versions);
                report.put("behaviorFailures", behaviorFailures); report.put("visibilityProbeRequested", visibilityProbe);
                report.put("probeOnlyPreflight", probeOnly);
                report.put("telemetrySamplingPolicy", "Read-only installed Nowheel predicate/native dynamic-whitelist sampling once per 20 measured client ticks for owned actors only. No per-frame reflection, config changes, or forced culling flags.");
            }
            grid = findGrid(level, local.position());
            deckObserver = grid.observer.add(64, 12, 0);
            platformBase = deckObserver.add(0, -1, 8);
            nearObserver = grid.observer.add(64, 0, -12);
            if (perspectiveComparison && Arrays.stream(scenes).anyMatch(s -> s.nearDeck)) {
                BlockPos below = BlockPos.containing(nearObserver.x, nearObserver.y - .05, nearObserver.z);
                var shape = level.getBlockState(below).getCollisionShape(level, below);
                if (!level.hasChunkAt(below) || shape.isEmpty()
                        || Math.abs(below.getY() + shape.max(Direction.Axis.Y) - nearObserver.y) > .08
                        || !level.noCollision(null, new AABB(nearObserver.x - 1, nearObserver.y + .01, nearObserver.z - 1,
                        nearObserver.x + 1, nearObserver.y + 2, nearObserver.z + 1)))
                    throw new IllegalStateException("Near-deck observer requires existing unobstructed world ground");
            }
            originalCamera = mc.options.getCameraType(); originalCameraEntity = mc.getCameraEntity();
            originalYaw = local.getYRot(); originalPitch = local.getXRot(); originalClientNoGravity = local.isNoGravity();
            originalClientFlying = local.getAbilities().flying; originalClientMovement = local.getDeltaMovement();
            originalClientInheritedVelocity = inheritedVelocity(local);
            originalClientLastSupport = ((EntityMovementExtension)local).sable$getLastTrackingSubLevelID();
            for (KeyMapping key : List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                    mc.options.keyShift, mc.options.keyJump, mc.options.keySprint, mc.options.keyUse, mc.options.keyAttack)) {
                keys.put(key, key.isDown());
            }
            deadline = System.nanoTime() + (long)(scenes.length * (warmup + measure + 15) + 180) * 1_000_000_000L;
            report.put("label", label); report.put("startedAt", Instant.now().toString());
            report.put("originalClientState", motionState(local));
            report.put("cleanupOnlyPreflight", cleanupOnly);
            report.put("perspectiveComparison", perspectiveComparison);
            report.put("sableReducedBenchmark", sableReducedBenchmark);
            report.put("sableFullTicksProperty", Boolean.getBoolean("emfattachments.sableFullTicks"));
            if (sableReducedBenchmark) {
                if (!ModList.get().isLoaded("emfattachments") || !ModList.get().isLoaded("entityculling"))
                    throw new IllegalStateException("Reduced-tick A/B requires the same production jar and Entity Culling in both modes");
                report.put("modVersions", NowheelTelemetry.versions());
                report.put("behaviorFailures", behaviorFailures);
                report.put("remoteMotionInputPolicy", "Initial spawn establishes world position, plot position and support once. Thereafter ClientTick.Post sends only sable$plotLerpTo(target,3) every three client ticks, independently of actor ticking. Native full tick or reduced aiStep alone advances world/plot positions. The fixture never repairs positions or tracking, forces culling flags, or calls aiStep itself.");
                report.put("remoteMotionValidation", Map.of("maximumPlotWorldErrorBlocks", .25,
                        "maximumPlotTargetLagBlocks", .5, "minimumMeasuredTravelBlocks", .5,
                        "minimumNativeAiStepsPerMeasuredTick", .9, "minimumHiddenCullFraction", .9));
            }
            report.put("perspectiveSceneFilter", perspectiveSceneFilter);
            report.put("perspectiveIncludeGroundCrowd", perspectiveIncludeGroundCrowd);
            report.put("plannedScenes", Arrays.stream(scenes).map(s -> s.id).toList());
            if (perspectiveComparison) {
                report.put("perspectiveSwitchPolicy", "First/back camera alternation every two seconds during measured intervals. Uses installed UnlockedCamera's native enterCamera method used by F5; return to first person clears its active flag and calls native Options.setCameraType, GameRenderer.checkEntityPostEffect and LevelRenderer.needsUpdate. Physical F5 dispatch, front-view cycling and free-look motion are not simulated. Scene warmup stays first person for switch scenes. Frame indices are zero-based and identify the first interval containing the switched view.");
                report.put("unlockedCameraInstalled", UnlockedState.installed);
                report.put("nearDeckPolicy", "Grounded untracked observer 20 blocks horizontally from moving elevated platform origin, looking upward 25 degrees. Native deck geometry remains present throughout the suite; ordinary-world controls are 64 blocks away.");
                report.put("frameCameraOrdinalNames", List.of("FIRST_PERSON", "THIRD_PERSON_BACK", "THIRD_PERSON_FRONT"));
            }
            report.put("restorationPolicy", "Remove owned Sable support and await client removal before authoritative teleport. Restore separate Sable inherited velocity and last-support state on both sides, then verify immediate server state and settled client/server state without widening position tolerance.");
            report.put("runtime", mc.gameDirectory.toPath().toAbsolutePath().normalize().toString());
            report.put("compatInstalled", ModList.get().isLoaded("emfattachments"));
            report.put("firstPersonInstalled", ModList.get().isLoaded("firstperson"));
            boolean firstPersonEnabled = ModList.get().isLoaded("firstperson") && FirstPersonState.enabled();
            report.put("firstPersonEnabled", firstPersonEnabled);
            if (ModList.get().isLoaded("firstperson") && !firstPersonEnabled) throw new IllegalStateException("FirstPerson is installed but not enabled");
            report.put("fullDeveloperHarnessInstalled", ModList.get().isLoaded("emfattachments_test"));
            if (ModList.get().isLoaded("emfattachments_test")) throw new IllegalStateException("Full diagnostic harness must be physically absent");
            report.put("productionMixinsDisabledProperty", Boolean.getBoolean("emfattachments.disable"));
            if (Boolean.getBoolean("emfattachments.disable")) throw new IllegalStateException("Use physical jar removal, not disabled mixins");
            report.put("resourcePacks", mc.getResourcePackRepository().getSelectedIds());
            report.put("renderSettings", settings(mc)); report.put("warmupSeconds", warmup); report.put("measureSeconds", measure);
            report.put("firstSceneExtraWarmupSeconds", 20);
            report.put("cameraLocked", true);
            report.put("cameraLockPolicy", "Only this owned offline observer: reset current/old player rotations before frames and client validation, then pin native main-camera yaw/pitch/roll through LOWEST ComputeCameraAngles. Input, focus, camera-type and actual-view guards remain active; restored at fixture end.");
            report.put("platformTrajectory", Map.of("horizontalAmplitudeBlocks", .5, "verticalAmplitudeBlocks", .25,
                    "rollRadians", 0, "controller", "Native physics-pipeline teleport/resetVelocity; translation only for a stable native observer"));
            report.put("stages", stages);
            report.put("scope", sableReducedBenchmark
                    ? "Same-jar full/reduced Sable tick comparison: equipped RemotePlayers receive identical independent plot interpolation targets on a real moving server-owned deck, with open and native wall-occluded views. Client-side actor and rendering costs are measured; multiplayer packet transport and server simulation costs are excluded."
                    : "Standalone offline native client-render workload: equipped RemotePlayer entities with native default skin/cape hooks, deterministic walking and native full ticks; a real server-owned moving Sable platform carries the local observer and remote plot-position players. Compatibility jar is physically present/absent per manifest.");
            report.put("limitations", List.of("Remote actors are client-only clones, not actual networked clients; no network, skin downloads or real remote-server simulation is benchmarked.",
                    "Walking target updates and controlled native physics-pipeline teleports add shared fixture work to every mode.",
                    "Native default WIDE/SLIM skins execute getSkin/cape hooks without player-info downloads; custom user skins are not represented.",
                    "Native renderer return and accessory dispatch counters do not prove final GPU pixels; post-measurement screenshots require review.",
                    "Occluded stage uses a real temporary world wall and native Entity Culling decisions; visibility/tick workloads may intentionally differ between mod modes.",
                    "Timestamps and primitive counters only run in measured render hooks; sorting, JSON and PNG encoding occur after timing stops.",
                    "GC collector deltas are aggregate collector timing, not attribution of individual slow frames."));
        }

        <T> CompletableFuture<T> server(Function<ServerPlayer, T> action) {
            CompletableFuture<T> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    if (server.isPublished()) throw new IllegalStateException("Offline integrated server was published");
                    ServerPlayer p = server.getPlayerList().getPlayer(OFFLINE_ID);
                    if (p == null) throw new IllegalStateException("Offline player missing");
                    future.complete(action.apply(p));
                } catch (Throwable failure) { future.completeExceptionally(failure); }
            });
            return future;
        }

        boolean capturingCounters() { return measuring || visibility != null && visibility.sampling; }
        float expectedYaw() { return visibility == null ? (scene.deck || scene.nearDeck ? 0 : grid.yaw) : visibility.yaw(); }
        float expectedPitch() { return visibility == null ? scene.pitch : 5; }
        Vec3 worldObserver() { return scene.nearDeck ? nearObserver : grid.observer; }

        void setPerspective(CameraType next) {
            CameraType old = mc.options.getCameraType();
            expectedCamera = next;
            if (perspectiveComparison && UnlockedState.installed && next == CameraType.THIRD_PERSON_BACK) {
                UnlockedState.enter(mc);
            } else {
                if (perspectiveComparison && UnlockedState.installed) UnlockedState.leave();
                mc.options.setCameraType(next);
                if (old.isFirstPerson() != next.isFirstPerson())
                    mc.gameRenderer.checkEntityPostEffect(next.isFirstPerson() ? mc.getCameraEntity() : null);
                mc.levelRenderer.needsUpdate();
            }
        }

        void switchPerspectiveIfDue() {
            if (!measuring || !scene.switches) return;
            long now = System.nanoTime();
            if (now < nextSwitchAt) return;
            if (switchCount == switchFrames.length) throw new IllegalStateException("Switch capacity exceeded");
            CameraType next = expectedCamera == CameraType.FIRST_PERSON ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON;
            switchFrames[switchCount] = samples; switchOffsets[switchCount] = now - measurementStart;
            switchFrom[switchCount] = expectedCamera.ordinal(); switchTo[switchCount] = next.ordinal(); switchCount++;
            setPerspective(next);
            nextSwitchAt = now + 2_000_000_000L;
        }

        void prepareNowheelTelemetry() {
            RemotePlayer plot = new RemotePlayer(level, profile(100)), world = new RemotePlayer(level, profile(101));
            plot.setPos(Vec3.atCenterOf(clientDeck.getPlot().getCenterBlock())); world.setPos(grid.observer);
            Map<String, Object> telemetry = nowheel.describe(plot, world);
            report.put("nowheelTelemetry", telemetry);
            if (!nowheel.errors.isEmpty()) throw new IllegalStateException("Native comparison telemetry failed: " + nowheel.errors);
            if (nowheel.installed && !Boolean.TRUE.equals(telemetry.get("sablePredicateRegistered")))
                behaviorFailures.add(Map.of("scope", "registration", "finding", "Installed Nowheel Sable predicate was not observed in the native whitelist with positive/negative controls"));
            if (nowheel.installed) {
                Object config = telemetry.get("config");
                if (!Map.of("overrideEntityCulling", true, "tickCulling", false, "distanceCulling", false, "forceRemoving", false).equals(config))
                    throw new IllegalStateException("Nowheel comparison requires unchanged native defaults: " + config);
            }
        }

        void samplePredicates() {
            for (CrowdPlayer clone : clones) {
                Boolean value = nowheel.predicate(clone);
                if (value != null) { predicateSamples[clone.index]++; if (value) predicateTrue[clone.index]++; }
                if (NowheelTelemetry.dynamic(clone)) dynamicTrue[clone.index]++;
            }
            if (!nowheel.errors.isEmpty()) throw new IllegalStateException("Native comparison predicate telemetry failed: " + nowheel.errors);
        }

        void resetCounters() {
            Arrays.fill(renderReturns, 0); Arrays.fill(fullTicks, 0); Arrays.fill(accessoryDispatches, 0);
            Arrays.fill(predicateSamples, 0); Arrays.fill(predicateTrue, 0); Arrays.fill(dynamicTrue, 0);
            frameSeenMask = frameReturnedCalls = frameLocalReturns = 0;
            measuredActualViewSamples = measuredCameraAngleLocks = 0;
            maxActualViewYawError = maxActualViewPitchError = 0;
        }

        void beginVisibilityProbe() {
            stageReady = false;
            visibility = new VisibilityProbe(this);
            visibility.prepare();
        }

        void prepare() {
            keys.keySet().forEach(key -> key.setDown(false));
            // Capture the actual client state before the authoritative server setup can synchronize its gear.
            clientGear = BenchmarkLoadouts.equip(local, 0);
            preparing = server(p -> {
                serverSnapshot = new Snapshot(p); serverGear = BenchmarkLoadouts.equip(p, 0);
                var container = SubLevelContainer.getContainer(p.serverLevel());
                if (container == null) throw new IllegalStateException("Missing Sable container");
                Pose3d pose = new Pose3d(); pose.position().set(platformBase.x, platformBase.y, platformBase.z);
                platform = (ServerSubLevel)container.allocateNewSubLevel(pose); platformId = platform.getUniqueId();
                platform.setName("Equipped performance fixture");
                var plot = platform.getPlot(); ChunkPos center = plot.getCenterChunk();
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) plot.newEmptyChunk(new ChunkPos(center.x + x, center.z + z));
                int blocks = 0;
                for (int x = -15; x <= 15; x++) for (int z = -10; z <= 14; z++) {
                    BlockPos pos = new BlockPos(x, 0, z);
                    plot.getEmbeddedLevelAccessor().setBlock(pos, x == 0 || z == 0 ? Blocks.QUARTZ_BLOCK.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState(), 3);
                    if (!plot.getEmbeddedLevelAccessor().getBlockState(pos).isAir()) blocks++;
                }
                if (blocks != 775 || platform.getMassTracker().getMass() <= 0) throw new IllegalStateException("Owned platform geometry/mass not initialized");
                platform.logicalPose().position().set(platformBase.x, platformBase.y, platformBase.z); platform.updateLastPose();
                container.physicsSystem().getPipeline().teleport(platform, new Vector3d(platformBase.x, platformBase.y, platformBase.z), new Quaterniond());
                preparationEvidence = Map.of("platformBlocks", blocks, "platformBase", vector(platformBase), "deckObserverBase", vector(deckObserver),
                        "mass", platform.getMassTracker().getMass(), "serverLocalLoadout", serverGear.metadata());
                return null;
            });
            write("PREPARING", false, "Creating and synchronizing owned platform and equipment");
        }

        void drivePlatform() {
            if (platform == null || platform.isRemoved()) return;
            if (visibility != null) { visibility.drive(); return; }
            Scene s = scene;
            double phase = s != null && (s.deck || s.nearDeck) ? driveTicks++ * Math.PI / 100 : 0;
            double x = platformBase.x + .5 * Math.sin(phase);
            double y = platformBase.y + .25 * Math.sin(phase * 2);
            var pipeline = SubLevelContainer.getContainer(platform.getLevel()).physicsSystem().getPipeline();
            pipeline.resetVelocity(platform);
            pipeline.teleport(platform, new Vector3d(x, y, platformBase.z), new Quaterniond());
        }

        void tick() {
            if (finishing) { restoreTick(); return; }
            ticks++;
            if (serverFailure != null) throw new IllegalStateException(serverFailure);
            if (mc.level != level || mc.player != local || mc.getSingleplayerServer() != server || server.isPublished())
                throw new IllegalStateException("Offline world identity changed");
            if (System.nanoTime() > deadline) throw new IllegalStateException("Bounded benchmark deadline exceeded");
            if (mc.screen != null || mc.isPaused() || !local.isAlive()) throw new IllegalStateException("GUI, pause or death interrupted benchmark");
            if (waitingInitial) {
                if (!preparing.isDone()) return;
                preparing.join();
                clientDeck = SubLevelContainer.getContainer(level).getSubLevel(platformId);
                if (clientDeck == null || level.getBlockState(clientDeck.getPlot().getCenterBlock()).isAir()) {
                    if (ticks > 300) throw new IllegalStateException("Native platform synchronization timeout");
                    return;
                }
                preflightProfiles();
                report.put("preparation", preparationEvidence); report.put("clientLocalLoadout", clientGear.metadata());
                report.put("originalServerState", serverSnapshot.description());
                waitingInitial = false;
                if (nowheelComparison) prepareNowheelTelemetry();
                if (cleanupOnly || probeOnly) sceneIndex = scenes.length - 1;
                startStage(); return;
            }
            if (visibility != null) { visibility.tick(); return; }
            if (waitingStage) {
                if (!stagePreparation.isDone()) return;
                stagePreparation.join();
                local.setNoGravity(false);
                boolean correctPosition = scene.deck ? tracking(local) == clientDeck && local.onGround()
                        : local.position().distanceToSqr(worldObserver()) < .0025 && local.onGround() && tracking(local) == null;
                readyTicks = correctPosition ? readyTicks + 1 : 0;
                if (ticks > 300) throw new IllegalStateException("Observer did not settle at native scene position: " + scene.id + " " + local.position());
                if (readyTicks < 12) return;
                local.setYRot(expectedYaw()); local.yRotO = local.getYRot();
                local.yHeadRot = local.yHeadRotO = local.yBodyRot = local.yBodyRotO = local.getYRot();
                local.setXRot(scene.pitch); local.xRotO = scene.pitch;
                spawnClones(); waitingStage = false; stageReady = true; warmed = false;
                phaseDeadline = System.nanoTime() + (long)(warmupSeconds + (sceneIndex == 0 ? 20 : 0)) * 1_000_000_000L;
                report.put("phase", "WARMUP"); write("RUNNING", false, scene.id); return;
            }
            clientMotionTicks++;
            if (sableReducedBenchmark) advanceRemoteTargetsAndObserve();
            lockCamera();
            validate();
            if (measuring) {
                measuredTicks++;
                if (scene.deck && tracking(local) == clientDeck) localTrackedTicks++;
                if (tracking(local) == null) localUntrackedTicks++;
                if (nowheelComparison && measuredTicks % 20 == 0) samplePredicates();
            }
        }

        void advanceRemoteTargetsAndObserve() {
            for (CrowdPlayer clone : clones) {
                int i = clone.index;
                double dx = clone.getX() - clone.lastX, dy = clone.getY() - clone.lastY, dz = clone.getZ() - clone.lastZ;
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                clone.lastX = clone.getX(); clone.lastY = clone.getY(); clone.lastZ = clone.getZ();
                clone.cumulativePath += distance; clone.movementTicks++;
                EntityStickExtension stick = (EntityStickExtension)(Object)clone;
                Vec3 plot = stick.sable$getPlotPosition();
                if (measuring) {
                    motionSamples[i]++; measuredPaths[i] += distance;
                    if (distance > 1e-6) movingSamples[i]++;
                    if (tracking(clone) != clientDeck || plot == null) lostSupportSamples[i]++;
                    if (plot != null) {
                        maxPlotWorldErrors[i] = Math.max(maxPlotWorldErrors[i], clientDeck.logicalPose().transformPosition(plot).distanceTo(clone.position()));
                        if (clone.lastPlotTarget != null)
                            maxTargetLags[i] = Math.max(maxTargetLags[i], plot.distanceTo(clone.lastPlotTarget));
                    }
                    var culling = (dev.tr7zw.entityculling.access.Cullable)(Object)clone;
                    if (culling.isCulled()) culledSamples[i]++;
                    if (culling.isOutOfCamera()) outOfCameraSamples[i]++;
                }
                if (clone.lastPlotTarget == null || clientMotionTicks % 3 == 0) {
                    double phase = clientMotionTicks * .08 + i * .63;
                    clone.lastPlotTarget = new Vec3(clone.baseX + .6 * Math.sin(phase), clone.baseY,
                            clone.baseZ + .3 * Math.cos(phase));
                    stick.sable$plotLerpTo(clone.lastPlotTarget, 3);
                    if (measuring) plotTargetUpdates[i]++;
                }
            }
        }

        void resetRemoteMotionCounters() {
            Arrays.fill(aiSteps, 0); Arrays.fill(plotTargetUpdates, 0); Arrays.fill(motionSamples, 0);
            Arrays.fill(movingSamples, 0); Arrays.fill(culledSamples, 0); Arrays.fill(outOfCameraSamples, 0);
            Arrays.fill(lostSupportSamples, 0); Arrays.fill(measuredPaths, 0);
            Arrays.fill(maxPlotWorldErrors, 0); Arrays.fill(maxTargetLags, 0);
            for (CrowdPlayer clone : clones) {
                clone.lastX = clone.getX(); clone.lastY = clone.getY(); clone.lastZ = clone.getZ();
            }
        }

        void validate() {
            if (!mc.isWindowActive()) throw new IllegalStateException("Window focus loss invalidated benchmark");
            if (mc.options.framerateLimit().get() < 260 || mc.options.enableVsync().get()) throw new IllegalStateException("Limiter/VSync changed during benchmark");
            if (mc.getWindow().getWidth() != 2560 || mc.getWindow().getHeight() != 1440 || mc.options.fov().get() != 90
                    || mc.options.renderDistance().get() != 8 || mc.options.simulationDistance().get() != 8 || !mc.getWindow().isFullscreen())
                throw new IllegalStateException("Native benchmark viewport/settings differ from fullscreen2560x1440, FOV90, render/simulation8");
            if (mc.options.getCameraType() != expectedCamera || mc.getCameraEntity() != local || local.isPassenger())
                throw new IllegalStateException("Observer perspective changed outside scheduled transition");
            float expectedYaw = expectedYaw();
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(local.getYRot() - expectedYaw)) > .15F || Math.abs(local.getXRot() - expectedPitch()) > .15F)
                throw new IllegalStateException("Observer look direction changed");
            if (maxActualViewYawError > .15F || maxActualViewPitchError > .15F)
                throw new IllegalStateException("Actual main-camera view differed from the locked direction");
            for (KeyMapping key : keys.keySet()) if (key.isDown()) throw new IllegalStateException("Gameplay input interrupted benchmark");
            for (CrowdPlayer clone : clones) if (level.getEntity(clone.getId()) != clone) throw new IllegalStateException("Owned clone left client registry");
            if (visibility != null) { visibility.validateObserver(); return; }
            if (scene.deck) {
                if (clientDeck.isRemoved() || tracking(local) != clientDeck) throw new IllegalStateException("Observer lost native Sable deck support");
                Vec3 relative = clientDeck.logicalPose().transformPositionInverse(local.position());
                BlockPos center = clientDeck.getPlot().getCenterBlock();
                maxLocalPlotDrift = Math.max(maxLocalPlotDrift, Math.hypot(relative.x - (center.getX() + .5), relative.z - (center.getZ() - 8)));
                if (Math.abs(relative.x - (center.getX() + .5)) > .3 || Math.abs(relative.z - (center.getZ() - 8)) > .3)
                    throw new IllegalStateException("Observer moved across deck: " + relative);
            } else if (local.position().distanceToSqr(worldObserver()) > .0025 || tracking(local) != null) throw new IllegalStateException("World observer moved or acquired deck support");
        }

        boolean cameraLockAllowed() {
            return !finishing && stageReady && scene != null && mc.level == level && mc.player == local
                    && mc.getCameraEntity() == local && mc.screen == null && !mc.isPaused() && mc.isWindowActive();
        }

        void lockCamera() {
            if (!cameraLockAllowed()) return;
            float yaw = expectedYaw();
            local.setYRot(yaw); local.yRotO = yaw;
            local.setXRot(expectedPitch()); local.xRotO = expectedPitch();
            local.yHeadRot = local.yHeadRotO = local.yBodyRot = local.yBodyRotO = yaw;
        }

        void observeActualView() {
            float yaw = expectedYaw();
            var camera = mc.gameRenderer.getMainCamera();
            measuredActualViewSamples++;
            maxActualViewYawError = Math.max(maxActualViewYawError, Math.abs(net.minecraft.util.Mth.wrapDegrees(camera.getYRot() - yaw)));
            maxActualViewPitchError = Math.max(maxActualViewPitchError, Math.abs(camera.getXRot() - expectedPitch()));
            double distance = camera.getPosition().distanceTo(local.getEyePosition(mc.getTimer().getGameTimeDeltaPartialTick(false)));
            minimumCameraEyeDistance = Math.min(minimumCameraEyeDistance, distance);
            maximumCameraEyeDistance = Math.max(maximumCameraEyeDistance, distance);
        }

        void startStage() {
            Scene previous = scene;
            Scene next = scenes[sceneIndex];
            continuedDeckRide = previous != null && previous.deck && next.deck;
            removeClones(); scene = next;
            // Reboarding/resetting a moving native rider can replay in-flight advection updates.
            // Keep the original ride and motion phase across adjacent deck workloads.
            if (!continuedDeckRide) driveTicks = 0;
            stageDriveTickAtStart = driveTicks;
            clientMotionTicks = 0; readyTicks = ticks = 0;
            maxLocalPlotDrift = 0;
            measuredActualViewSamples = measuredCameraAngleLocks = 0;
            maxActualViewYawError = maxActualViewPitchError = 0;
            stageReady = false; waitingStage = true; measuring = false;
            mc.setCameraEntity(local); setPerspective(scene.camera);
            keys.keySet().forEach(key -> key.setDown(false));
            if (!continuedDeckRide) {
                local.setNoGravity(true);
                if (perspectiveComparison) {
                    clearOwnedSupport(local);
                    ((LivingEntityMovementExtension)local).sable$getInheritedVelocity().zero();
                    local.setDeltaMovement(Vec3.ZERO);
                }
            }
            stagePreparation = server(p -> {
                if (scene.occluded) createWall(p); else restoreWall(p);
                if (continuedDeckRide) {
                    if (tracking(p) != platform) throw new IllegalStateException("Server observer lost the existing native deck ride");
                    return null;
                }
                if (perspectiveComparison) {
                    clearOwnedSupport(p);
                    ((LivingEntityMovementExtension)p).sable$getInheritedVelocity().zero();
                }
                p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = false; p.onUpdateAbilities(); p.setNoGravity(false);
                Vec3 position;
                if (scene.deck) {
                    BlockPos center = platform.getPlot().getCenterBlock();
                    position = platform.logicalPose().transformPosition(new Vec3(center.getX() + .5, center.getY() + 2, center.getZ() - 8));
                } else position = worldObserver();
                p.teleportTo(p.serverLevel(), position.x, position.y, position.z, expectedYaw(), scene.pitch);
                p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
                return null;
            });
            report.put("activeScene", scene.id); report.put("phase", "SCENE_PREPARATION"); write("RUNNING", false, scene.id);
        }

        void spawnClones() {
            int id = Integer.MIN_VALUE + 12000;
            BlockPos center = clientDeck.getPlot().getCenterBlock();
            for (int i = 0; i < scene.players; i++) {
                while (level.getEntity(id) != null) id++;
                int gridIndex = scene.players == 8 ? i * 3 + 1 : i;
                Vec3 reference, position;
                if (scene.deck) {
                    Vec3 p = formation(gridIndex);
                    reference = new Vec3(center.getX() + p.x, center.getY() + 1, center.getZ() + p.z - 8);
                    position = clientDeck.logicalPose().transformPosition(reference);
                } else reference = position = grid.positions.get(gridIndex);
                CrowdPlayer clone = new CrowdPlayer(this, i, id++, position, reference);
                if (scene.deck) {
                    ((EntityStickExtension)(Object)clone).sable$setPlotPosition(reference);
                    ((EntityMovementExtension)(Object)clone).sable$setTrackingSubLevel(clientDeck);
                }
                clone.gear = BenchmarkLoadouts.equip(clone, i % BenchmarkLoadouts.profiles().size());
                clones.add(clone); ownedClonesCreated++; level.addEntity(clone);
            }
        }

        void preflightProfiles() {
            List<Map<String, Object>> results = new ArrayList<>();
            report.put("profilePreflight", results);
            for (int i = 0; i < BenchmarkLoadouts.profiles().size(); i++) {
                RemotePlayer witness = new RemotePlayer(level, profile(i));
                BenchmarkLoadouts.Applied gear = BenchmarkLoadouts.equip(witness, i);
                try {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("profile", BenchmarkLoadouts.profiles().get(i).name());
                    result.put("loadout", gear.metadata()); result.put("equippedSkin", skin(witness));
                    gear.close(); result.put("restored", gear.restored()); results.add(result);
                    if (!gear.restored()) throw new IllegalStateException("Preflight loadout restoration failed");
                } finally { gear.close(); }
            }
        }

        void createWall(ServerPlayer p) {
            if (!originalWall.isEmpty()) return;
            BlockPos center = platform.getPlot().getCenterBlock();
            Vec3 observer = platform.logicalPose().transformPosition(new Vec3(center.getX() + .5, center.getY() + 1, center.getZ() - 8));
            int y = (int)Math.floor(observer.y) - 2, z = (int)Math.floor(observer.z + 13);
            int centerX = (int)Math.floor(observer.x);
            for (int dx = -15; dx <= 15; dx++) for (int dy = 0; dy <= 8; dy++) {
                BlockPos pos = new BlockPos(centerX + dx, y + dy, z);
                BlockState state = p.serverLevel().getBlockState(pos);
                if (!state.isAir()) throw new IllegalStateException("Occlusion wall volume was not empty: " + pos);
                originalWall.put(pos, state);
            }
            for (BlockPos pos : originalWall.keySet()) p.serverLevel().setBlock(pos, Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
        }

        void restoreWall(ServerPlayer p) {
            for (var entry : originalWall.entrySet()) {
                p.serverLevel().setBlock(entry.getKey(), entry.getValue(), 3);
                if (!p.serverLevel().getBlockState(entry.getKey()).equals(entry.getValue()))
                    throw new IllegalStateException("Native wall block restoration mismatch: " + entry.getKey());
            }
            originalWall.clear();
        }

        void frame(long now) throws Exception {
            if (visibility != null) { visibility.frame(); return; }
            if (!stageReady || waitingStage || waitingInitial) return;
            if (!measuring) {
                if (warmed || now < phaseDeadline) return;
                validate();
                if (cleanupOnly) {
                    finish("CLEANUP_PREFLIGHT_CAPTURED", "Native moving-deck/occluded-wall cleanup exercised; no frame-time measurements collected.");
                    return;
                }
                if (probeOnly) { beginVisibilityProbe(); return; }
                samples = 0; Arrays.fill(renderReturns, 0); Arrays.fill(fullTicks, 0); Arrays.fill(accessoryDispatches, 0);
                resetCounters();
                if (sableReducedBenchmark) resetRemoteMotionCounters();
                totalReturns = shadowReturns = fullyRenderedFrames = missingFrames = localRenderedFrames = localBodyReturns = 0;
                localFullTicks = localAccessoryDispatches = measuredTicks = localTrackedTicks = localUntrackedTicks = 0;
                firstPersonFrames = thirdPersonFrames = thirdPersonBodyFrames = 0; switchCount = 0;
                minimumCameraEyeDistance = Double.POSITIVE_INFINITY; maximumCameraEyeDistance = 0;
                measuredActualViewSamples = measuredCameraAngleLocks = 0;
                maxActualViewYawError = maxActualViewPitchError = 0;
                minSeen = Integer.MAX_VALUE; maxSeen = 0;
                report.put("phase", "MEASURE"); report.put("measurementStartedAt", Instant.now().toString());
                write("RUNNING", false, scene.id);
                for (int i = 0; i < collectors.size(); i++) { gcCountBefore[i] = collectors.get(i).getCollectionCount(); gcTimeBefore[i] = collectors.get(i).getCollectionTime(); }
                heapBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                previousFrame = measurementStart = System.nanoTime(); phaseDeadline = measurementStart + measureSeconds * 1_000_000_000L;
                nextSwitchAt = measurementStart + 2_000_000_000L;
                warmed = true; measuring = true; return;
            }
            if (samples >= intervals.length) throw new IllegalStateException("Preallocated frame sample capacity exceeded");
            frameCameras[samples] = mc.options.getCameraType().ordinal();
            if (mc.options.getCameraType().isFirstPerson()) firstPersonFrames++;
            else { thirdPersonFrames++; if (frameLocalReturns > 0) thirdPersonBodyFrames++; }
            intervals[samples++] = now - previousFrame; previousFrame = now;
            int seen = Long.bitCount(frameSeenMask); minSeen = Math.min(minSeen, seen); maxSeen = Math.max(maxSeen, seen);
            totalReturns += frameReturnedCalls;
            if (seen == scene.players) fullyRenderedFrames++; else missingFrames++;
            if (frameLocalReturns > 0) localRenderedFrames++;
            if (now < phaseDeadline) return;
            measuring = false;
            for (int i = 0; i < collectors.size(); i++) { gcCountAfter[i] = collectors.get(i).getCollectionCount(); gcTimeAfter[i] = collectors.get(i).getCollectionTime(); }
            heapAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            validate(); completeStage(now);
            stageReady = false;
            if (++sceneIndex == scenes.length) {
                if (visibilityProbe) beginVisibilityProbe();
                else finish("CAPTURED", "All scene measurements captured; requires visual and repeatability review.");
            }
            else startStage();
        }

        void completeStage(long now) throws Exception {
            long duration = now - measurementStart;
            long[] raw = Arrays.copyOf(intervals, samples), sorted = raw.clone(); Arrays.sort(sorted);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sceneId", scene.id); row.put("players", scene.players); row.put("walking", scene.walk);
            row.put("movingSublevel", scene.deck || scene.nearDeck); row.put("realWallOcclusion", scene.occluded);
            if (sableReducedBenchmark) row.put("sableFullTicksProperty", Boolean.getBoolean("emfattachments.sableFullTicks"));
            if (perspectiveComparison) {
                row.put("observerOnDeck", scene.deck); row.put("nearbyMovingDeck", scene.nearDeck);
                row.put("initialCameraType", scene.camera.name()); row.put("cameraTypeAtEnd", mc.options.getCameraType().name());
                row.put("frameCameraTypeOrdinals", Arrays.copyOf(frameCameras, samples));
                row.put("localUntrackedTicks", localUntrackedTicks);
                row.put("firstPersonFrameCount", firstPersonFrames); row.put("thirdPersonFrameCount", thirdPersonFrames);
                row.put("thirdPersonFramesLocalBodyReturned", thirdPersonBodyFrames);
                row.put("unlockedCameraActiveAtEnd", UnlockedState.active());
                row.put("minimumCameraEyeDistance", minimumCameraEyeDistance); row.put("maximumCameraEyeDistance", maximumCameraEyeDistance);
                List<Map<String, Object>> switches = new ArrayList<>();
                for (int i = 0; i < switchCount; i++) switches.add(Map.of("frameIndex", switchFrames[i], "offsetNanos", switchOffsets[i],
                        "from", CameraType.values()[switchFrom[i]].name(), "to", CameraType.values()[switchTo[i]].name()));
                row.put("cameraSwitches", switches);
            }
            row.put("continuedNativeDeckRide", continuedDeckRide);
            row.put("platformDriveTickAtSceneStart", stageDriveTickAtStart); row.put("platformDriveTickAtSceneEnd", driveTicks);
            row.put("sampleCount", samples); row.put("durationNanos", duration); row.put("frameIntervalsNanos", raw);
            row.put("meanFps", samples * 1e9 / duration); row.put("p50FrameMs", percentile(sorted, .5));
            row.put("p95FrameMs", percentile(sorted, .95)); row.put("p99FrameMs", percentile(sorted, .99)); row.put("maxFrameMs", sorted[samples - 1] / 1e6);
            row.put("over16_7ms", Arrays.stream(raw).filter(n -> n > 16_666_667).count());
            row.put("over33_3ms", Arrays.stream(raw).filter(n -> n > 33_333_333).count()); row.put("over50ms", Arrays.stream(raw).filter(n -> n > 50_000_000).count());
            row.put("measurementStartedAt", report.get("measurementStartedAt")); row.put("measurementEndedAt", Instant.now().toString());
            row.put("framesAllClonesReturned", fullyRenderedFrames); row.put("framesMissingSomeClone", missingFrames);
            row.put("minDistinctClonesRenderedPerFrame", minSeen); row.put("maxDistinctClonesRenderedPerFrame", maxSeen);
            row.put("nonShadowPlayerRendererReturns", totalReturns); row.put("irisShadowReturnsExcluded", shadowReturns);
            row.put("localBodyRendererReturns", localBodyReturns); row.put("framesLocalBodyReturned", localRenderedFrames);
            row.put("localAccessoryDispatches", localAccessoryDispatches); row.put("localNativeFullTicks", localFullTicks);
            row.put("measuredClientTicks", measuredTicks); row.put("localTrackedDeckTicks", localTrackedTicks);
            row.put("localPosition", vector(local.position())); row.put("cameraPosition", vector(mc.gameRenderer.getMainCamera().getPosition()));
            row.put("cameraYaw", mc.gameRenderer.getMainCamera().getYRot()); row.put("cameraPitch", mc.gameRenderer.getMainCamera().getXRot());
            row.put("cameraLocked", true); row.put("actualViewSamples", measuredActualViewSamples);
            row.put("cameraAngleLockEvents", measuredCameraAngleLocks);
            row.put("maximumActualViewYawError", maxActualViewYawError); row.put("maximumActualViewPitchError", maxActualViewPitchError);
            row.put("actualViewLockedEveryMeasuredFrame", measuredActualViewSamples >= samples && maxActualViewYawError <= .15F && maxActualViewPitchError <= .15F);
            row.put("localTrackingSublevel", tracking(local) == null ? "" : tracking(local).getUniqueId().toString());
            row.put("maximumLocalPlotHorizontalDrift", maxLocalPlotDrift);
            row.put("localSkin", skin(local)); row.put("renderSettings", settings(mc));
            row.put("firstPersonEnabled", ModList.get().isLoaded("firstperson") && FirstPersonState.enabled());
            List<Map<String, Object>> players = new ArrayList<>();
            boolean allObserved = true, allAccessoriesObserved = true;
            for (CrowdPlayer clone : clones) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("index", clone.index); p.put("entityId", clone.getId()); p.put("loadout", clone.gear.metadata());
                p.put("skin", skin(clone)); p.put("worldPosition", vector(clone.position()));
                p.put("nativeFullTicks", fullTicks[clone.index]); p.put("nativeRendererReturns", renderReturns[clone.index]);
                p.put("accessoryDispatches", accessoryDispatches[clone.index]); p.put("controllerMovementTicks", clone.movementTicks); p.put("cumulativePath", clone.cumulativePath);
                if (sableReducedBenchmark) {
                    int i = clone.index;
                    p.put("nativeAiSteps", aiSteps[i]); p.put("independentPlotTargetUpdates", plotTargetUpdates[i]);
                    p.put("nativeMotionSamples", motionSamples[i]); p.put("nativeMovingSamples", movingSamples[i]);
                    p.put("nativeMeasuredTravelBlocks", measuredPaths[i]); p.put("nativeCulledSamples", culledSamples[i]);
                    p.put("nativeOutOfCameraSamples", outOfCameraSamples[i]); p.put("lostSupportSamples", lostSupportSamples[i]);
                    p.put("maximumNativePlotWorldError", maxPlotWorldErrors[i]); p.put("maximumPlotTargetLag", maxTargetLags[i]);
                    p.put("lastIndependentPlotTarget", clone.lastPlotTarget == null ? List.of() : vector(clone.lastPlotTarget));
                    p.put("nativeCullingAtMeasurementEnd", NowheelTelemetry.culling(clone));
                    boolean motionValid = motionSamples[i] > 0 && plotTargetUpdates[i] > 0 && lostSupportSamples[i] == 0
                            && aiSteps[i] >= measuredTicks * .9 && measuredPaths[i] > .5
                            && maxPlotWorldErrors[i] <= .25 && maxTargetLags[i] <= .5;
                    boolean cullingValid = !scene.occluded || culledSamples[i] >= motionSamples[i] * .9;
                    boolean tickModeValid = !scene.occluded || (Boolean.getBoolean("emfattachments.sableFullTicks")
                            ? fullTicks[i] >= measuredTicks * .9 : fullTicks[i] < measuredTicks * .1);
                    p.put("nativeMotionValidated", motionValid); p.put("hiddenCullingValidated", cullingValid);
                    p.put("hiddenTickModeValidated", tickModeValid);
                    if (!motionValid || !cullingValid || !tickModeValid)
                        behaviorFailures.add(Map.of("scope", scene.id, "index", i, "nativeMotionValidated", motionValid,
                                "hiddenCullingValidated", cullingValid, "hiddenTickModeValidated", tickModeValid));
                }
                if (nowheelComparison) {
                    p.put("nowheelPredicateSamples", predicateSamples[clone.index]);
                    p.put("nowheelPredicateTrueSamples", predicateTrue[clone.index]);
                    p.put("nativeDynamicWhitelistTrueSamples", dynamicTrue[clone.index]);
                    p.put("nowheelPredicateAtMeasurementEnd", nowheel.predicate(clone));
                    p.put("nativeCullingAtMeasurementEnd", NowheelTelemetry.culling(clone));
                }
                p.put("resident", level.getEntity(clone.getId()) == clone);
                p.put("trackingMatchesOwnedDeck", tracking(clone) == clientDeck);
                p.put("trackingSublevel", tracking(clone) == null ? "" : tracking(clone).getUniqueId().toString());
                Vec3 plot = ((EntityStickExtension)(Object)clone).sable$getPlotPosition();
                p.put("plotPosition", plot == null ? List.of() : vector(plot));
                if (scene.deck && plot != null) p.put("nativePlotWorldPositionError", clientDeck.logicalPose().transformPosition(plot).distanceTo(clone.position()));
                players.add(p); allObserved &= renderReturns[clone.index] > 0; allAccessoriesObserved &= accessoryDispatches[clone.index] > 0;
            }
            row.put("entities", players); row.put("everyCloneObserved", allObserved); row.put("everyCloneAccessoryDispatcherObserved", allAccessoriesObserved);
            row.put("openSceneEveryCloneEveryFrame", scene.occluded ? null : missingFrames == 0);
            row.put("rendererWorkloadComparable", scene.occluded ? null : allObserved && missingFrames == 0 && allAccessoriesObserved);
            row.put("occlusionWorkloadDifferenceExpected", scene.occluded);
            if (sableReducedBenchmark) {
                if (!scene.occluded && (!allObserved || !allAccessoriesObserved || missingFrames != 0))
                    behaviorFailures.add(Map.of("scope", scene.id, "finding", "Open scene missed native player/accessory rendering",
                            "everyCloneObserved", allObserved, "everyCloneAccessoryDispatcherObserved", allAccessoriesObserved,
                            "framesMissingSomeClone", missingFrames));
                boolean valid = behaviorFailures.stream().noneMatch(f -> scene.id.equals(f.get("scope")));
                row.put("nativeMotionAndTickModeValidated", valid);
                row.put("occlusionWorkloadDifferenceExpected", false);
            }
            List<Map<String, Object>> gc = new ArrayList<>();
            for (int i = 0; i < collectors.size(); i++) gc.add(Map.of("name", collectors.get(i).getName(),
                    "collectionCountDelta", gcCountAfter[i] - gcCountBefore[i], "collectionTimeMillisDelta", gcTimeAfter[i] - gcTimeBefore[i]));
            row.put("gc", gc); row.put("heapUsedBefore", heapBefore); row.put("heapUsedAfter", heapAfter);
            row.put("platformLogicalPosition", vector(new Vec3(clientDeck.logicalPose().position().x(), clientDeck.logicalPose().position().y(), clientDeck.logicalPose().position().z())));
            row.put("nativeWallBlocks", originalWall.size());
            String image = String.format(java.util.Locale.ROOT, "%02d-%s-after-measurement.png", sceneIndex + 1, scene.id);
            try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { pixels.writeToFile(output.resolve(image)); }
            row.put("afterMeasurementScreenshot", image); row.put("visualValidationPending", true);
            stages.add(row); write("RUNNING", false, scene.id + " complete");
            if (!sableReducedBenchmark && sceneIndex == 0 && ModList.get().isLoaded("firstperson") && localBodyReturns == 0)
                throw new IllegalStateException("FirstPerson look-down scene did not render the actual local body");
            if (!sableReducedBenchmark && !scene.occluded && (!allObserved || !allAccessoriesObserved || missingFrames != 0)) {
                if (nowheelComparison) behaviorFailures.add(Map.of("scope", scene.id, "finding", "Open scene missed requested native player/accessory rendering", "everyCloneObserved", allObserved,
                        "everyCloneAccessoryDispatcherObserved", allAccessoriesObserved, "framesMissingSomeClone", missingFrames));
                else if (!allObserved || !allAccessoriesObserved)
                    throw new IllegalStateException("Open scene did not execute every requested native player/accessory renderer; report retained as invalid workload");
            }
        }

        void removeClones() {
            RuntimeException restorationFailure = null;
            for (CrowdPlayer clone : clones) {
                try {
                    if (clone.gear != null) { clone.gear.close(); allRetiredGearRestored &= clone.gear.restored(); }
                } catch (RuntimeException failure) {
                    allRetiredGearRestored = false;
                    if (restorationFailure == null) restorationFailure = failure; else restorationFailure.addSuppressed(failure);
                } finally {
                    if (level.getEntity(clone.getId()) == clone) level.removeEntity(clone.getId(), Entity.RemovalReason.DISCARDED);
                    retiredCloneIds.add(clone.getId());
                }
            }
            clones.clear();
            if (restorationFailure != null) throw restorationFailure;
        }

        void finish(String status, String detail) {
            if (finishing) return;
            finishing = true; measuring = false; stageReady = false; finalStatus = status; finalDetail = detail;
            keys.keySet().forEach(key -> key.setDown(false));
            try { removeClones(); if (clientGear != null) clientGear.close(); }
            catch (Throwable failure) { finalStatus = "FAIL"; finalDetail += "; client restoration: " + failure; }
            report.put("clientBeforeRestoration", motionState(local));
            restorationDeadline = System.nanoTime() + 20_000_000_000L;
            // Keep the owned observer still until the support removal reaches the client.
            local.setNoGravity(true); local.setDeltaMovement(Vec3.ZERO);
            clearOwnedSupport(local); ((LivingEntityMovementExtension)local).sable$getInheritedVelocity().zero();
            mc.options.setCameraType(originalCamera); mc.setCameraEntity(originalCameraEntity);
            local.setYRot(originalYaw); local.yRotO = originalYaw; local.setXRot(originalPitch); local.xRotO = originalPitch;
            restoration = server(p -> {
                restoreWall(p);
                if (serverGear != null) serverGear.close();
                serverBeforeRestoration = motionState(p);
                if (platform != null && !platform.isRemoved()) SubLevelContainer.getContainer(p.serverLevel()).removeSubLevel(platform, SubLevelRemovalReason.REMOVED);
                clearOwnedSupport(p); ((LivingEntityMovementExtension)p).sable$getInheritedVelocity().zero();
                p.setNoGravity(true); p.setDeltaMovement(Vec3.ZERO);
                return null;
            });
            write("RESTORING", false, detail);
        }

        void restoreTick() {
            try {
                if (System.nanoTime() > restorationDeadline) throw new IllegalStateException("Bounded restoration deadline exceeded");
                if (restoration == null || !restoration.isDone()) return;
                restoration.join();
                report.put("serverBeforeRestoration", serverBeforeRestoration);
                boolean removed = platformId == null || SubLevelContainer.getContainer(level).getSubLevel(platformId) == null;
                if (!removed) return;
                if (authoritativeRestore == null) {
                    clearOwnedSupport(local);
                    ((LivingEntityMovementExtension)local).sable$getInheritedVelocity().set(originalClientInheritedVelocity.x, originalClientInheritedVelocity.y, originalClientInheritedVelocity.z);
                    ((EntityMovementExtension)local).sable$setLastTrackingSubLevelID(originalClientLastSupport);
                    authoritativeRestore = server(p -> {
                        clearOwnedSupport(p);
                        if (serverSnapshot != null) serverSnapshot.restore(p);
                        return serverSnapshot == null ? new RestoreAudit(true, Map.of()) : serverSnapshot.audit(p, true);
                    });
                    return;
                }
                if (!authoritativeRestore.isDone()) return;
                RestoreAudit immediate = authoritativeRestore.join();
                if (!clientMotionRestored) {
                    local.setNoGravity(originalClientNoGravity);
                    local.setDeltaMovement(originalClientMovement);
                    ((LivingEntityMovementExtension)local).sable$getInheritedVelocity().set(originalClientInheritedVelocity.x, originalClientInheritedVelocity.y, originalClientInheritedVelocity.z);
                    ((EntityMovementExtension)local).sable$setLastTrackingSubLevelID(originalClientLastSupport);
                    clientMotionRestored = true;
                }
                if (++restoreTicks < 25) return;
                if (authoritativeCheck == null) {
                    authoritativeCheck = server(p -> serverSnapshot == null ? new RestoreAudit(true, Map.of()) : serverSnapshot.audit(p, false));
                    return;
                }
                if (!authoritativeCheck.isDone()) return;
                RestoreAudit settled = authoritativeCheck.join();
                Map<String, Object> clientState = motionState(local);
                boolean clientPosition = serverSnapshot == null || local.position().distanceToSqr(serverSnapshot.position) < .01;
                boolean clientPhysics = inheritedVelocity(local).distanceToSqr(originalClientInheritedVelocity) < 1e-12
                        && local.isNoGravity() == originalClientNoGravity && local.getAbilities().flying == originalClientFlying;
                boolean clientSupport = tracking(local) == null && ((EntityStickExtension)local).sable$getPlotPosition() == null
                        && java.util.Objects.equals(((EntityMovementExtension)local).sable$getLastTrackingSubLevelID(), originalClientLastSupport);
                boolean clientRotation = serverSnapshot == null
                        || Math.abs(net.minecraft.util.Mth.wrapDegrees(local.getYRot() - serverSnapshot.yaw)) < .01
                        && Math.abs(local.getXRot() - serverSnapshot.pitch) < .01;
                report.put("restorationEvidence", Map.of("immediateServer", immediate.details, "settledServer", settled.details,
                        "client", clientState, "clientPositionMatches", clientPosition, "clientPhysicsMatches", clientPhysics,
                        "clientSupportMatches", clientSupport, "clientRotationMatchesAuthoritativeSnapshot", clientRotation, "settleTicks", restoreTicks));
                keys.forEach(KeyMapping::setDown);
                Map<String, Boolean> cleanup = new LinkedHashMap<>();
                cleanup.put("ownedClonesRemoved", clones.isEmpty() && retiredCloneIds.stream().allMatch(id -> level.getEntity(id) == null));
                cleanup.put("ownedCloneGearRestored", allRetiredGearRestored);
                cleanup.put("localClientGearRestored", clientGear == null || clientGear.restored());
                cleanup.put("localServerGearRestored", serverGear == null || serverGear.restored());
                cleanup.put("platformRemoved", removed);
                cleanup.put("wallRestored", originalWall.isEmpty());
                cleanup.put("cameraRestored", mc.options.getCameraType() == originalCamera && mc.getCameraEntity() == originalCameraEntity);
                cleanup.put("inputsRestored", keys.entrySet().stream().allMatch(e -> e.getKey().isDown() == e.getValue()));
                cleanup.put("clientGravityRestored", local.isNoGravity() == originalClientNoGravity);
                cleanup.put("serverStateRestored", immediate.matches && settled.matches);
                cleanup.put("clientStateRestored", clientPosition && clientPhysics && clientSupport && clientRotation);
                report.put("cleanup", cleanup);
                report.put("ownedClonesCreated", ownedClonesCreated);
                report.put("retiredCloneReferencesRetained", false);
                if (cleanup.values().stream().anyMatch(v -> !v)) { finalStatus = "FAIL"; finalDetail += "; a restoration check failed"; }
                write(finalStatus, true, finalDetail);
            } catch (Throwable failure) { write("FAIL", true, "Restoration failure: " + failure); }
            active = null;
        }

        void clearOwnedSupport(LivingEntity entity) {
            EntityMovementExtension movement = (EntityMovementExtension)entity;
            SubLevel support = movement.sable$getTrackingSubLevel();
            if (support != null && platformId != null && platformId.equals(support.getUniqueId())) movement.sable$setTrackingSubLevel(null);
            // Entry requires ordinary ground/no plot position; these are fixture-created bindings only.
            ((EntityStickExtension)entity).sable$setPlotPosition(null);
            var collision = movement.sable$getCollisionInfo();
            if (collision != null && (collision.trackingSubLevel == platform || collision.trackingSubLevel == clientDeck
                    || collision.preTrackingSubLevel == platform || collision.preTrackingSubLevel == clientDeck)) {
                collision.trackingSubLevel = null; collision.preTrackingSubLevel = null; collision.inheritedMotion = null;
                if (collision.firstCollisions != null) {
                    collision.firstCollisions.remove(platform); collision.firstCollisions.remove(clientDeck);
                }
            }
        }

        void write(String status, boolean finished, String detail) {
            report.put("status", status); report.put("finished", finished); report.put("detail", detail);
            try { Files.writeString(output.resolve("equipped-crowd-frames.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch (Exception failure) { throw new IllegalStateException("Cannot save benchmark evidence", failure); }
        }
    }

    /** Functional evidence only: after timing, keep the wall and allow native entity ticks to carry actors. */
    private static final class VisibilityProbe {
        private static final String[] IDS = {"hidden-moving", "emerge-past-wall", "exposed-hold", "camera-away",
                "camera-reentry", "return-behind-wall", "rehidden-hold"};
        private static final int[] LENGTHS = {80, 120, 80, 60, 80, 120, 80};
        final Session s;
        final Vec3 observer;
        final Vec3[] plots = new Vec3[8];
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<Map<String, Object>> phases = new ArrayList<>(), samples = new ArrayList<>(), failures = new ArrayList<>();
        final long started = System.nanoTime();
        CompletableFuture<?> preparing;
        volatile int phase, phaseTicks;
        volatile boolean sampling;
        boolean ready, closePending;
        int readyTicks, preparationTicks;
        long frameCount, normalFrames, allFrames;

        VisibilityProbe(Session s) {
            this.s = s; observer = s.deckObserver.add(0, 0, -6);
            if (s.clones.size() != 8 || s.originalWall.size() != 279) throw new IllegalStateException("Probe requires the existing eight-actor/wall scene");
            for (CrowdPlayer clone : s.clones) {
                Vec3 plot = ((EntityStickExtension)(Object)clone).sable$getPlotPosition();
                if (plot == null) throw new IllegalStateException("Probe actor has no native pending plot binding");
                plots[clone.index] = plot;
                clone.setDeltaMovement(Vec3.ZERO);
            }
            report.put("requested", true); report.put("finished", false); report.put("excludedFromFrameTimings", true);
            report.put("wallRetainedThroughout", true); report.put("actorPositionRepairWrites", 0);
            report.put("actorVelocityZeroedOnceBeforeProbe", true);
            report.put("exposedHoldStalePositionThresholdBlocks", 1);
            report.put("controller", "Same eight actors retain their native pending plot bindings. During probes their fixture override calls only super.tick; no setPos, plot update, culling flag write or noCulling bypass. Native server physics pipeline moves the same deck past a persistent world wall.");
            report.put("fixedWorldObserver", vector(observer)); report.put("exposedLateralShiftBlocks", 40);
            report.put("cameraYawTowardDeck", -30); report.put("cameraPitch", 5);
            report.put("phases", phases); report.put("behaviorFailures", failures);
            report.put("geometryPolicy", "Native world clip from fixed observer eye to independently transformed expected actor box center and actual box center. Expected exposed centers must have clear wall rays and remain in the locked camera cone; hidden holds must have blocked expected rays. Actual actor failures are recorded separately.");
            s.report.put("comparisonProbes", report);
        }

        float yaw() { return phase == 3 ? 150 : -30; }

        void prepare() {
            s.report.put("phase", "PROBE_PREPARATION");
            s.clearOwnedSupport(s.local); ((LivingEntityMovementExtension)s.local).sable$getInheritedVelocity().zero();
            s.local.setNoGravity(true); s.local.setDeltaMovement(Vec3.ZERO);
            preparing = s.server(p -> {
                s.clearOwnedSupport(p); ((LivingEntityMovementExtension)p).sable$getInheritedVelocity().zero();
                p.setNoGravity(true); p.getAbilities().flying = true; p.onUpdateAbilities();
                p.teleportTo(p.serverLevel(), observer.x, observer.y, observer.z, -30, 5);
                p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
                return null;
            });
            s.write("RUNNING", false, "Untimed natural visibility probe preparation");
        }

        void drive() {
            double t = Math.min(phaseTicks / (double)LENGTHS[phase], 1);
            double x = switch (phase) {
                case 1 -> 40 * t;
                case 2, 3, 4 -> 40;
                case 5 -> 40 * (1 - t);
                default -> .5 * Math.sin(s.driveTicks * Math.PI / 100);
            };
            double y = s.platformBase.y + .25 * Math.sin(s.driveTicks++ * Math.PI / 50);
            var pipeline = SubLevelContainer.getContainer(s.platform.getLevel()).physicsSystem().getPipeline();
            pipeline.resetVelocity(s.platform);
            pipeline.teleport(s.platform, new Vector3d(s.platformBase.x + x, y, s.platformBase.z), new Quaterniond());
        }

        void tick() {
            if (!ready) {
                if (++preparationTicks > 240) throw new IllegalStateException("Fixed world observer did not settle for visibility probe");
                if (!preparing.isDone()) return;
                preparing.join();
                boolean position = s.local.position().distanceToSqr(observer) < .0025 && tracking(s.local) == null;
                readyTicks = position ? readyTicks + 1 : 0;
                if (readyTicks < 12) return;
                s.local.setNoGravity(true); s.local.getAbilities().flying = true;
                ready = true; s.stageReady = true; beginPhase(); return;
            }
            s.lockCamera(); s.validate();
            if (closePending) return;
            phaseTicks++;
            if (phaseTicks % 20 == 0) s.samplePredicates();
            if (phaseTicks % 4 == 0) sample();
            if (phaseTicks == LENGTHS[phase]) closePending = true;
        }

        void validateObserver() {
            if (s.clientDeck.isRemoved() || s.local.position().distanceToSqr(observer) > .0025 || tracking(s.local) != null)
                throw new IllegalStateException("Fixed untimed world observer or owned deck changed");
        }

        void beginPhase() {
            phaseTicks = 0; frameCount = normalFrames = allFrames = 0;
            samples.clear(); s.resetCounters(); closePending = false;
            s.lockCamera(); sampling = true;
            s.report.put("phase", "PROBE:" + IDS[phase]);
            s.write("RUNNING", false, "Untimed native visibility phase " + (phase + 1) + "/7: " + IDS[phase]);
        }

        void sample() {
            Vec3 eye = s.mc.gameRenderer.getMainCamera().getPosition();
            double hfov = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(s.mc.options.fov().get() / 2.0))
                    * s.mc.getWindow().getWidth() / s.mc.getWindow().getHeight()));
            for (CrowdPlayer clone : s.clones) {
                Vec3 expected = s.clientDeck.logicalPose().transformPosition(plots[clone.index]);
                Vec3 expectedCenter = expected.add(0, clone.getBbHeight() / 2, 0);
                Vec3 actualCenter = clone.getBoundingBox().getCenter();
                boolean expectedBlocked = wallBlocks(eye, expectedCenter), actualBlocked = wallBlocks(eye, actualCenter);
                Vec3 direction = expectedCenter.subtract(eye);
                float targetYaw = (float)Math.toDegrees(Math.atan2(-direction.x, direction.z));
                float targetPitch = (float)-Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
                boolean inCone = Math.abs(net.minecraft.util.Mth.wrapDegrees(targetYaw - yaw())) < hfov / 2 - 3
                        && Math.abs(targetPitch - 5) < s.mc.options.fov().get() / 2.0 - 3;
                if ((phase == 2 || phase == 4) && (expectedBlocked || !inCone))
                    throw new IllegalStateException("Visibility probe exposed geometry invalid for actor " + clone.index + " blocked=" + expectedBlocked + " inCone=" + inCone);
                if ((phase == 0 || phase == 6) && !expectedBlocked)
                    throw new IllegalStateException("Visibility probe hidden hold did not retain expected wall obstruction for actor " + clone.index);
                Map<String, Object> row = new LinkedHashMap<>(NowheelTelemetry.culling(clone));
                row.put("phaseTick", phaseTicks); row.put("index", clone.index);
                row.put("worldPosition", vector(clone.position())); row.put("expectedWorldPosition", vector(expected));
                row.put("nativePlotWorldPositionError", expected.distanceTo(clone.position()));
                row.put("expectedWallBlocksSight", expectedBlocked); row.put("actualWallBlocksSight", actualBlocked);
                row.put("expectedCenterInCameraCone", inCone); row.put("nowheelPredicate", s.nowheel.predicate(clone));
                row.put("trackingMatchesOwnedDeck", tracking(clone) == s.clientDeck);
                row.put("nativeTickCount", clone.tickCount); row.put("cameraEye", vector(eye));
                samples.add(row);
            }
        }

        boolean wallBlocks(Vec3 eye, Vec3 target) {
            var hit = s.level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, s.local));
            return hit.getType() == HitResult.Type.BLOCK && s.originalWall.containsKey(hit.getBlockPos());
        }

        void frame() throws Exception {
            if (!sampling) return;
            frameCount++;
            int seen = Long.bitCount(s.frameSeenMask);
            if (seen > 0) normalFrames++;
            if (seen == 8) allFrames++;
            if (!closePending) return;
            sampling = false;
            if (s.originalWall.size() != 279 || s.originalWall.keySet().stream().anyMatch(p -> !s.level.getBlockState(p).is(Blocks.QUARTZ_BLOCK)))
                throw new IllegalStateException("Persistent native wall geometry changed during probe");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phaseId", IDS[phase]); row.put("ticks", phaseTicks); row.put("frameCount", frameCount);
            row.put("normalRenderedFrames", normalFrames); row.put("allClonesReturnedFrames", allFrames);
            row.put("actualViewLocked", s.measuredActualViewSamples > 0 && s.maxActualViewYawError <= .15F && s.maxActualViewPitchError <= .15F);
            row.put("maximumActualViewYawError", s.maxActualViewYawError); row.put("maximumActualViewPitchError", s.maxActualViewPitchError);
            row.put("samples", List.copyOf(samples));
            List<Map<String, Object>> entities = new ArrayList<>();
            for (CrowdPlayer clone : s.clones) {
                entities.add(Map.of("index", clone.index, "nativeFullTicks", s.fullTicks[clone.index],
                        "nativeRendererReturns", s.renderReturns[clone.index], "accessoryDispatches", s.accessoryDispatches[clone.index],
                        "nowheelPredicateSamples", s.predicateSamples[clone.index], "nowheelPredicateTrueSamples", s.predicateTrue[clone.index]));
                double maxError = samples.stream().filter(v -> ((Number)v.get("index")).intValue() == clone.index)
                        .mapToDouble(v -> ((Number)v.get("nativePlotWorldPositionError")).doubleValue()).max().orElse(0);
                if ((phase == 2 || phase == 4) && (s.renderReturns[clone.index] == 0 || maxError > 1)) {
                    Map<String, Object> failure = Map.of("scope", IDS[phase], "index", clone.index,
                            "finding", "Expected exposed actor failed normal rendering or retained stale world position",
                            "nativeRendererReturns", s.renderReturns[clone.index], "maximumPositionError", maxError);
                    failures.add(failure); s.behaviorFailures.add(failure);
                }
            }
            row.put("entities", entities);
            String image = "probe-" + (phase + 1) + "-" + IDS[phase] + ".png";
            try (NativeImage pixels = Screenshot.takeScreenshot(s.mc.getMainRenderTarget())) { pixels.writeToFile(s.output.resolve(image)); }
            row.put("afterProbeScreenshot", image); phases.add(row);
            if (phase + 1 < IDS.length) { phase++; beginPhase(); return; }
            report.put("finished", true); report.put("durationNanos", System.nanoTime() - started);
            report.put("nativeGeometryValidated", true);
            s.finish(s.probeOnly ? "VISIBILITY_PREFLIGHT_CAPTURED" : "CAPTURED", "All requested timing/untimed natural visibility evidence captured; native behavior findings retained separately.");
        }
    }

    private record RestoreAudit(boolean matches, Map<String, Object> details) {}

    private record Snapshot(Vec3 position, Vec3 movement, Vec3 inherited, UUID lastSupport,
                            float yaw, float pitch, float fallDistance, boolean flying, boolean noGravity, GameType gameType) {
        Snapshot(ServerPlayer p) { this(p.position(), p.getDeltaMovement(), inheritedVelocity(p),
                ((EntityMovementExtension)p).sable$getLastTrackingSubLevelID(), p.getYRot(), p.getXRot(), p.fallDistance,
                p.getAbilities().flying, p.isNoGravity(), p.gameMode.getGameModeForPlayer()); }
        void restore(ServerPlayer p) {
            p.setGameMode(gameType); p.getAbilities().flying = flying; p.onUpdateAbilities(); p.setNoGravity(noGravity);
            p.teleportTo(p.serverLevel(), position.x, position.y, position.z, yaw, pitch); p.setDeltaMovement(movement); p.fallDistance = fallDistance;
            ((LivingEntityMovementExtension)p).sable$getInheritedVelocity().set(inherited.x, inherited.y, inherited.z);
            ((EntityMovementExtension)p).sable$setLastTrackingSubLevelID(lastSupport);
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
        }
        Map<String, Object> description() {
            return Map.of("position", vector(position), "movement", vector(movement), "inheritedVelocity", vector(inherited),
                    "lastSupport", lastSupport == null ? "" : lastSupport.toString(), "yaw", yaw, "pitch", pitch,
                    "fallDistance", fallDistance, "flying", flying, "noGravity", noGravity, "gameType", gameType.getName());
        }
        RestoreAudit audit(ServerPlayer p, boolean immediate) {
            Map<String, Object> state = motionState(p);
            boolean positionMatches = p.position().distanceToSqr(position) < .01;
            boolean inheritedMatches = inheritedVelocity(p).distanceToSqr(inherited) < 1e-12;
            boolean supportMatches = tracking(p) == null && ((EntityStickExtension)p).sable$getPlotPosition() == null
                    && java.util.Objects.equals(((EntityMovementExtension)p).sable$getLastTrackingSubLevelID(), lastSupport);
            boolean flagsMatch = p.getAbilities().flying == flying && p.isNoGravity() == noGravity
                    && p.gameMode.getGameModeForPlayer() == gameType;
            boolean rotationMatches = Math.abs(net.minecraft.util.Mth.wrapDegrees(p.getYRot() - yaw)) < .01
                    && Math.abs(p.getXRot() - pitch) < .01;
            boolean motionMatches = p.getDeltaMovement().distanceToSqr(movement) < 1e-12;
            boolean fallMatches = Math.abs(p.fallDistance - fallDistance) < 1e-6;
            state.put("positionMatches", positionMatches); state.put("inheritedVelocityMatches", inheritedMatches);
            state.put("supportMatches", supportMatches); state.put("flagsMatch", flagsMatch); state.put("rotationMatches", rotationMatches);
            state.put("movementMatches", motionMatches); state.put("fallDistanceMatches", fallMatches);
            state.put("movementValidation", immediate ? "Exact restored movement before physics advances" : "Movement recorded after normal physics; strict restoration checked at teleport boundary");
            boolean matches = positionMatches && inheritedMatches && supportMatches && flagsMatch && rotationMatches
                    && (!immediate || motionMatches && fallMatches);
            state.put("matches", matches);
            return new RestoreAudit(matches, state);
        }
    }

    private static Vec3 inheritedVelocity(LivingEntity entity) {
        var motion = ((LivingEntityMovementExtension)entity).sable$getInheritedVelocity();
        return new Vec3(motion.x, motion.y, motion.z);
    }

    private static Map<String, Object> motionState(LivingEntity entity) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("position", vector(entity.position())); state.put("movement", vector(entity.getDeltaMovement()));
        state.put("inheritedVelocity", vector(inheritedVelocity(entity))); state.put("noGravity", entity.isNoGravity());
        state.put("onGround", entity.onGround()); state.put("yaw", entity.getYRot()); state.put("pitch", entity.getXRot());
        state.put("fallDistance", entity.fallDistance);
        SubLevel support = tracking(entity);
        state.put("trackingSublevel", support == null ? "" : support.getUniqueId().toString());
        UUID last = ((EntityMovementExtension)entity).sable$getLastTrackingSubLevelID();
        state.put("lastSupport", last == null ? "" : last.toString());
        Vec3 plot = ((EntityStickExtension)entity).sable$getPlotPosition(); state.put("plotPosition", plot == null ? List.of() : vector(plot));
        if (entity instanceof net.minecraft.world.entity.player.Player player) state.put("flying", player.getAbilities().flying);
        if (entity instanceof ServerPlayer player) state.put("gameType", player.gameMode.getGameModeForPlayer().getName());
        return state;
    }

    private static SubLevel tracking(Entity entity) { return ((EntityMovementExtension)entity).sable$getTrackingSubLevel(); }
    private static List<Double> vector(Vec3 v) { return List.of(v.x, v.y, v.z); }
    private static double percentile(long[] sorted, double fraction) { return sorted[Math.max(0, (int)Math.ceil(sorted.length * fraction) - 1)] / 1e6; }
    private static Map<String, Object> skin(AbstractClientPlayer player) {
        PlayerSkin s = player.getSkin();
        return Map.of("model", s.model().id(), "texture", s.texture().toString(), "cape", s.capeTexture() == null ? "" : s.capeTexture().toString(),
                "elytra", s.elytraTexture() == null ? "" : s.elytraTexture().toString(), "nativeGetSkinPath", true);
    }
    private static Map<String, Object> settings(Minecraft mc) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("maxFps", mc.options.framerateLimit().get()); values.put("vsync", mc.options.enableVsync().get());
        values.put("renderDistance", mc.options.renderDistance().get()); values.put("simulationDistance", mc.options.simulationDistance().get());
        values.put("fov", mc.options.fov().get()); values.put("width", mc.getWindow().getWidth()); values.put("height", mc.getWindow().getHeight());
        values.put("fullscreen", mc.getWindow().isFullscreen());
        values.put("shadersEnabled", IRIS && net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse());
        values.put("focused", mc.isWindowActive()); values.put("hideGui", mc.options.hideGui); return values;
    }
    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (int depth = 0; failure != null && depth < 5; depth++, failure = failure.getCause()) {
            if (!text.isEmpty()) text.append("; caused by ");
            text.append(failure.getClass().getSimpleName()).append(": ").append(failure.getMessage());
        }
        return text.toString();
    }
    private static Vec3 formation(int i) {
        double forward = 16 + i % 4, side = forward * Math.tan(Math.toRadians(-27.6 + i * 2.4));
        return new Vec3(side, 0, forward);
    }
    private static Grid findGrid(ClientLevel level, Vec3 origin) {
        for (float yaw : new float[]{0, 90, 180, 270}) {
            double angle = Math.toRadians(yaw), fx = -Math.sin(angle), fz = Math.cos(angle), rx = Math.cos(angle), rz = Math.sin(angle);
            List<Vec3> positions = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                Vec3 relative = formation(i);
                Vec3 p = new Vec3(origin.x + fx * relative.z + rx * relative.x, origin.y, origin.z + fz * relative.z + rz * relative.x);
                BlockPos below = BlockPos.containing(p.x, p.y - .05, p.z);
                var shape = level.getBlockState(below).getCollisionShape(level, below);
                if (!level.hasChunkAt(below) || shape.isEmpty() || Math.abs(below.getY() + shape.max(Direction.Axis.Y) - p.y) > .08
                        || !level.noCollision(null, new AABB(p.x - 1, p.y + .01, p.z - .7, p.x + 1, p.y + 1.85, p.z + .7))) break;
                positions.add(p);
            }
            if (positions.size() == 24) return new Grid(origin, yaw, positions);
        }
        throw new IllegalStateException("No existing flat unobstructed 24-player world grid; no world terrain altered");
    }
}
