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
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
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

/** Real Sable plot/collision/render fixture in the offline disposable world only. */
public final class SableDeckSequence {
    private static final UUID OFFLINE_ID = UUID.nameUUIDFromBytes("OfflinePlayer:AttachmentTest".getBytes(StandardCharsets.UTF_8));
    private static final String[] VARIABLES = {"var.pre_posy", "var.verti_drag", "var.vertical_speed", "var.t_land",
            "var.land", "var.fall_amp", "var.Asneak", "var.sneak", "var.sneak2", "varb.falling", "varb.jumping"};
    private static volatile Session active;
    private static int polls;
    private SableDeckSequence() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SableDeckSequence::tick);
        NeoForge.EVENT_BUS.addListener(SableDeckSequence::serverTick);
        NeoForge.EVENT_BUS.addListener(SableDeckSequence::preFrame);
        NeoForge.EVENT_BUS.addListener(SableDeckSequence::postFrame);
    }

    private static boolean disposable(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.hasSingleplayerServer()
                && !mc.getSingleplayerServer().isPublished()
                && mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                && mc.getUser().getType() == User.Type.LEGACY && "AttachmentTest".equals(mc.getUser().getName())
                && OFFLINE_ID.equals(mc.getUser().getProfileId()) && OFFLINE_ID.equals(mc.player.getUUID());
    }

    private static void tick(ClientTickEvent.Post event) {
        Session session = active;
        if (session != null) {
            try { session.tick(); } catch (Throwable error) { session.finish("FAIL", error.toString()); }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (++polls % 20 != 0 || !disposable(mc) || mc.screen != null || !NativeWorldReadiness.ready()) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-sable-deck.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            String[] args = Files.readString(trigger).trim().split("\\s+");
            String label = args[0].replaceAll("[^a-zA-Z0-9._-]", "_");
            boolean firstperson = java.util.Arrays.asList(args).contains("firstperson");
            if(args.length > (firstperson ? 2 : 1)) throw new IllegalArgumentException("Expected label [firstperson]");
            if (label.isBlank() || label.equals(".") || label.equals("..")) label = "sable-deck";
            Files.delete(trigger);
            Session next = new Session(mc, label, firstperson);
            active = next;
            next.prepare();
        } catch (Throwable error) {
            if (active != null) active.finish("FAIL", error.toString());
            else com.mojang.logging.LogUtils.getLogger().error("Sable deck fixture startup failed", error);
        }
    }

    private static void serverTick(ServerTickEvent.Pre event) {
        Session session = active;
        if (session == null || session.finishing || event.getServer() != session.server || session.platform == null) return;
        try { session.drivePlatform(); } catch (Throwable error) { session.serverFailure = error.toString(); }
    }
    private static void preFrame(RenderFrameEvent.Pre event) {
        Session s = active;
        if (s != null && !s.finishing) { s.localDraws = 0; s.remoteDraws = 0; s.handDraws = 0; }
    }
    private static void postFrame(RenderFrameEvent.Post event) {
        Session s = active;
        if (s != null && !s.finishing) try { s.frame(); } catch (Throwable error) { s.finish("FAIL", error.toString()); }
    }

    /** Native PlayerRenderer return, excluding Iris shadows; model state is read only. */
    public static void playerRendered(AbstractClientPlayer player, PlayerModel<?> model) {
        Session s = active;
        if (s == null || s.finishing || s.stage < 0 || (player != s.mc.player && player != s.remote)) return;
        if (ModList.get().isLoaded("iris") && IrisShadow.active()) return;
        if (player == s.mc.player) s.localDraws++; else s.remoteDraws++;
        if (s.lastSampleTick.getOrDefault(player.getId(), -1) == player.tickCount) return;
        s.lastSampleTick.put(player.getId(), player.tickCount);
        Map<String, Object> row = s.entityState(player);
        row.put("kind", player == s.mc.player ? "local" : "remote-client-clone");
        row.put("stage", s.stageName()); row.put("stageTick", s.stageTicks);
        row.put("body", List.of(model.body.x, model.body.y, model.body.z, model.body.xRot, model.body.yRot, model.body.zRot));
        row.put("head", List.of(model.head.x, model.head.y, model.head.z, model.head.xRot, model.head.yRot, model.head.zRot));
        row.put("modelCrouching", model.crouching);
        Map<String, Float> vars = ((EMFEntity)player).emf$getVariableMap();
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String name : VARIABLES) if (vars.containsKey(name)) {
            float value = vars.get(name);
            // EMF encodes booleans as +/-Infinity internally; JSON keeps them actual booleans.
            selected.put(name, name.startsWith("varb.") ? value > 0 : Float.isFinite(value) ? value : Float.toString(value));
        }
        row.put("emfVariables", selected);
        s.renderSamples.add(row);
    }
    private static final class IrisShadow {
        static boolean active() { return net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass(); }
    }

    /** Test-only witness at the production helper input; bounded and one row per player/frame. */
    public static void freshMotion(double ownVerticalMotion, float worldY, float frame) {
        Session s = active;
        if (s == null || s.finishing || s.stage < 0 || s.motionSamples.size() >= 20_000) return;
        var emf = traben.entity_model_features.models.animation.state.EMFState.state();
        if (emf == null || !(emf.emfEntity() instanceof AbstractClientPlayer player)
                || (player != s.local && player != s.remote)) return;
        Map<String, Float> variables = emf.variableMap();
        String context = player.getId()+":"+emf.isFirstPersonHand()+":"+System.identityHashCode(variables);
        Float previousFrame = s.lastMotionFrame.put(context, frame);
        if (previousFrame != null && previousFrame == frame) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", player == s.local ? "local" : "remote-client-clone");
        row.put("stage", s.stageName()); row.put("stageTick", s.stageTicks); row.put("entityTick", player.tickCount);
        row.put("frame", frame); row.put("worldY", worldY);
        row.put("ownVerticalMotion", Double.isFinite(ownVerticalMotion) ? ownVerticalMotion : Double.toString(ownVerticalMotion));
        row.put("worldTickDeltaY", player.getY() - player.yo);
        row.put("motionY", player.getDeltaMovement().y);
        row.put("onGround", player.onGround()); row.put("shift", player.isShiftKeyDown()); row.put("passenger", player.isPassenger());
        row.put("localPlotY", tracking(player) == null ? null : tracking(player).logicalPose().transformPositionInverse(player.position()).y);
        row.put("firstPersonHand", emf.isFirstPersonHand()); row.put("variableMapIdentity",System.identityHashCode(variables));
        row.put("irisShadow",ModList.get().isLoaded("iris") && IrisShadow.active());
        row.put("previousY", variables.get("var.pre_posy")); row.put("draggedY", variables.get("var.verti_drag"));
        row.put("land", variables.get("var.land")); row.put("landingTimer", variables.get("var.t_land"));
        s.motionSamples.add(row);
    }

    /** Native EMF cube compilation, including shader and hand maps; reads actual render state only. */
    public static void handPartCompiled(net.minecraft.client.model.geom.ModelPart part, com.mojang.blaze3d.vertex.PoseStack.Pose pose) {
        Session s=active;
        if(s==null || s.finishing || !s.firstperson || s.stage<0 || s.nativeMapSamples.size()>=6000)return;
        var state=traben.entity_model_features.models.animation.state.EMFState.state();
        if(state==null || state.emfEntity()!=s.local)return;
        boolean hand=state.isFirstPersonHand(),shadow=ModList.get().isLoaded("iris") && IrisShadow.active();
        if(hand && !shadow)s.handDraws++;
        Map<String,Float> variables=state.variableMap();
        String key=hand+":"+shadow+":"+System.identityHashCode(variables);
        if(s.lastMapTick.getOrDefault(key,-1)==s.local.tickCount)return;
        s.lastMapTick.put(key,s.local.tickCount);
        Map<String,Object> row=s.entityState(s.local);
        row.put("stage",s.stageName());row.put("stageTick",s.stageTicks);row.put("firstPersonHand",hand);row.put("irisShadow",shadow);
        row.put("variableMapIdentity",System.identityHashCode(variables));row.put("partClass",part.getClass().getName());
        row.put("pose",ModelState.state(part));row.put("matrix",pose.pose().get(new float[16]));row.put("cubeCount",ModelState.cubeCount(part));
        Map<String,Object> selected=new LinkedHashMap<>();
        for(String name:VARIABLES)if(variables.containsKey(name)){float value=variables.get(name);selected.put(name,name.startsWith("varb.")?value>0:Float.isFinite(value)?value:Float.toString(value));}
        row.put("emfVariables",selected);s.nativeMapSamples.add(row);
    }

    private static final class DeckPlayer extends RemotePlayer {
        final PlayerSkin skin;
        final PlayerTeam team = new PlayerTeam(new Scoreboard(), "sable-deck-clone");
        DeckPlayer(Minecraft mc, int id, Vec3 position) {
            super(mc.level, new GameProfile(UUID.randomUUID(), "DeckWitness"));
            skin = mc.player.getSkin();
            team.setNameTagVisibility(Team.Visibility.NEVER);
            setId(id); setPos(position); setYRot(180); yBodyRot = yBodyRotO = yHeadRot = yHeadRotO = 180;
            setOldPosAndRot(); setOnGround(true); setNoGravity(true);
        }
        @Override public PlayerSkin getSkin() { return skin != null ? skin : Minecraft.getInstance().player.getSkin(); }
        @Override public PlayerTeam getTeam() { return team; }
        @Override public boolean isModelPartShown(PlayerModelPart part) { return true; }
    }

    private static final class Session {
        final Minecraft mc;
        final boolean firstperson;
        final net.minecraft.client.server.IntegratedServer server;
        final net.minecraft.client.multiplayer.ClientLevel clientLevel;
        final net.minecraft.client.player.LocalPlayer local;
        final Path output;
        final CameraType originalCamera;
        final Entity originalCameraEntity;
        final float clientYaw, clientPitch;
        final Map<KeyMapping, Boolean> keys = new LinkedHashMap<>();
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<Map<String, Object>> renderSamples = new ArrayList<>(), tickSamples = new ArrayList<>(), screenshots = new ArrayList<>(), stages = new ArrayList<>();
        final List<Map<String, Object>> motionSamples = new ArrayList<>(), nativeMapSamples = new ArrayList<>();
        final Map<String,Integer> lastMapTick = new LinkedHashMap<>();
        final Map<Integer, Integer> lastSampleTick = new LinkedHashMap<>();
        final Map<String, Float> lastMotionFrame = new LinkedHashMap<>();
        final long deadline = System.nanoTime() + 180_000_000_000L;
        volatile ServerSubLevel platform;
        volatile Vec3 platformBase, landingPosition;
        volatile UUID platformId;
        volatile String serverFailure;
        volatile Map<String, Object> preparationEvidence = Map.of();
        volatile boolean finishing;
        volatile int stage = -1;
        volatile int driveTick;
        Snapshot original;
        CompletableFuture<?> preparation, restoration, releaseGravity;
        DeckPlayer remote;
        int ticks, stableTicks, stageTicks, restoreTicks, localDraws, remoteDraws, pendingShot;
        int stageFrames, localRenderedFrames, remoteRenderedFrames, handRenderedFrames, handDraws, clientTrackingTicks, localTrackingTicks;
        String finalStatus, finalDetail;
        double stageStartX, stageStartY, maxPlatformTravel;

        Session(Minecraft mc, String label, boolean firstperson) throws Exception {
            this.firstperson=firstperson;
            if(firstperson && ModList.get().isLoaded("firstperson"))throw new IllegalStateException("FirstPerson must be physically absent");
            if (mc.player.isPassenger() || mc.player.isUsingItem() || mc.player.isInWaterOrBubble())
                throw new IllegalStateException("Start unmounted and not using an item or swimming");
            this.mc = mc; server = mc.getSingleplayerServer(); clientLevel = mc.level; local = mc.player;
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);
            Files.createDirectories(output);
            originalCamera = mc.options.getCameraType(); originalCameraEntity = mc.getCameraEntity();
            clientYaw = local.getYRot(); clientPitch = local.getXRot();
            for (KeyMapping key : List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                    mc.options.keyShift, mc.options.keyJump, mc.options.keySprint, mc.options.keyUse, mc.options.keyAttack)) {
                keys.put(key, key.isDown()); key.setDown(false);
            }
            mc.options.setCameraType(firstperson ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_FRONT); mc.setCameraEntity(local);
            report.put("label", label); report.put("startedAt", Instant.now().toString());
            report.put("scope", "Real server-owned Sable platform and native local-player collision/carry, plus a client-only RemotePlayer seeded once with Sable plot coordinates. Controlled platform trajectory uses native PhysicsPipeline teleport/resetVelocity; not an Aeronautics propulsion simulation or multiplayer packet test.");
            report.put("firstPersonHandMode",firstperson);
            report.put("shaderPackActive",ModList.get().isLoaded("iris") && net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse());
            report.put("nativeMapSamples",nativeMapSamples);
            report.put("firstPersonInstalled", ModList.get().isLoaded("firstperson"));
            report.put("productionMixinsDisabled", Boolean.getBoolean("emfattachments.disable"));
            report.put("resourcePacks", mc.getResourcePackRepository().getSelectedIds());
            report.put("stages", stages); report.put("renderSamples", renderSamples); report.put("tickSamples", tickSamples); report.put("screenshots", screenshots);
            report.put("freshMotionSamples", motionSamples); report.put("freshMotionSampleLimit", 20_000);
            report.put("limitations", List.of("Remote player is a client-only clone, so server tracking/network visibility is not certified.",
                    "PlayerRenderer return is not proof of visible GPU geometry; screenshots require review.",
                    "Platform is driven with a bounded native physics-pipeline trajectory, not a fully constructed aircraft.",
                    "The fixture records evidence and does not automatically declare animation alignment correct."));
        }

        <T> CompletableFuture<T> server(Function<ServerPlayer, T> action) {
            CompletableFuture<T> result = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    if (server.isPublished()) throw new IllegalStateException("Integrated server was published");
                    ServerPlayer p = server.getPlayerList().getPlayer(OFFLINE_ID);
                    if (p == null) throw new IllegalStateException("Offline player missing");
                    result.complete(action.apply(p));
                } catch (Throwable error) { result.completeExceptionally(error); }
            });
            return result;
        }

        void prepare() {
            preparation = server(p -> {
                original = new Snapshot(p);
                if(firstperson) { p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,net.minecraft.world.item.ItemStack.EMPTY);p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,net.minecraft.world.item.ItemStack.EMPTY);p.inventoryMenu.broadcastChanges(); }
                var container = SubLevelContainer.getContainer(p.serverLevel());
                if (container == null) throw new IllegalStateException("No Sable container");
                int x = p.getBlockX() + 24, z = p.getBlockZ() + 24;
                int y = p.serverLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 12;
                Pose3d pose = new Pose3d(); pose.position().set(x + .5, y, z + .5);
                ServerSubLevel owned = (ServerSubLevel)container.allocateNewSubLevel(pose);
                platform = owned; platformId = owned.getUniqueId(); owned.setName("EMF disposable deck fixture");
                var plot = owned.getPlot(); ChunkPos center = plot.getCenterChunk();
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) plot.newEmptyChunk(new ChunkPos(center.x + dx, center.z + dz));
                for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++)
                    plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(dx, 0, dz), dx == 0 || dz == 0 ? Blocks.QUARTZ_BLOCK.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState(), 3);
                int actualBlocks = 0;
                for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++)
                    if (!plot.getEmbeddedLevelAccessor().getBlockState(new BlockPos(dx, 0, dz)).isAir()) actualBlocks++;
                if (actualBlocks != 121 || owned.getMassTracker().getMass() <= 0 || owned.getMassTracker().getCenterOfMass() == null)
                    throw new IllegalStateException("Owned platform blocks/mass were not initialized");
                platformBase = new Vec3(x + .5, y, z + .5);
                owned.logicalPose().position().set(platformBase.x, platformBase.y, platformBase.z);
                owned.updateLastPose();
                container.physicsSystem().getPipeline().teleport(owned, new Vector3d(platformBase.x, platformBase.y, platformBase.z), new Quaterniond());
                BlockPos anchor = plot.getCenterBlock();
                // EmbeddedPlotLevelAccessor adds the full plot center, whose Y is the dimension midpoint.
                Vec3 spawn = owned.logicalPose().transformPosition(new Vec3(anchor.getX() + .5, anchor.getY() + 2, anchor.getZ() - 1.5));
                if (spawn.distanceTo(platformBase) > 10 || spawn.y < p.serverLevel().getMinBuildHeight() + 8)
                    throw new IllegalStateException("Owned deck spawn transform was outside its nearby world bounds: " + spawn);
                landingPosition = spawn;
                preparationEvidence = Map.of("blockCount", actualBlocks, "mass", owned.getMassTracker().getMass(),
                        "plotCenter", List.of(anchor.getX(), anchor.getY(), anchor.getZ()), "spawnWorldPosition", vec(spawn),
                        "platformWorldBase", vec(platformBase), "rotationPoint", List.of(owned.logicalPose().rotationPoint().x(), owned.logicalPose().rotationPoint().y(), owned.logicalPose().rotationPoint().z()));
                // Hold only during network/chunk preparation; native gravity resumes before measurement.
                p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = false; p.onUpdateAbilities(); p.setNoGravity(true);
                p.teleportTo(p.serverLevel(), spawn.x, spawn.y, spawn.z, 180, 8);
                p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
                return null;
            });
            write("PREPARING", "");
        }

        void drivePlatform() {
            if (platformBase == null || platform.isRemoved()) return;
            var pipeline = SubLevelContainer.getContainer(platform.getLevel()).physicsSystem().getPipeline();
            int t = driveTick++;
            double x = platformBase.x, y = platformBase.y;
            double tilt = 0;
            if (stage == 1) { x += Math.min(t, 180) * .025; y -= Math.min(t, 180) * .01; }
            if (stage >= 2) { x += 4.5; y -= 1.8; }
            if (stage == 2) tilt = .04 * Math.sin(t * Math.PI / 60);
            // Only the owned body is controlled. Local and remote players receive no per-tick position correction.
            pipeline.resetVelocity(platform);
            pipeline.teleport(platform, new Vector3d(x, y, platformBase.z), new Quaterniond().rotationZ(tilt));
        }

        void tick() {
            if (finishing) { restoreTick(); return; }
            if (mc.level != clientLevel || mc.player != local || mc.getSingleplayerServer() != server || server.isPublished())
                throw new IllegalStateException("Disposable world identity changed");
            if (serverFailure != null) throw new IllegalStateException(serverFailure);
            if (System.nanoTime() > deadline) throw new IllegalStateException("Bounded fixture deadline exceeded");
            if (mc.screen != null || !local.isAlive()) throw new IllegalStateException("GUI or player death interrupted fixture");
            ticks++;
            if (!preparation.isDone()) return;
            preparation.join();
            report.put("preparation", preparationEvidence);
            var clientContainer = SubLevelContainer.getContainer(clientLevel);
            SubLevel deck = clientContainer == null || platformId == null ? null : clientContainer.getSubLevel(platformId);
            if (stage < 0) {
                if (ticks > 240) throw new IllegalStateException("Native deck collision readiness timeout at " + local.position());
                if (deck != null && !clientLevel.getBlockState(deck.getPlot().getCenterBlock()).isAir() && releaseGravity == null)
                    releaseGravity = server(p -> { p.setNoGravity(false); return null; });
                if (releaseGravity == null || !releaseGravity.isDone()) return;
                releaseGravity.join();
                boolean attached = deck != null && tracking(local) == deck && local.onGround() && (!firstperson || local.getMainHandItem().isEmpty() && local.getOffhandItem().isEmpty());
                stableTicks = attached ? stableTicks + 1 : 0;
                if (stableTicks < 12) return;
                int id = Integer.MIN_VALUE + 4096; while (clientLevel.getEntity(id) != null) id++;
                BlockPos center = deck.getPlot().getCenterBlock();
                Vec3 plotPosition = new Vec3(center.getX() + 2.5, center.getY() + 1, center.getZ() + .5);
                remote = new DeckPlayer(mc, id, deck.logicalPose().transformPosition(plotPosition));
                ((EntityStickExtension)(Object)remote).sable$setPlotPosition(plotPosition);
                ((EntityMovementExtension)(Object)remote).sable$setTrackingSubLevel(deck);
                clientLevel.addEntity(remote);
                local.setYRot(180); local.setXRot(firstperson ? 25 : 8);
                startStage(0); return;
            }
            stageTicks++;
            if (deck == null || deck.isRemoved() || clientLevel.getEntity(remote.getId()) != remote)
                throw new IllegalStateException("Owned deck or remote clone disappeared from client registry");
            if (tracking(local) == deck) localTrackingTicks++;
            if (tracking(remote) == deck) clientTrackingTicks++;
            maxPlatformTravel = Math.max(maxPlatformTravel, Math.hypot(deck.logicalPose().position().x() - stageStartX, deck.logicalPose().position().y() - stageStartY));
            if (stageTicks % 2 == 0) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stage", stageName()); row.put("stageTick", stageTicks);
                row.put("local", entityState(local)); row.put("remote", entityState(remote));
                row.put("sublevelPosition", List.of(deck.logicalPose().position().x(), deck.logicalPose().position().y(), deck.logicalPose().position().z()));
                Vec3 expected = deck.logicalPose().transformPosition(((EntityStickExtension)(Object)remote).sable$getPlotPosition());
                row.put("remoteWorldPositionError", expected.distanceTo(remote.position()));
                tickSamples.add(row);
            }
            if (stage == 3) mc.options.keyShift.setDown(stageTicks < 45);
            if (stage == 4) mc.options.keyUp.setDown(stageTicks < 18);
            if (stage == 5) mc.options.keyJump.setDown(stageTicks == 2);
            if (stageTicks == 40 || stageTicks == 100 || stageTicks == 160) pendingShot++;
            if (stageTicks >= 180) {
                keys.keySet().forEach(key -> key.setDown(false));
                closeStage();
                if (stage == 5) finish("CAPTURED", "Native deck sequence captured; requires evidence and image review, not an automatic visual pass.");
                else startStage(stage + 1);
            }
        }

        String stageName() {
            return switch (stage) {
                case 0 -> "stationary-no-input";
                case 1 -> "translate-descend-no-input";
                case 2 -> "rock-no-input";
                case 3 -> "intentional-crouch-release";
                case 4 -> "intentional-walk-stop";
                case 5 -> "intentional-jump-land";
                default -> "preparing";
            };
        }
        void startStage(int next) {
            stage = next; driveTick = 0; stageTicks = 0;
            stageFrames = localRenderedFrames = remoteRenderedFrames = handRenderedFrames = localTrackingTicks = clientTrackingTicks = 0;
            maxPlatformTravel = 0;
            SubLevel deck = SubLevelContainer.getContainer(clientLevel).getSubLevel(platformId);
            stageStartX = deck.logicalPose().position().x(); stageStartY = deck.logicalPose().position().y();
            write("RUNNING", stageName());
        }
        void closeStage() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stageName()); row.put("ticks", stageTicks); row.put("frames", stageFrames);
            row.put("nativeHandRenderedFrames",handRenderedFrames); row.put("localRenderedFrames", localRenderedFrames); row.put("remoteRenderedFrames", remoteRenderedFrames);
            row.put("localTrackingTicks", localTrackingTicks); row.put("remoteTrackingTicks", clientTrackingTicks);
            row.put("maximumPlatformTravel", maxPlatformTravel); stages.add(row);
        }
        void frame() throws Exception {
            if (stage < 0) return;
            stageFrames++; if(handDraws>0)handRenderedFrames++; if (localDraws > 0) localRenderedFrames++; if (remoteDraws > 0) remoteRenderedFrames++;
            if (pendingShot <= 0) return; pendingShot--;
            String file = String.format(java.util.Locale.ROOT, "%02d-%s-%03d.png", screenshots.size() + 1, stageName(), stageTicks);
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(file)); }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", file); row.put("stage", stageName()); row.put("stageTick", stageTicks);
            row.put("nativeHandCompiles",handDraws); row.put("localRendererReturns", localDraws); row.put("remoteRendererReturns", remoteDraws);
            row.put("local", entityState(local)); row.put("remote", entityState(remote)); screenshots.add(row);
        }
        Map<String, Object> entityState(AbstractClientPlayer entity) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("position", vec(entity.position())); row.put("oldPosition", List.of(entity.xo, entity.yo, entity.zo));
            row.put("motion", vec(entity.getDeltaMovement())); row.put("pose", entity.getPose().name());
            row.put("onGround", entity.onGround()); row.put("crouching", entity.isCrouching()); row.put("shift", entity.isShiftKeyDown());
            row.put("passenger", entity.isPassenger()); row.put("invisible", entity.isInvisible()); row.put("tickCount", entity.tickCount);
            SubLevel tracked = tracking(entity); row.put("trackingSublevel", tracked == null ? "" : tracked.getUniqueId().toString());
            Vec3 plotPosition = ((EntityStickExtension)entity).sable$getPlotPosition(); row.put("plotPosition", plotPosition == null ? List.of() : vec(plotPosition));
            return row;
        }
        void finish(String status, String detail) {
            if (finishing) return; finishing = true; finalStatus = status; finalDetail = detail;
            keys.keySet().forEach(key -> key.setDown(false));
            if (remote != null && clientLevel.getEntity(remote.getId()) == remote) clientLevel.removeEntity(remote.getId(), Entity.RemovalReason.DISCARDED);
            mc.options.setCameraType(originalCamera); mc.setCameraEntity(originalCameraEntity);
            local.setYRot(clientYaw); local.setXRot(clientPitch);
            write("RESTORING", detail);
            restoration = server(p -> {
                if (original != null) original.restore(p);
                if (platform != null && !platform.isRemoved()) SubLevelContainer.getContainer(p.serverLevel()).removeSubLevel(platform, SubLevelRemovalReason.REMOVED);
                return null;
            });
        }
        void restoreTick() {
            if (restoration == null || !restoration.isDone() || ++restoreTicks < 20) return;
            try {
                restoration.join(); keys.forEach(KeyMapping::setDown);
                boolean removed = platformId == null || SubLevelContainer.getContainer(clientLevel).getSubLevel(platformId) == null;
                report.put("platformRemovedFromClient", removed);
                report.put("remoteRemoved", remote == null || clientLevel.getEntity(remote.getId()) != remote);
                report.put("inputsRestored", keys.entrySet().stream().allMatch(entry -> entry.getKey().isDown() == entry.getValue()));
                report.put("cameraRestored", mc.options.getCameraType() == originalCamera && mc.getCameraEntity() == originalCameraEntity);
                report.put("serverStateRestored", true);
                report.put("heldItemsRestored",original==null || net.minecraft.world.item.ItemStack.matches(original.mainHand(),local.getMainHandItem()) && net.minecraft.world.item.ItemStack.matches(original.offHand(),local.getOffhandItem()));
                write(removed ? finalStatus : "FAIL", removed ? finalDetail : "Owned platform removal not received");
            } catch (Throwable error) { write("FAIL", "Restoration failed: " + error); }
            active = null;
        }
        void write(String status, String detail) {
            try {
                report.put("status", status); report.put("detail", detail); report.put("finished", status.equals("CAPTURED") || status.equals("FAIL"));
                if (platformId != null) report.put("platformId", platformId.toString());
                Files.writeString(output.resolve("sable-deck-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
    }
    private static SubLevel tracking(Entity entity) { return ((EntityMovementExtension)entity).sable$getTrackingSubLevel(); }
    private static List<Double> vec(Vec3 value) { return List.of(value.x, value.y, value.z); }
    private record Snapshot(Vec3 position, Vec3 movement, float yaw, float pitch, float fallDistance, boolean flying, boolean noGravity, GameType gameType, net.minecraft.world.item.ItemStack mainHand, net.minecraft.world.item.ItemStack offHand) {
        Snapshot(ServerPlayer p) { this(p.position(), p.getDeltaMovement(), p.getYRot(), p.getXRot(), p.fallDistance, p.getAbilities().flying, p.isNoGravity(), p.gameMode.getGameModeForPlayer(),p.getMainHandItem().copy(),p.getOffhandItem().copy()); }
        void restore(ServerPlayer p) {
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,mainHand.copy());p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,offHand.copy());p.inventoryMenu.broadcastChanges();
            p.setShiftKeyDown(false); p.setGameMode(gameType); p.setNoGravity(noGravity); p.getAbilities().flying = flying; p.onUpdateAbilities();
            p.teleportTo(p.serverLevel(), position.x, position.y, position.z, yaw, pitch); p.setDeltaMovement(movement); p.fallDistance = fallDistance;
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
        }
    }
}
