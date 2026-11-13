package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.tr7zw.entityculling.EntityCullingModBase;
import dev.tr7zw.entityculling.util.NMSCullingHelper;
import dev.tr7zw.entityculling.access.Cullable;
import dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import traben.entity_model_features.utils.EMFEntity;

/** Bounded natural-wall test and separate injected stale-occlusion control. */
public final class SableOcclusionSequence {
    private static final UUID OFFLINE_ID = UUID.nameUUIDFromBytes("OfflinePlayer:AttachmentTest".getBytes(StandardCharsets.UTF_8));
    private static volatile Session active;
    private static int polls;
    private SableOcclusionSequence() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SableOcclusionSequence::tick);
        NeoForge.EVENT_BUS.addListener(SableOcclusionSequence::serverTick);
        NeoForge.EVENT_BUS.addListener(SableOcclusionSequence::frame);
    }

    private static boolean disposable(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.hasSingleplayerServer() && !mc.getSingleplayerServer().isPublished()
                && mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                && mc.getUser().getType() == User.Type.LEGACY && "AttachmentTest".equals(mc.getUser().getName())
                && OFFLINE_ID.equals(mc.getUser().getProfileId()) && OFFLINE_ID.equals(mc.player.getUUID());
    }

    private static void tick(ClientTickEvent.Post event) {
        Session s = active;
        if (s != null) {
            try { s.tick(); } catch (Throwable error) { s.finish("FAIL", error.toString()); }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (++polls % 20 != 0 || !disposable(mc) || mc.screen != null || !NativeWorldReadiness.ready()) return;
        Path naturalTrigger = mc.gameDirectory.toPath().resolve("emfattachments-sable-natural-culling.txt");
        boolean natural = Files.isRegularFile(naturalTrigger);
        Path trigger = natural ? naturalTrigger : mc.gameDirectory.toPath().resolve("emfattachments-sable-occlusion.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            String label = Files.readString(trigger).trim().replaceAll("[^a-zA-Z0-9._-]", "_");
            if (label.isBlank() || label.equals(".") || label.equals("..")) label = "sable-occlusion";
            Files.delete(trigger);
            active = new Session(mc, label, natural);
            active.prepare();
        } catch (Throwable error) {
            if (active != null) active.finish("FAIL", error.toString());
            else com.mojang.logging.LogUtils.getLogger().error("Sable occlusion fixture startup failed", error);
        }
    }

    private static void serverTick(ServerTickEvent.Pre event) {
        Session s = active;
        if (s == null || s.finishing || event.getServer() != s.server || s.platform == null) return;
        try { s.drive(); } catch (Throwable error) { s.serverFailure = error.toString(); }
    }

    private static void frame(RenderFrameEvent.Post event) {
        Session s = active;
        if (s != null && !s.finishing) try { s.frame(); } catch (Throwable error) { s.finish("FAIL", error.toString()); }
    }

    /** Runs immediately before Entity Culling's native ClientLevel tick cancellation. */
    public static void beforeTick(Entity entity) {
        Session s = active;
        Witness w = s == null || s.finishing || !s.sampling ? null : s.find(entity);
        if (w == null) return;
        s.forceFlags(w);
        w.tickAttempts++;
        if (s.reducedTickAudit) s.beforeAuditedTick(w);
        if (w.firstTick == null) w.firstTick = s.state(w);
        if (NMSCullingHelper.ignoresCulling(entity)) w.exemptTicks++;
    }

    public static void afterTick(Entity entity) {
        Session s = active;
        Witness w = s == null || s.finishing || !s.sampling || !s.reducedTickAudit ? null : s.find(entity);
        if (w != null) s.afterAuditedTick(w);
    }

    /** Entity.tick is absent from Entity Culling's replacement basicTick. */
    public static void fullTick(Entity entity) {
        Session s = active;
        Witness w = s == null || s.finishing || !s.sampling ? null : s.find(entity);
        if (w != null) w.fullTicks++;
    }

    public static void beforeRender(Entity entity) {
        Session s = active;
        Witness w = s == null || s.finishing || !s.sampling || !(entity instanceof Witness) || shadow() ? null : s.find(entity);
        if (w == null) return;
        s.forceFlags(w); w.renderAttempts++;
        if (s.stageTicks >= 25) w.settledRenderAttempts++;
    }

    public static void playerRendered(AbstractClientPlayer player, PlayerModel<?> model) {
        Session s = active;
        Witness w = s == null || s.finishing || !s.sampling || !(player instanceof Witness) || shadow() ? null : s.find(player);
        if (w != null) {
            w.rendererReturns++; if (s.stageTicks >= 25) w.settledRendererReturns++;
            if (w.firstRendererTick < 0) w.firstRendererTick = s.stageTicks;
            w.lastRendererTick = s.stageTicks;
            if (s.natural && w.lastAnimationTick != player.tickCount) {
                w.lastAnimationTick = player.tickCount;
                Map<String, Object> row = s.state(w);
                Map<String, Object> vars = new LinkedHashMap<>();
                for (String key : List.of("var.land", "var.t_land", "var.sneak", "var.Asneak")) {
                    Float value = ((EMFEntity)player).emf$getVariableMap().get(key);
                    if (value != null && Float.isFinite(value)) vars.put(key, value);
                }
                row.put("emfVariables", vars);
                row.put("bodyY", model.body.y); row.put("bodyPitch", model.body.xRot);
                row.put("onGround", player.onGround()); row.put("shift", player.isShiftKeyDown());
                row.put("pose", player.getPose().toString());
                if (s.animationSamples.size() < 5_000) s.animationSamples.add(row);
            }
        }
    }

    public static void skinRenderType(Entity entity, RenderType renderType) {
        Session s = active;
        Witness w = s == null || s.finishing || !s.sampling || !(entity instanceof Witness) || shadow() ? null : s.find(entity);
        if (w != null) { if (renderType == null) w.hiddenSkinDecisions++; else w.visibleSkinDecisions++; }
    }

    private static boolean shadow() {
        return ModList.get().isLoaded("iris") && net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();
    }

    private enum Kind { ATTACHED, PENDING_PLOT, ORDINARY_WORLD, INVISIBLE_ATTACHED, NATIVE_EXEMPT }

    private static final class Witness extends RemotePlayer {
        final Kind kind;
        final PlayerSkin skin;
        final PlayerTeam team = new PlayerTeam(new Scoreboard(), "sable-occlusion");
        final Vec3 plotPosition;
        int tickAttempts, exemptTicks, fullTicks, renderAttempts, rendererReturns, hiddenSkinDecisions, visibleSkinDecisions;
        int settledRenderAttempts, settledRendererReturns;
        int lastAnimationTick = -1;
        int firstRendererTick = -1, lastRendererTick = -1;
        int aiSteps, packetUpdates, packetRemaining;
        Vec3 packetTarget;
        float packetYaw, packetPitch;
        Map<String, Object> tickObservation;
        Vec3 expectedWorld, expectedPlot;
        Float expectedYaw, expectedPitch;
        int beforeNativeTick, beforeAiSteps, beforeFullTicks;
        Map<String, Object> firstTick;
        Witness(Minecraft mc, Kind kind, int id, Vec3 position, Vec3 plotPosition) {
            super(mc.level, new GameProfile(UUID.randomUUID(), "Occlusion" + kind.ordinal()));
            this.kind = kind; this.plotPosition = plotPosition; skin = mc.player.getSkin();
            team.setNameTagVisibility(Team.Visibility.NEVER);
            setId(id); setPos(position); setNoGravity(true); setOnGround(true);
            setYRot(180); yBodyRot = yBodyRotO = yHeadRot = yHeadRotO = 180; setOldPosAndRot();
            if (kind == Kind.INVISIBLE_ATTACHED) setInvisible(true);
            if (kind == Kind.NATIVE_EXEMPT) noCulling = true;
        }
        @Override public PlayerSkin getSkin() { return skin == null ? Minecraft.getInstance().player.getSkin() : skin; }
        @Override public PlayerTeam getTeam() { return team; }
        @Override public boolean isModelPartShown(PlayerModelPart part) { return true; }
        @Override public void aiStep() { aiSteps++; super.aiStep(); }
        void resetCounts() { tickAttempts = exemptTicks = fullTicks = renderAttempts = rendererReturns = hiddenSkinDecisions = visibleSkinDecisions = 0; settledRenderAttempts = settledRendererReturns = 0; aiSteps = packetUpdates = packetRemaining = 0; tickObservation = null; firstTick = null; firstRendererTick = lastRendererTick = -1; }
    }

    private static final class Session {
        final Minecraft mc;
        final net.minecraft.client.server.IntegratedServer server;
        final net.minecraft.client.multiplayer.ClientLevel level;
        final net.minecraft.client.player.LocalPlayer local;
        final Path output;
        final boolean natural;
        final boolean reducedTickAudit = Boolean.getBoolean("emfattachments.sableReducedTickAudit");
        final CameraType oldCamera;
        final Entity oldCameraEntity;
        final float oldYaw, oldPitch;
        final int oldFov;
        final EntityCullingModBase ec = EntityCullingModBase.instance;
        final boolean oldWorkerDisabled, oldTickCulling, oldSkipCulling, oldEnabled;
        final Map<KeyMapping, Boolean> keys = new LinkedHashMap<>();
        final List<Witness> witnesses = new ArrayList<>();
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<Map<String, Object>> stages = new ArrayList<>(), samples = new ArrayList<>(), screenshots = new ArrayList<>();
        final List<Map<String, Object>> animationSamples = new ArrayList<>();
        final List<Map<String, Object>> tickObservations = new ArrayList<>();
        final Map<BlockPos, BlockState> wallBlocks = new LinkedHashMap<>();
        final long deadline = System.nanoTime() + 150_000_000_000L;
        volatile ServerSubLevel platform;
        volatile UUID platformId;
        volatile Vec3 base, cameraPosition;
        volatile String serverFailure;
        volatile boolean finishing, sampling;
        volatile int stage = -1, driveTicks;
        SubLevel clientDeck;
        Snapshot snapshot;
        CompletableFuture<?> preparation, removal, restoration;
        int ticks, stageTicks, pendingShots, restoreTicks;
        String finalStatus, detail;
        boolean naturalShotTaken, boarded;

        Session(Minecraft mc, String label, boolean natural) throws Exception {
            if (mc.player.isPassenger() || mc.player.isUsingItem() || ec == null || ec.cullTask == null)
                throw new IllegalStateException("Start unmounted, with no active item and initialized Entity Culling");
            if (ec.entityWhitelist.contains(EntityType.PLAYER) || ec.tickCullWhitelists.contains(EntityType.PLAYER))
                throw new IllegalStateException("Player whitelist would invalidate occlusion control");
            if (ModList.get().isLoaded("firstperson")) throw new IllegalStateException("Focused culling test requires FirstPerson absent");
            this.mc = mc; server = mc.getSingleplayerServer(); level = mc.level; local = mc.player;
            this.natural = natural;
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label); Files.createDirectories(output);
            oldCamera = mc.options.getCameraType(); oldCameraEntity = mc.getCameraEntity();
            oldYaw = local.getYRot(); oldPitch = local.getXRot();
            oldFov = mc.options.fov().get();
            oldWorkerDisabled = ec.cullTask.disableEntityCulling; oldTickCulling = ec.config.tickCulling;
            oldSkipCulling = ec.config.skipEntityCulling; oldEnabled = EntityCullingVersionlessBase.enabled;
            report.put("label", label); report.put("startedAt", Instant.now().toString());
            report.put("scope", natural
                    ? "NATURAL_WALL: native Entity Culling worker traces a real opaque world wall while a real Sable platform translates and rotates. No cull or forced-visible flags are written by this mode. Owned remote clones model pending plot, tracked, ordinary, invisible and native-exempt cases; this is not a second network client."
                    : "INJECTED_CONTROL: deliberately force stale Entity Culling flags immediately before native tick/render decisions on owned client-only clones. This is not natural reproduction of the user's multiplayer invisibility.");
            report.put("naturalWorker", natural);
            report.put("reducedTickAudit", reducedTickAudit);
            report.put("fullTickControl", Boolean.getBoolean("emfattachments.sableFullTicks"));
            report.put("productionMixinsDisabled", Boolean.getBoolean("emfattachments.disable"));
            report.put("firstPersonInstalled", ModList.get().isLoaded("firstperson"));
            report.put("resourcePacks", mc.getResourcePackRepository().getSelectedIds());
            report.put("stages", stages); report.put("samples", samples); report.put("screenshots", screenshots);
            report.put("animationSamples", animationSamples);
            if (reducedTickAudit) {
                report.put("tickObservations", tickObservations);
                report.put("packetFixture", "Owned remote clones receive lerpTo plus sable$plotLerpTo every 12 ticks, with three interpolation steps, alternating plot offsets and view rotations. This exercises native interpolation but not real packet transport.");
            }
            report.put("limitations", List.of("No actual second client or multiplayer packet delivery; pending plot is seeded once through Sable's native entity extension.", natural ? "Native PhysicsPipeline trajectory, not Aeronautics propulsion." : "The occlusion result is injected, not generated by a natural wall trace.", "Native render returns do not alone prove visible pixels; skin RenderType decisions and screenshots are separate evidence."));
            report.put("renderTimeoutControl", "Entity Culling grants a one-second forced-visible timeout after clear flags. Settled render counters exclude the first 25 ticks of every stage.");
        }

        void prepare() {
            for (KeyMapping key : List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                    mc.options.keyShift, mc.options.keyJump, mc.options.keySprint, mc.options.keyUse, mc.options.keyAttack)) {
                keys.put(key, key.isDown()); key.setDown(false);
            }
            // In-memory test-copy controls only; native mode keeps the real worker active.
            ec.cullTask.disableEntityCulling = !natural; ec.config.tickCulling = true; ec.config.skipEntityCulling = false;
            EntityCullingVersionlessBase.enabled = true;
            mc.options.setCameraType(CameraType.FIRST_PERSON); mc.setCameraEntity(local);
            if (natural) mc.options.fov().set(80);
            report.put("cameraFovSetting", mc.options.fov().get());
            report.put("windowPixels", List.of(mc.getWindow().getWidth(), mc.getWindow().getHeight()));
            preparation = server(p -> {
                snapshot = new Snapshot(p);
                var container = SubLevelContainer.getContainer(p.serverLevel());
                if (container == null) throw new IllegalStateException("Missing Sable container");
                int x = p.getBlockX() + 24, z = p.getBlockZ() + 24;
                int y = p.serverLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 12;
                Pose3d pose = new Pose3d(); pose.position().set(x + .5, y, z + .5);
                platform = (ServerSubLevel)container.allocateNewSubLevel(pose); platformId = platform.getUniqueId();
                platform.setName(natural ? "EMF natural wall culling control" : "EMF injected occlusion control");
                var plot = platform.getPlot(); ChunkPos center = plot.getCenterChunk();
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) plot.newEmptyChunk(new ChunkPos(center.x + dx, center.z + dz));
                int count = 0;
                for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
                    BlockPos pos = new BlockPos(dx, 0, dz);
                    plot.getEmbeddedLevelAccessor().setBlock(pos, Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
                    if (!plot.getEmbeddedLevelAccessor().getBlockState(pos).isAir()) count++;
                }
                if (count != 121 || platform.getMassTracker().getMass() <= 0) throw new IllegalStateException("Platform blocks/mass missing");
                base = new Vec3(x + .5, y, z + .5); platform.logicalPose().position().set(base.x, base.y, base.z); platform.updateLastPose();
                if (natural) {
                    // Only replace verified air in this disposable save, then restore exact states.
                    for (int dx = -6; dx <= 6; dx++) for (int dy = -2; dy <= 8; dy++) for (int dz = -7; dz <= -6; dz++) {
                        BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
                        BlockState old = p.serverLevel().getBlockState(pos);
                        if (!old.isAir() || p.serverLevel().getBlockEntity(pos) != null)
                            throw new IllegalStateException("Natural wall area is not empty: " + pos);
                        wallBlocks.put(pos, old);
                    }
                    wallBlocks.keySet().forEach(pos -> p.serverLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 3));
                    report.put("wallBlocks", wallBlocks.size());
                    report.put("wallBounds", List.of(x - 6, y - 2, z - 7, x + 6, y + 8, z - 6));
                }
                container.physicsSystem().getPipeline().teleport(platform, new Vector3d(base.x + (natural ? 14 : 0), base.y, base.z), new Quaterniond());
                cameraPosition = natural ? base.add(4, 3.5, -18) : base.add(0, 3.5, -10);
                report.put("platformBase", vec(base)); report.put("cameraFeetPosition", vec(cameraPosition));
                report.put("cameraPitch", 15); report.put("cameraYawFacingDeck", 0); report.put("cameraYawAway", 180);
                if (natural) report.put("trajectory", "X relative to platformBase: visible +14, hidden 0, linear transitions over 100 ticks. Native yaw +/-0.12 radians, roll +/-0.025 radians, world Y +/-0.15 blocks. Camera remains fixed and turns 180 degrees for away stage. Witness plot offsets X=-3,-1.5,0,1.5,3; Y=center+1; Z=center+0.5.");
                p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = true; p.onUpdateAbilities(); p.setNoGravity(true);
                p.teleportTo(p.serverLevel(), cameraPosition.x, cameraPosition.y, cameraPosition.z, 0, 15);
                p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
                return null;
            });
            write("PREPARING", "");
        }

        <T> CompletableFuture<T> server(Function<ServerPlayer, T> action) {
            CompletableFuture<T> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    if (server.isPublished()) throw new IllegalStateException("Server published during test");
                    ServerPlayer p = server.getPlayerList().getPlayer(OFFLINE_ID);
                    if (p == null) throw new IllegalStateException("Offline player disappeared");
                    future.complete(action.apply(p));
                } catch (Throwable error) { future.completeExceptionally(error); }
            });
            return future;
        }

        void drive() {
            if (base == null || platform.isRemoved()) return;
            if (natural) {
                if (stage >= 0) driveTicks++;
                double t = Math.min(stageTicks / 100.0, 1.0);
                double x = switch (stage) {
                    case 1, 6 -> 14 * (1 - t);
                    case 2, 7, 8 -> 0;
                    case 3 -> 14 * t;
                    default -> 14;
                };
                double yaw = stage < 0 ? 0 : .12 * Math.sin(driveTicks * .035);
                double pitch = stage < 0 ? 0 : .025 * Math.sin(driveTicks * .045);
                var pipeline = SubLevelContainer.getContainer(platform.getLevel()).physicsSystem().getPipeline();
                pipeline.resetVelocity(platform);
                pipeline.teleport(platform, new Vector3d(base.x + x, base.y - .15 * Math.sin(driveTicks * .025), base.z),
                        new Quaterniond().rotationYXZ(yaw, 0, pitch));
                return;
            }
            if (stage >= 0 && stage <= 1) driveTicks++;
            var pipeline = SubLevelContainer.getContainer(platform.getLevel()).physicsSystem().getPipeline();
            pipeline.resetVelocity(platform);
            pipeline.teleport(platform, new Vector3d(base.x + Math.min(driveTicks, 160) * .02, base.y, base.z),
                    reducedTickAudit ? new Quaterniond().rotationYXZ(.12 * Math.sin(driveTicks * .035), 0, .025 * Math.sin(driveTicks * .045)) : new Quaterniond());
        }

        void tick() {
            if (finishing) { restoreTick(); return; }
            if (!disposable(mc) || mc.level != level || mc.player != local || mc.getSingleplayerServer() != server || mc.screen != null)
                throw new IllegalStateException("Disposable scene changed or GUI opened");
            if (serverFailure != null) throw new IllegalStateException(serverFailure);
            if (System.nanoTime() > deadline) throw new IllegalStateException("Bounded occlusion test deadline exceeded");
            ticks++;
            if (!preparation.isDone()) return; preparation.join();
            if (stage < 0) {
                if (ticks > 240) throw new IllegalStateException("Owned platform readiness timeout");
                var container = SubLevelContainer.getContainer(level);
                clientDeck = container == null || platformId == null ? null : container.getSubLevel(platformId);
                if (clientDeck == null || level.getBlockState(clientDeck.getPlot().getCenterBlock()).isAir() || local.position().distanceTo(cameraPosition) > .25) return;
                if (natural && wallBlocks.keySet().stream().anyMatch(pos -> !level.getBlockState(pos).is(Blocks.STONE))) return;
                local.setYRot(0); local.setXRot(15);
                spawnWitnesses(); startStage(0); return;
            }
            int removalStage = natural ? 8 : 3;
            if (stage == removalStage && removal != null && !removal.isDone()) return;
            if (stage == removalStage && removal != null) {
                removal.join();
                if (SubLevelContainer.getContainer(level).getSubLevel(platformId) != null) return;
                // Keep an explicit stale reference to the actually removed native sub-level for this control.
                for (Witness w : witnesses) if (w.kind == Kind.ATTACHED || w.kind == Kind.PENDING_PLOT || w.kind == Kind.INVISIBLE_ATTACHED) {
                    ((EntityMovementExtension)(Object)w).sable$setTrackingSubLevel(clientDeck);
                    if (reducedTickAudit) ((EntityStickExtension)(Object)w).sable$setPlotPosition(w.plotPosition);
                }
                removal = null;
                report.put("removedControlReferenceActuallyRemoved", clientDeck.isRemoved());
                witnesses.forEach(Witness::resetCounts);
                sampling = true;
            }
            float expectedYaw = natural && stage == 4 ? 180 : 0;
            if (local.position().distanceTo(cameraPosition) > .5 || Math.abs(local.getYRot() - expectedYaw) > .1 || Math.abs(local.getXRot() - 15) > .1)
                throw new IllegalStateException("Fixed test camera moved");
            stageTicks++;
            for (Witness w : witnesses) {
                if (level.getEntity(w.getId()) != w) throw new IllegalStateException("Owned witness missing");
                if (stageTicks % 4 == 0) samples.add(state(w));
            }
            if (natural) {
                if (stage == 6 && stageTicks == 100) detachAll();
                // Capture only after the native worker's expected state has actually been observed.
                if (!naturalShotTaken && stageTicks >= 60 && naturalScreenshotReady()) {
                    naturalShotTaken = true; pendingShots++;
                }
            } else if (stageTicks == 30 || stageTicks == 75) pendingShots++;
            int duration = natural ? (stage == 0 ? 140 : stage == 1 || stage == 3 || stage == 6 ? 140 : 100) : stage == 0 ? 100 : 80;
            if (stageTicks >= duration) {
                closeStage();
                if (stage == removalStage) finish("CAPTURED", natural
                        ? "Natural wall/camera/lifecycle culling evidence captured; apply functional assertions and inspect images."
                        : "Injected stale-cull controls captured; successful hidden render suppression is expected with preserved attached ticks.");
                else startStage(stage + 1);
            }
        }

        void spawnWitnesses() {
            BlockPos center = clientDeck.getPlot().getCenterBlock();
            int id = Integer.MIN_VALUE + 6144;
            for (Kind kind : Kind.values()) {
                while (level.getEntity(id) != null) id++;
                Vec3 plot = new Vec3(center.getX() - 3 + kind.ordinal() * 1.5, center.getY() + 1, center.getZ() + .5);
                Witness w = new Witness(mc, kind, id++, clientDeck.logicalPose().transformPosition(plot), plot);
                if (natural && kind == Kind.ORDINARY_WORLD) {
                    w.setPos(base.add(0, 1, 0)); w.setOldPosAndRot();
                }
                boolean attached = kind == Kind.ATTACHED || kind == Kind.PENDING_PLOT || kind == Kind.INVISIBLE_ATTACHED;
                if (attached) ((EntityStickExtension)(Object)w).sable$setPlotPosition(plot);
                if (attached && kind != Kind.PENDING_PLOT) ((EntityMovementExtension)(Object)w).sable$setTrackingSubLevel(clientDeck);
                witnesses.add(w); level.addEntity(w);
            }
        }

        void startStage(int next) {
            stage = next; stageTicks = 0; pendingShots = 0; naturalShotTaken = false;
            sampling = next != (natural ? 8 : 3); witnesses.forEach(Witness::resetCounts);
            if (natural) {
                local.setYRot(next == 4 ? 180 : 0); local.setXRot(15);
                if (next == 5) {
                    Witness w = witnesses.stream().filter(p -> p.kind == Kind.ORDINARY_WORLD).findFirst().orElseThrow();
                    w.setPos(clientDeck.logicalPose().transformPosition(w.plotPosition)); w.setOldPosAndRot();
                    ((EntityStickExtension)(Object)w).sable$setPlotPosition(w.plotPosition);
                    ((EntityMovementExtension)(Object)w).sable$setTrackingSubLevel(clientDeck);
                    boarded = true;
                }
            } else if (next == 2) detachAll();
            if (next == (natural ? 8 : 3)) removal = server(p -> {
                SubLevelContainer.getContainer(p.serverLevel()).removeSubLevel(platform, SubLevelRemovalReason.REMOVED); return null;
            });
            // Clear recovery-stage flags even if a previous cancellation skipped all body hooks.
            witnesses.forEach(this::forceFlags);
            write("RUNNING", stageName());
        }

        String stageName() {
            if (natural) return switch (stage) {
                case 0 -> "natural-visible-moving";
                case 1 -> "natural-move-behind-wall";
                case 2 -> "natural-hidden-rotating";
                case 3 -> "natural-emerge-from-wall";
                case 4 -> "natural-camera-away";
                case 5 -> "natural-camera-reentry-and-board";
                case 6 -> "natural-hide-and-dismount";
                case 7 -> "natural-detached-hidden";
                case 8 -> "natural-unloaded-stale-reference";
                default -> "preparing";
            };
            return switch (stage) {
                case 0 -> "injected-culled-and-out-of-camera-moving";
                case 1 -> "cleared-flags-moving-native-control";
                case 2 -> "detached-injected-cull-control";
                case 3 -> "removed-sublevel-stale-reference-control";
                default -> "preparing";
            };
        }

        Witness find(Entity entity) {
            if (!(entity instanceof Witness)) return null;
            for (int i = 0; i < witnesses.size(); i++) { Witness w = witnesses.get(i); if (w == entity) return w; }
            return null;
        }

        void forceFlags(Witness w) {
            if (natural) return;
            Cullable cull = (Cullable)(Object)w;
            cull.setCulled(stage != 1); cull.setOutOfCamera(stage != 1);
        }

        void detachAll() {
            for (Witness w : witnesses) {
                ((EntityStickExtension)(Object)w).sable$setPlotPosition(null);
                // A normal world movement packet clears plot state before Entity.tick
                // clears the old tracking reference. Keep that transition observable.
                if (!reducedTickAudit) ((EntityMovementExtension)(Object)w).sable$setTrackingSubLevel(null);
                w.packetRemaining = 0;
            }
            boarded = false;
        }

        void beforeAuditedTick(Witness w) {
            var stick = (EntityStickExtension)(Object)w;
            Vec3 plot = stick.sable$getPlotPosition();
            SubLevel support = plot == null ? null : dev.ryanhcode.sable.Sable.HELPER.getContaining(level, plot);
            if (plot != null && support != null && !support.isRemoved() && w.tickAttempts % 12 == 1) {
                double sign = w.packetUpdates % 2 == 0 ? 1 : -1;
                w.packetTarget = w.plotPosition.add(.45 * sign, 0, .2 * sign);
                w.packetYaw = (float)(180 + 24 * sign); w.packetPitch = (float)(8 * sign);
                w.lerpTo(w.packetTarget.x, w.packetTarget.y, w.packetTarget.z, w.packetYaw, w.packetPitch, 3);
                stick.sable$plotLerpTo(w.packetTarget, 3);
                w.packetRemaining = 3; w.packetUpdates++;
            }
            w.beforeNativeTick = w.tickCount; w.beforeAiSteps = w.aiSteps; w.beforeFullTicks = w.fullTicks;
            // Full Entity.tick projects the pre-aiStep plot point. aiStep then
            // advances the plot history; comparing against that newer point
            // would incorrectly label native one-step ordering as an error.
            w.expectedWorld = support != null && !support.isRemoved() ? support.logicalPose().transformPosition(plot) : null;
            w.expectedPlot = plot == null ? null : w.packetRemaining > 0 ? plot.lerp(w.packetTarget, 1.0 / w.packetRemaining) : plot;
            w.expectedYaw = w.packetRemaining > 0 ? w.getYRot() + Mth.wrapDegrees(w.packetYaw - w.getYRot()) / w.packetRemaining : null;
            w.expectedPitch = w.packetRemaining > 0 ? w.getXRot() + (w.packetPitch - w.getXRot()) / w.packetRemaining : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stageName()); row.put("stageTick", stageTicks); row.put("kind", w.kind.name());
            row.put("attempt", w.tickAttempts); row.put("packetUpdates", w.packetUpdates); row.put("packetStepsBefore", w.packetRemaining);
            row.put("plotBefore", plot == null ? List.of() : vec(plot));
            var tracked = ((EntityMovementExtension)(Object)w).sable$getTrackingSubLevel();
            row.put("trackingBefore", tracked == null ? "" : tracked.getUniqueId().toString());
            row.put("trackingRemovedBefore", tracked != null && tracked.isRemoved());
            Cullable cull = (Cullable)(Object)w;
            row.put("culledBefore", cull.isCulled()); row.put("outOfCameraBefore", cull.isOutOfCamera());
            row.put("positionBefore", vec(w.position()));
            if (w.expectedWorld != null) row.put("expectedWorld", vec(w.expectedWorld));
            if (w.expectedPlot != null) row.put("expectedPlot", vec(w.expectedPlot));
            w.tickObservation = row;
        }

        void afterAuditedTick(Witness w) {
            Map<String, Object> row = w.tickObservation;
            if (row == null) throw new IllegalStateException("Missing pre-tick observation");
            row.put("nativeTickDelta", w.tickCount - w.beforeNativeTick);
            row.put("aiStepDelta", w.aiSteps - w.beforeAiSteps);
            row.put("fullEntityTickDelta", w.fullTicks - w.beforeFullTicks);
            row.put("positionAfter", vec(w.position()));
            Vec3 plot = ((EntityStickExtension)(Object)w).sable$getPlotPosition();
            row.put("plotAfter", plot == null ? List.of() : vec(plot));
            var tracked = ((EntityMovementExtension)(Object)w).sable$getTrackingSubLevel();
            row.put("trackingAfter", tracked == null ? "" : tracked.getUniqueId().toString());
            row.put("trackingRemovedAfter", tracked != null && tracked.isRemoved());
            if (w.expectedWorld != null) row.put("projectionError", w.expectedWorld.distanceTo(w.position()));
            if (w.expectedPlot != null && plot != null) row.put("plotInterpolationError", w.expectedPlot.distanceTo(plot));
            if (w.expectedYaw != null) row.put("yawInterpolationError", Math.abs(Mth.wrapDegrees(w.getYRot() - w.expectedYaw)));
            if (w.expectedPitch != null) row.put("pitchInterpolationError", Math.abs(w.getXRot() - w.expectedPitch));
            if (w.packetRemaining > 0) w.packetRemaining--;
            tickObservations.add(row); w.tickObservation = null;
        }

        boolean naturalScreenshotReady() {
            Witness w = witnesses.get(0);
            Cullable cull = (Cullable)(Object)w;
            if (stage == 4) return w.settledRendererReturns == 0;
            if (stage == 2 || stage == 7 || stage == 8) return cull.isCulled() && !cull.isForcedVisible();
            if (stage == 0 || stage == 5) return !cull.isCulled() && w.settledRendererReturns > 0;
            return stageTicks >= 115;
        }

        Map<String, Object> state(Witness w) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stageName()); row.put("stageTick", stageTicks); row.put("kind", w.kind.name());
            row.put("cameraYaw", local.getYRot()); row.put("ordinaryBoarded", boarded);
            if (natural) {
                Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
                var hit = level.clip(new ClipContext(camera, w.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, local));
                row.put("wallBlocksSight", hit.getType() == HitResult.Type.BLOCK && wallBlocks.containsKey(hit.getBlockPos()));
                row.put("cameraEye", vec(camera));
                if (clientDeck != null) {
                    var position = clientDeck.logicalPose().position();
                    var orientation = clientDeck.logicalPose().orientation();
                    row.put("platformPosition", List.of(position.x(), position.y(), position.z()));
                    row.put("platformRotationQuaternion", List.of(orientation.x(), orientation.y(), orientation.z(), orientation.w()));
                }
            }
            row.put("position", vec(w.position())); row.put("nativeTickCount", w.tickCount);
            row.put("nativeIgnoresCulling", NMSCullingHelper.ignoresCulling(w)); row.put("noCulling", w.noCulling);
            row.put("invisible", w.isInvisible()); row.put("invisibleToLocal", w.isInvisibleTo(local));
            Cullable cull = (Cullable)(Object)w;
            row.put("culled", cull.isCulled()); row.put("outOfCamera", cull.isOutOfCamera()); row.put("forcedVisibleTimeout", cull.isForcedVisible());
            SubLevel tracked = ((EntityMovementExtension)(Object)w).sable$getTrackingSubLevel();
            row.put("tracking", tracked == null ? "" : tracked.getUniqueId().toString()); row.put("trackingRemoved", tracked != null && tracked.isRemoved());
            Vec3 plot = ((EntityStickExtension)(Object)w).sable$getPlotPosition(); row.put("plotPosition", plot == null ? List.of() : vec(plot));
            if (plot != null && clientDeck != null && !clientDeck.isRemoved()) row.put("worldPositionError", clientDeck.logicalPose().transformPosition(plot).distanceTo(w.position()));
            row.put("tickAttempts", w.tickAttempts); row.put("fullEntityTicks", w.fullTicks); row.put("exemptTickDecisions", w.exemptTicks);
            row.put("aiSteps", w.aiSteps); row.put("packetUpdates", w.packetUpdates);
            row.put("renderAttempts", w.renderAttempts); row.put("playerRendererReturns", w.rendererReturns);
            row.put("firstRendererTick", w.firstRendererTick); row.put("lastRendererTick", w.lastRendererTick);
            row.put("settledRenderAttempts", w.settledRenderAttempts); row.put("settledPlayerRendererReturns", w.settledRendererReturns);
            row.put("hiddenSkinDecisions", w.hiddenSkinDecisions); row.put("visibleSkinDecisions", w.visibleSkinDecisions);
            return row;
        }

        void closeStage() {
            for (Witness w : witnesses) { var row = state(w); row.put("firstTick", w.firstTick); stages.add(row); }
        }

        void frame() throws Exception {
            if (pendingShots == 0 || stage < 0) return; pendingShots--;
            String file = String.format(java.util.Locale.ROOT, "%02d-%s-%03d.png", screenshots.size() + 1, stageName(), stageTicks);
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(file)); }
            screenshots.add(Map.of("file", file, "stage", stageName(), "stageTick", stageTicks,
                    "naturalStateObservedBeforeCapture", natural && naturalShotTaken));
        }

        void finish(String status, String reason) {
            if (finishing) return; finishing = true; finalStatus = status; detail = reason;
            keys.keySet().forEach(key -> key.setDown(false));
            for (Witness w : witnesses) if (level.getEntity(w.getId()) == w) level.removeEntity(w.getId(), Entity.RemovalReason.DISCARDED);
            ec.cullTask.disableEntityCulling = oldWorkerDisabled; ec.config.tickCulling = oldTickCulling;
            ec.config.skipEntityCulling = oldSkipCulling; EntityCullingVersionlessBase.enabled = oldEnabled;
            mc.options.setCameraType(oldCamera); mc.setCameraEntity(oldCameraEntity); local.setYRot(oldYaw); local.setXRot(oldPitch);
            mc.options.fov().set(oldFov);
            restoration = server(p -> {
                if (snapshot != null) snapshot.restore(p);
                if (platform != null && !platform.isRemoved()) SubLevelContainer.getContainer(p.serverLevel()).removeSubLevel(platform, SubLevelRemovalReason.REMOVED);
                for (var entry : wallBlocks.entrySet()) p.serverLevel().setBlock(entry.getKey(), entry.getValue(), 3);
                report.put("wallServerRestored", wallBlocks.entrySet().stream().allMatch(e -> p.serverLevel().getBlockState(e.getKey()).equals(e.getValue())));
                return null;
            });
            write("RESTORING", reason);
        }

        void restoreTick() {
            if (restoration == null || !restoration.isDone() || ++restoreTicks < 20) return;
            try {
                restoration.join(); keys.forEach(KeyMapping::setDown);
                boolean removed = platformId == null || SubLevelContainer.getContainer(level).getSubLevel(platformId) == null;
                report.put("platformRemoved", removed);
                report.put("wallClientRestored", wallBlocks.entrySet().stream().allMatch(e -> level.getBlockState(e.getKey()).equals(e.getValue())));
                report.put("witnessesRemoved", witnesses.stream().allMatch(w -> level.getEntity(w.getId()) != w));
                report.put("entityCullingStateRestored", ec.cullTask.disableEntityCulling == oldWorkerDisabled && ec.config.tickCulling == oldTickCulling
                        && ec.config.skipEntityCulling == oldSkipCulling && EntityCullingVersionlessBase.enabled == oldEnabled);
                report.put("inputsRestored", keys.entrySet().stream().allMatch(e -> e.getKey().isDown() == e.getValue()));
                report.put("cameraRestored", mc.options.getCameraType() == oldCamera && mc.getCameraEntity() == oldCameraEntity);
                report.put("fovRestored", mc.options.fov().get() == oldFov);
                report.put("serverStateRestored", true);
                write(removed ? finalStatus : "FAIL", removed ? detail : "Platform removal not received");
            } catch (Throwable error) { write("FAIL", "Restoration failed: " + error); }
            active = null;
        }

        void write(String status, String detail) {
            try {
                report.put("status", status); report.put("detail", detail);
                Files.writeString(output.resolve("sable-occlusion-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
    }

    private static List<Double> vec(Vec3 v) { return List.of(v.x, v.y, v.z); }
    private record Snapshot(Vec3 position, Vec3 movement, float yaw, float pitch, float fallDistance, boolean flying, boolean noGravity, GameType gameType) {
        Snapshot(ServerPlayer p) { this(p.position(), p.getDeltaMovement(), p.getYRot(), p.getXRot(), p.fallDistance, p.getAbilities().flying, p.isNoGravity(), p.gameMode.getGameModeForPlayer()); }
        void restore(ServerPlayer p) {
            p.setGameMode(gameType); p.setNoGravity(noGravity); p.getAbilities().flying = flying; p.onUpdateAbilities();
            p.teleportTo(p.serverLevel(), position.x, position.y, position.z, yaw, pitch); p.setDeltaMovement(movement); p.fallDistance = fallDistance;
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
        }
    }
}
