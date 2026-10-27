package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
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
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Controlled client-only crowd comparison; not a multiplayer or absolute-FPS benchmark. */
public final class CrowdFrameSequence {
    private static final UUID OFFLINE_ID = UUID.nameUUIDFromBytes("OfflinePlayer:AttachmentTest".getBytes(StandardCharsets.UTF_8));
    private static final int[] COUNTS = {0, 8, 24};
    private static final boolean IRIS = ModList.get().isLoaded("iris");
    private static Session active;
    private static int pollTicks;

    private CrowdFrameSequence() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CrowdFrameSequence::tick);
        NeoForge.EVENT_BUS.addListener(CrowdFrameSequence::beforeFrame);
        NeoForge.EVENT_BUS.addListener(CrowdFrameSequence::afterFrame);
    }

    private static boolean disposable(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.hasSingleplayerServer()
                && !mc.getSingleplayerServer().isPublished()
                && mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                && mc.getUser().getType() == User.Type.LEGACY
                && "AttachmentTest".equals(mc.getUser().getName()) && OFFLINE_ID.equals(mc.getUser().getProfileId())
                && "AttachmentTest".equals(mc.player.getGameProfile().getName()) && OFFLINE_ID.equals(mc.player.getUUID());
    }

    private static void tick(ClientTickEvent.Post ignored) {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) {
            try { active.validate(); }
            catch (Throwable failure) { active.finish("FAIL", failure.toString()); }
            return;
        }
        if (++pollTicks % 20 != 0 || !disposable(mc) || mc.screen != null || !mc.isWindowActive()) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-crowd-frames.txt");
        if (!Files.isRegularFile(trigger) || !NativeWorldReadiness.ready()) return;
        Path out = mc.gameDirectory.toPath().resolve("emfattachments-tests/crowd-frames");
        try {
            List<String> lines = Files.readAllLines(trigger);
            String label = (lines.isEmpty() ? "crowd-frames" : lines.getFirst().trim()).replaceAll("[^a-zA-Z0-9._-]", "_");
            if (label.isBlank() || label.equals(".") || label.equals("..")) label = "crowd-frames";
            int warmup = 15, measure = 20;
            for (String line : lines) {
                if (line.trim().startsWith("warmupSeconds=")) warmup = Integer.parseInt(line.trim().substring(14));
                if (line.trim().startsWith("measureSeconds=")) measure = Integer.parseInt(line.trim().substring(15));
            }
            if (warmup < 5 || warmup > 60 || measure < 5 || measure > 60) throw new IllegalArgumentException("warmupSeconds and measureSeconds must each be5..60");
            out = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);
            Files.createDirectories(out);
            Files.delete(trigger);
            Session next = new Session(mc, out, warmup, measure);
            active = next;
            try { next.startStage(); } catch (Throwable failure) { next.finish("FAIL", failure.toString()); }
        } catch (Throwable failure) {
            try { Files.createDirectories(out); Files.writeString(out.resolve("crowd-frames.json"), new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                    "status", "FAIL", "finished", true, "startupFailure", failure.toString(), "observedAt", Instant.now().toString()))); }
            catch (Exception ignoredWriteFailure) {}
        }
    }

    private static void beforeFrame(RenderFrameEvent.Pre ignored) {
        Session session = active;
        if (session != null && session.measuring) { session.frameSeenMask = 0; session.frameReturnedCalls = 0; }
    }

    private static void afterFrame(RenderFrameEvent.Post ignored) {
        Session session = active;
        if (session == null) return;
        try { session.frame(System.nanoTime()); }
        catch (Throwable failure) { session.finish("FAIL", failure.toString()); }
    }

    /** Native PlayerRenderer normal-return witness; no allocations or GL queries. */
    public static void playerRendered(AbstractClientPlayer entity) {
        Session session = active;
        if (session == null || !session.measuring || !(entity instanceof CrowdPlayer clone) || clone.owner != session) return;
        if (IRIS && IrisPass.shadow()) { session.shadowReturnedCalls++; return; }
        session.frameSeenMask |= 1L << clone.stageIndex;
        session.frameReturnedCalls++;
        session.cloneReturnedCalls[clone.stageIndex]++;
    }

    private static final class IrisPass {
        static boolean shadow() { return net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass(); }
    }

    private static final class CrowdPlayer extends RemotePlayer {
        final Session owner;
        final int stageIndex;
        final PlayerSkin skin;
        final PlayerTeam team = new PlayerTeam(new Scoreboard(), "crowd-frame-clones");
        CrowdPlayer(Session owner, int index, int id, Vec3 position) {
            super(owner.level, new GameProfile(UUID.randomUUID(), "Crowd" + index));
            this.owner = owner; this.stageIndex = index; this.skin = owner.player.getSkin();
            team.setNameTagVisibility(Team.Visibility.NEVER);
            setId(id); setPos(position); setYRot(owner.cameraYaw + 180); yHeadRot = yBodyRot = getYRot();
            setOldPosAndRot(); yHeadRotO = yHeadRot; yBodyRotO = yBodyRot;
            setOnGround(true); setNoGravity(true);
            xCloak = xCloakO = getX(); yCloak = yCloakO = getY(); zCloak = zCloakO = getZ();
        }
        @Override public PlayerSkin getSkin() { return skin != null ? skin : Minecraft.getInstance().player.getSkin(); }
        @Override public PlayerTeam getTeam() { return team; }
        @Override public boolean isModelPartShown(PlayerModelPart part) { return true; }
    }

    private static final class Session {
        final Minecraft mc;
        final ClientLevel level;
        final LocalPlayer player;
        final net.minecraft.client.server.IntegratedServer server;
        final Path out;
        final int warmupSeconds, measureSeconds;
        final Vec3 originalPosition;
        final float originalYaw, originalPitch, originalYawOld, originalPitchOld, originalBody, originalBodyOld, originalHead, originalHeadOld;
        final CameraType originalCamera;
        final Entity originalCameraEntity;
        final Map<KeyMapping, Boolean> keys = new LinkedHashMap<>();
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<Map<String, Object>> stages = new ArrayList<>();
        final List<CrowdPlayer> clones = new ArrayList<>();
        final List<CrowdPlayer> allOwnedClones = new ArrayList<>();
        final List<Vec3> positions;
        final float cameraYaw;
        final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        final long[] gcCounts = new long[collectors.size()], gcTimes = new long[collectors.size()];
        final long[] gcEndCounts = new long[collectors.size()], gcEndTimes = new long[collectors.size()];
        final long[] frameIntervals = new long[250_000];
        final long[] cloneReturnedCalls = new long[24];
        final long deadline;
        long phaseDeadline, previousFrame, measurementStarted, frameSeenMask, frameReturnedCalls, shadowReturnedCalls;
        long totalReturnedCalls, fullyRenderedFrames, missingCloneFrames, heapBefore, heapAfter;
        int stageIndex, samples, minRenderedClones, maxRenderedClones;
        boolean measuring, finished;

        Session(Minecraft mc, Path out, int warmupSeconds, int measureSeconds) {
            this.mc = mc; this.level = mc.level; this.player = mc.player; this.server = mc.getSingleplayerServer(); this.out = out;
            this.warmupSeconds = warmupSeconds; this.measureSeconds = measureSeconds;
            if (!player.onGround() || player.isPassenger() || player.isUsingItem() || player.isInWaterOrBubble()) throw new IllegalStateException("Start stationary, grounded, unmounted and not using an item");
            if (mc.options.framerateLimit().get() < 260 || mc.options.enableVsync().get()) throw new IllegalStateException("Benchmark requires maxFps>=260 and VSync=false; no settings changed");
            originalPosition = player.position();
            originalYaw = player.getYRot(); originalPitch = player.getXRot(); originalYawOld = player.yRotO; originalPitchOld = player.xRotO;
            originalBody = player.yBodyRot; originalBodyOld = player.yBodyRotO; originalHead = player.yHeadRot; originalHeadOld = player.yHeadRotO;
            originalCamera = mc.options.getCameraType(); originalCameraEntity = mc.getCameraEntity();
            Grid grid = findGrid(level, originalPosition);
            positions = grid.positions; cameraYaw = grid.yaw;
            for (KeyMapping key : List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                    mc.options.keyJump, mc.options.keyShift, mc.options.keySprint, mc.options.keyUse, mc.options.keyAttack)) keys.put(key, key.isDown());
            deadline = System.nanoTime() + (long)(3 * (warmupSeconds + measureSeconds + 15) + 30) * 1_000_000_000L;
            report.put("startedAt", Instant.now().toString());
            report.put("scope", "Controlled relative client-only player-render workload; no multiplayer packets/server player simulation, blocks, inventory or production config changed");
            report.put("runtime", mc.gameDirectory.toPath().toAbsolutePath().normalize().toString());
            report.put("firstPersonInstalled", ModList.get().isLoaded("firstperson"));
            report.put("compatInstalled", ModList.get().isLoaded("emfattachments"));
            report.put("emfInstalled", ModList.get().isLoaded("entity_model_features"));
            report.put("irisInstalled", IRIS);
            report.put("resourcePacks", mc.getResourcePackRepository().getSelectedIds());
            report.put("skinModel", player.getSkin().model().id());
            report.put("warmupSeconds", warmupSeconds); report.put("measureSeconds", measureSeconds);
            report.put("requestedCrowds", COUNTS); report.put("renderSettings", renderSettings(mc));
            report.put("cameraYaw", cameraYaw); report.put("cameraPitch", 4F); report.put("playerPosition", vector(originalPosition));
            report.put("nativeWorldReadiness", NativeWorldReadiness.evidence());
            report.put("stages", stages);
            report.put("limitations", List.of("Clones use the actual cached local skin with native FA/EMF/Iris render paths; names hidden and equipment empty.",
                    "Client-only clones have no real remote networking, server AI, equipment synchronization or distinct downloaded skins.",
                    "Render witness counts native PlayerRenderer returns excluding Iris shadows, but cannot exclude every third-party offscreen renderer or prove GPU geometry. Post-sample screenshots require visual review.",
                    "Only interval timestamps and primitive render counters are recorded during measurements; screenshots, sorting and JSON occur afterward.",
                    "GC MXBean deltas are aggregate collector timing, not an attribution of individual slow frames."));
        }

        void validate() {
            if (finished) return;
            // The immutable path/profile were checked before construction. Reference
            // checks avoid allocating filesystem paths in each measured client tick.
            if (mc.level != level || mc.player != player || mc.getSingleplayerServer() != server || server.isPublished()) throw new IllegalStateException("Disposable world or player identity changed");
            if (System.nanoTime() > deadline) throw new IllegalStateException("Bounded benchmark deadline exceeded");
            if (mc.screen != null || mc.isPaused() || !mc.isWindowActive()) throw new IllegalStateException("GUI, pause or window focus loss invalidates sample");
            if (mc.options.framerateLimit().get() < 260 || mc.options.enableVsync().get()) throw new IllegalStateException("FPS limiter or VSync changed during benchmark");
            if (player.position().distanceToSqr(originalPosition) > 0.0025 || player.isPassenger()) throw new IllegalStateException("Local player moved or mounted during stationary benchmark");
            if (mc.getCameraEntity() != player || mc.options.getCameraType() != CameraType.FIRST_PERSON
                    || Math.abs(net.minecraft.util.Mth.wrapDegrees(player.getYRot() - cameraYaw)) > 0.1F || Math.abs(player.getXRot() - 4F) > 0.1F)
                throw new IllegalStateException("Camera changed during fixed-view benchmark");
            if (measuring && (mc.gameRenderer.getMainCamera().getEntity() != player
                    || Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.gameRenderer.getMainCamera().getYRot() - cameraYaw)) > .2F
                    || Math.abs(mc.gameRenderer.getMainCamera().getXRot() - 4F) > .2F))
                throw new IllegalStateException("Actual main camera differs from the fixed player view");
            for (KeyMapping key : keys.keySet()) if (key.isDown()) throw new IllegalStateException("Gameplay input pressed during benchmark");
            for (CrowdPlayer clone : clones) if (level.getEntity(clone.getId()) != clone) throw new IllegalStateException("Owned client clone no longer resident");
        }

        void startStage() throws Exception {
            removeClones();
            int count = COUNTS[stageIndex];
            int entityId = Integer.MIN_VALUE + 1024;
            for (int i = 0; i < count; i++) {
                while (level.getEntity(entityId) != null) entityId++;
                Vec3 pos = positions.get(count == 8 ? i * 3 + 1 : i);
                CrowdPlayer clone = new CrowdPlayer(this, i, entityId++, pos);
                clones.add(clone); allOwnedClones.add(clone); level.addEntity(clone);
            }
            keys.keySet().forEach(key -> key.setDown(false));
            mc.setCameraEntity(player); mc.options.setCameraType(CameraType.FIRST_PERSON);
            player.setYRot(cameraYaw); player.yRotO = cameraYaw; player.yHeadRot = player.yHeadRotO = cameraYaw;
            player.yBodyRot = player.yBodyRotO = cameraYaw; player.setXRot(4F); player.xRotO = 4F;
            measuring = false;
            phaseDeadline = System.nanoTime() + warmupSeconds * 1_000_000_000L;
            report.put("activeStage", stageIndex); report.put("activeCrowd", count); report.put("phase", "WARMUP");
            report.put("warmupStartedAt", Instant.now().toString());
            write("RUNNING", false, "");
        }

        void frame(long now) throws Exception {
            if (finished) return;
            if (!measuring) {
                if (now < phaseDeadline) return;
                validate();
                samples = 0; previousFrame = measurementStarted = now; phaseDeadline = now + measureSeconds * 1_000_000_000L;
                Arrays.fill(cloneReturnedCalls, 0); frameSeenMask = frameReturnedCalls = totalReturnedCalls = fullyRenderedFrames = missingCloneFrames = shadowReturnedCalls = 0;
                minRenderedClones = Integer.MAX_VALUE; maxRenderedClones = 0;
                // Announce before starting the timed interval so external JFR can correlate UTC.
                report.put("phase", "MEASURE"); report.put("measurementStartedAt", Instant.now().toString());
                write("RUNNING", false, "");
                for (int i = 0; i < collectors.size(); i++) { gcCounts[i] = collectors.get(i).getCollectionCount(); gcTimes[i] = collectors.get(i).getCollectionTime(); }
                heapBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                previousFrame = measurementStarted = System.nanoTime(); phaseDeadline = measurementStarted + measureSeconds * 1_000_000_000L;
                measuring = true;
                return;
            }
            if (samples == frameIntervals.length) throw new IllegalStateException("Preallocated frame sample capacity exceeded");
            frameIntervals[samples++] = now - previousFrame; previousFrame = now;
            int seen = Long.bitCount(frameSeenMask); minRenderedClones = Math.min(minRenderedClones, seen); maxRenderedClones = Math.max(maxRenderedClones, seen);
            totalReturnedCalls += frameReturnedCalls;
            if (seen == clones.size()) fullyRenderedFrames++; else missingCloneFrames++;
            if (now < phaseDeadline) return;
            measuring = false;
            // Snapshot before sorting, report construction, PNG encoding or teardown.
            for (int i = 0; i < collectors.size(); i++) { gcEndCounts[i] = collectors.get(i).getCollectionCount(); gcEndTimes[i] = collectors.get(i).getCollectionTime(); }
            heapAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            validate();
            completeStage(now);
            if (++stageIndex == COUNTS.length) finish("CAPTURED", ""); else startStage();
        }

        void completeStage(long now) throws Exception {
            long[] sorted = Arrays.copyOf(frameIntervals, samples); Arrays.sort(sorted);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("remotePlayersRequested", COUNTS[stageIndex]); row.put("frameSamples", samples);
            row.put("actualSampleSeconds", (now - measurementStarted) / 1_000_000_000.0);
            row.put("measurementStartedAt", report.get("measurementStartedAt"));
            row.put("measurementEndedAt", Instant.now().toString());
            row.put("meanFps", samples * 1_000_000_000.0 / (now - measurementStarted));
            row.put("p50FrameMs", percentile(sorted, .50)); row.put("p95FrameMs", percentile(sorted, .95)); row.put("p99FrameMs", percentile(sorted, .99));
            row.put("maxFrameMs", samples == 0 ? null : sorted[samples - 1] / 1_000_000.0);
            row.put("over16_7ms", Arrays.stream(sorted).filter(n -> n > 16_666_667).count());
            row.put("over33_3ms", Arrays.stream(sorted).filter(n -> n > 33_333_333).count());
            row.put("over50ms", Arrays.stream(sorted).filter(n -> n > 50_000_000).count());
            row.put("nativeNonShadowPlayerRendererReturnedCalls", totalReturnedCalls); row.put("irisShadowReturnedCallsExcluded", shadowReturnedCalls);
            row.put("framesAllClonesReturned", fullyRenderedFrames); row.put("framesMissingSomeClone", missingCloneFrames);
            row.put("minDistinctClonesRenderedPerFrame", minRenderedClones); row.put("maxDistinctClonesRenderedPerFrame", maxRenderedClones);
            List<Map<String, Object>> entities = new ArrayList<>();
            boolean everyCloneObserved = true;
            for (CrowdPlayer clone : clones) {
                boolean resident = level.getEntity(clone.getId()) == clone;
                long calls = cloneReturnedCalls[clone.stageIndex]; everyCloneObserved &= resident && calls > 0;
                entities.add(Map.of("index", clone.stageIndex, "entityId", clone.getId(), "position", vector(clone.position()), "resident", resident, "nonShadowRendererReturns", calls));
            }
            row.put("entities", entities); row.put("everyCloneResidentAndObserved", everyCloneObserved);
            row.put("allFramesRenderedEveryRequestedClone", missingCloneFrames == 0);
            row.put("validRendererWorkloadMeasurement", everyCloneObserved && samples > 20);
            row.put("visualValidationPending", true);
            row.put("levelEntityCount", level.getEntityCount()); row.put("levelPlayerCount", level.players().size()); row.put("windowFocusedAtEnd", mc.isWindowActive());
            var representative = clones.isEmpty() ? player : clones.getFirst();
            var renderer = mc.getEntityRenderDispatcher().getRenderer(representative);
            row.put("nativeRendererClass", renderer.getClass().getName());
            if (renderer instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer playerRenderer) {
                var model = playerRenderer.getModel();
                row.put("nativeModelClass", model.getClass().getName());
                row.put("nativeBodyPartClass", model.body.getClass().getName());
                row.put("nativeHeadPartClass", model.head.getClass().getName());
            }
            List<Map<String, Object>> gc = new ArrayList<>();
            for (int i = 0; i < collectors.size(); i++) {
                var bean = collectors.get(i); long count = gcEndCounts[i], time = gcEndTimes[i];
                Map<String, Object> collector = new LinkedHashMap<>(); collector.put("name", bean.getName());
                collector.put("collectionCountDelta", count < 0 || gcCounts[i] < 0 ? null : count - gcCounts[i]);
                collector.put("collectionTimeMillisDelta", time < 0 || gcTimes[i] < 0 ? null : time - gcTimes[i]); gc.add(collector);
            }
            row.put("gc", gc); row.put("heapUsedBefore", heapBefore); row.put("heapUsedAfter", heapAfter);
            row.put("renderSettings", renderSettings(mc));
            String image = String.format("%02d-crowd-%02d-after-measurement.png", stageIndex + 1, COUNTS[stageIndex]);
            // All measurement counters are stopped before screenshot capture/encoding.
            try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { pixels.writeToFile(out.resolve(image)); }
            row.put("afterMeasurementScreenshot", image);
            stages.add(row);
            if (!everyCloneObserved) throw new IllegalStateException("Some requested clones never reached the native non-shadow renderer; see recorded visibility limits");
        }

        void removeClones() {
            for (CrowdPlayer clone : clones) if (level.getEntity(clone.getId()) == clone) level.removeEntity(clone.getId(), Entity.RemovalReason.DISCARDED);
            clones.clear();
        }

        void finish(String status, String detail) {
            if (finished) return;
            finished = true; measuring = false; active = null;
            List<String> restoreFailures = new ArrayList<>();
            for (CrowdPlayer clone : allOwnedClones) {
                try { if (level.getEntity(clone.getId()) == clone) level.removeEntity(clone.getId(), Entity.RemovalReason.DISCARDED); }
                catch (Throwable failure) { restoreFailures.add("Clone" + clone.getId() + ": " + failure); }
            }
            clones.clear();
            report.put("ownedCloneTotal", allOwnedClones.size());
            report.put("ownedClonesRemoved", allOwnedClones.stream().allMatch(clone -> level.getEntity(clone.getId()) != clone));
            for (var entry : keys.entrySet()) {
                try { entry.getKey().setDown(entry.getValue()); } catch (Throwable failure) { restoreFailures.add("Input: " + failure); }
            }
            try {
                mc.options.setCameraType(originalCamera);
                if (mc.level == level) mc.setCameraEntity(originalCameraEntity);
                if (mc.player == player) {
                    player.setYRot(originalYaw); player.setXRot(originalPitch); player.yRotO = originalYawOld; player.xRotO = originalPitchOld;
                    player.yBodyRot = originalBody; player.yBodyRotO = originalBodyOld; player.yHeadRot = originalHead; player.yHeadRotO = originalHeadOld;
                }
                report.put("inputRestored", keys.entrySet().stream().allMatch(e -> e.getKey().isDown() == e.getValue()));
                report.put("cameraRestored", mc.options.getCameraType() == originalCamera && mc.getCameraEntity() == originalCameraEntity);
                report.put("positionUnchanged", player.position().distanceToSqr(originalPosition) < 0.0025);
                report.put("yawPitchRestored", player.getYRot() == originalYaw && player.getXRot() == originalPitch);
            } catch (Throwable restoreFailure) { restoreFailures.add("Camera: " + restoreFailure); }
            if (!restoreFailures.isEmpty()) { report.put("restoreFailures", restoreFailures); status = "FAIL"; }
            report.put("phase", "FINISHED"); write(status, true, detail);
        }

        void write(String status, boolean finished, String detail) {
            report.put("status", status); report.put("finished", finished); report.put("detail", detail);
            try { Files.writeString(out.resolve("crowd-frames.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch (Exception failure) { throw new IllegalStateException("Cannot save crowd frame report", failure); }
        }
    }

    private record Grid(float yaw, List<Vec3> positions) {}
    private static Grid findGrid(ClientLevel level, Vec3 origin) {
        // Interleave the six columns/four depths by viewing angle so nearer clones
        // do not perfectly occlude the same-column players behind them.
        for (float yaw : new float[] {0, 90, 180, 270}) {
            double radians = Math.toRadians(yaw), forwardX = -Math.sin(radians), forwardZ = Math.cos(radians);
            double rightX = Math.cos(radians), rightZ = Math.sin(radians);
            List<Vec3> positions = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                double forward = 16 + i % 4, side = forward * Math.tan(Math.toRadians(-27.6 + i * 2.4));
                Vec3 p = new Vec3(origin.x + forwardX * forward + rightX * side, origin.y, origin.z + forwardZ * forward + rightZ * side);
                BlockPos below = BlockPos.containing(p.x, p.y - .05, p.z);
                var shape = level.getBlockState(below).getCollisionShape(level, below);
                if (!level.hasChunkAt(below) || shape.isEmpty() || Math.abs(below.getY() + shape.max(Direction.Axis.Y) - p.y) > .08
                        || !level.noCollision(null, new AABB(p.x - .31, p.y + .01, p.z - .31, p.x + .31, p.y + 1.85, p.z + .31))) break;
                positions.add(p);
            }
            if (positions.size() == 24) return new Grid(yaw, positions);
        }
        throw new IllegalStateException("No existing flat, unobstructed24-player grid16..19 blocks away in any cardinal direction; no terrain changed");
    }

    private static double percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) return 0;
        return sorted[Math.max(0, (int)Math.ceil(sorted.length * fraction) - 1)] / 1_000_000.0;
    }

    private static List<Double> vector(Vec3 p) { return List.of(p.x, p.y, p.z); }

    private static Map<String, Object> renderSettings(Minecraft mc) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxFps", mc.options.framerateLimit().get()); result.put("vsync", mc.options.enableVsync().get());
        result.put("renderDistance", mc.options.renderDistance().get()); result.put("simulationDistance", mc.options.simulationDistance().get());
        result.put("effectiveRenderDistance", mc.options.getEffectiveRenderDistance()); result.put("fov", mc.options.fov().get());
        result.put("width", mc.getWindow().getWidth()); result.put("height", mc.getWindow().getHeight());
        result.put("windowFocused", mc.isWindowActive()); result.put("pauseOnLostFocus", mc.options.pauseOnLostFocus); result.put("hideGui", mc.options.hideGui);
        return result;
    }
}
