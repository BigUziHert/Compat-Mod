package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import io.socol.betterthirdperson.BetterThirdPerson;
import io.socol.betterthirdperson.api.util.Rotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Records native walking, jumping and creative flight on a fast Sable deck with no Create elevator. */
final class SableAirshipMotionProbe {
    private static final UUID OFFLINE_PLAYER = UUID.nameUUIDFromBytes(
            "OfflinePlayer:AttachmentTest".getBytes(StandardCharsets.UTF_8));
    private static final double SPEED = 1000.0 / 60;
    private static volatile Session active;

    private SableAirshipMotionProbe() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(SableAirshipMotionProbe::clientTick);
        NeoForge.EVENT_BUS.addListener(SableAirshipMotionProbe::serverTick);
        NeoForge.EVENT_BUS.addListener(SableAirshipMotionProbe::beforeFrame);
        NeoForge.EVENT_BUS.addListener(SableAirshipMotionProbe::renderFrame);
    }

    private static void clientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        Session session = active;
        if (session != null) {
            try {
                session.clientTick();
            } catch (Exception failure) {
                session.fail(failure);
            }
            return;
        }
        if (client.player == null || client.level == null || client.getSingleplayerServer() == null
                || client.getSingleplayerServer().isPublished() || client.getUser().getType() != User.Type.LEGACY
                || !OFFLINE_PLAYER.equals(client.player.getUUID()) || !OFFLINE_PLAYER.equals(client.getUser().getProfileId())
                || !client.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                || client.getOverlay() != null) return;
        Path trigger = client.gameDirectory.toPath().resolve("emfattachments-sable-airship.txt");
        if (!Files.isRegularFile(trigger)) return;
        if (client.screen instanceof net.minecraft.client.gui.screens.DeathScreen && client.player.isDeadOrDying()) {
            LogUtils.getLogger().info("Respawning disposable fixture player before new deck scenario");
            client.player.respawn();
            client.setScreen(null);
            return;
        }
        if (client.screen != null) return;
        try {
            List<String> args = List.of(Files.readString(trigger).strip().split("\\s+"));
            Files.delete(trigger);
            boolean survival = args.size() >= 4 && args.getLast().equals("survival");
            List<String> actions = args.size() >= 2 ? args.subList(2, args.size() - (survival ? 1 : 0)) : List.of();
            boolean landingOnly = !actions.isEmpty() && List.of("slippers-jump", "reference-jump").contains(actions.getFirst());
            boolean flightOnly = actions.equals(List.of("slippers-flight")) || actions.equals(List.of("reference-flight"));
            boolean sprintJump = actions.equals(List.of("sprint-jump"))
                    || landingOnly && actions.size() == 2 && actions.getLast().equals("sprint-jump");
            boolean walkOff = actions.equals(List.of("walk-off"));
            if (args.size() < 2 || survival && !landingOnly || !actions.isEmpty() && !sprintJump && !walkOff && !flightOnly && !(landingOnly && actions.size() == 1)
                    || !List.of("rear", "left", "first").contains(args.get(0))
                    || !List.of("x", "z").contains(args.get(1))) {
                throw new IllegalArgumentException("Expected rear|left|first x|z [sprint-jump|walk-off|"
                        + "slippers-jump [sprint-jump] [survival]|reference-jump [sprint-jump] [survival]|slippers-flight|reference-flight]");
            }
            active = new Session(client, args.get(0), args.get(1), walkOff, sprintJump,
                    landingOnly, flightOnly, actions.contains("slippers-jump") || actions.contains("slippers-flight"), survival);
        } catch (Exception failure) {
            if (active != null) active.fail(failure);
            else LogUtils.getLogger().error("Could not start disposable airship motion fixture", failure);
        }
    }

    private static void serverTick(ServerTickEvent.Pre event) {
        Session session = active;
        if (session == null || event.getServer() != session.server) return;
        ServerPlayer player = session.server.getPlayerList().getPlayer(OFFLINE_PLAYER);
        if (player == null) return;
        try {
            session.serverTick(player);
        } catch (Exception failure) {
            session.fail(failure);
        }
    }

    private static void beforeFrame(RenderFrameEvent.Pre event) {
        Session session = active;
        if (session != null && !session.stopping && session.inputTick >= 0) {
            session.camera.applyOrbit(session.client);
        }
    }

    private static void renderFrame(RenderFrameEvent.Post event) {
        Session session = active;
        if (session == null || session.stopping || session.deckId == null || session.inputTick < 0) return;
        try {
            session.frame();
        } catch (Exception failure) {
            session.fail(failure);
        }
    }

    private static final class Session {
        private static final int WALK_AT = 12;
        private static final int RUN_AT = 34;
        private static final int RUN_END = 52;
        private static final int JUMP_AT = 63;
        private static final int FLY_AT = 99;
        private static final int END_AT = 137;
        final Minecraft client;
        final IntegratedServer server;
        final String view, axis;
        final SableElevatorCameraProbe camera;
        final boolean forwardWasDown, jumpWasDown, sprintWasDown;
        final List<Map<String, Object>> clientRows = new ArrayList<>();
        final List<Map<String, Object>> serverRows = new ArrayList<>();
        final List<Map<String, Object>> frames = new ArrayList<>();
        volatile int inputTick = -1;
        volatile UUID deckId;
        volatile boolean visible, standing, stopping, restored;
        volatile String failure = "";
        ServerSubLevel deck;
        PhysicsPipeline pipeline;
        Snapshot snapshot;
        BlockPos origin;
        Vec3 deckFloor;
        double holdHeight;
        int warmupTicks, rampTicks, setupTicks;
        boolean selectedCamera;
        final boolean sprintJump, walkOff, landingOnly, flightOnly, checkEquipment, slippers;
        final int deckRadius;
        boolean leftDeck;
        BunnySlippersEquipment.Saved savedEquipment;
        final GameType requestedMode;
        int originalFood;
        float originalSaturation, originalExhaustion;
        volatile boolean serverEquipmentReady;
        final JumpWindow clientJump = new JumpWindow(), serverJump = new JumpWindow();
        int clientFlyingTicks;
        volatile int serverFlyingTicks;

        Session(Minecraft client, String view, String axis, boolean walkOff,
                boolean sprintJump, boolean landingOnly, boolean flightOnly, boolean slippers, boolean survival) {
            this.client = client;
            this.server = client.getSingleplayerServer();
            this.view = view;
            this.axis = axis;
            this.walkOff = walkOff;
            this.sprintJump = sprintJump;
            this.landingOnly = landingOnly;
            this.flightOnly = flightOnly;
            checkEquipment = landingOnly || flightOnly;
            this.slippers = slippers;
            requestedMode = survival ? GameType.SURVIVAL : GameType.CREATIVE;
            // Give the extended sprint jump room to land. Paired barefoot controls use the same footprint.
            deckRadius = checkEquipment ? 15 : 9;
            camera = new SableElevatorCameraProbe(view);
            forwardWasDown = client.options.keyUp.isDown();
            jumpWasDown = client.options.keyJump.isDown();
            sprintWasDown = client.options.keySprint.isDown();
        }

        void serverTick(ServerPlayer player) {
            if (restored) return;
            if (stopping) {
                try {
                    ExpandedRagdollRecovery.release(player);
                    if (deck != null && !deck.isRemoved()) SubLevelContainer.getContainer(deck.getLevel())
                            .removeSubLevel(deck, SubLevelRemovalReason.REMOVED);
                } finally {
                    try {
                        if (savedEquipment != null) savedEquipment.restore(player);
                    } finally {
                        if (snapshot != null && requestedMode == GameType.SURVIVAL) {
                            player.getFoodData().setFoodLevel(originalFood);
                            player.getFoodData().setSaturation(originalSaturation);
                            player.getFoodData().setExhaustion(originalExhaustion);
                        }
                        if (snapshot != null) snapshot.restore(player);
                        restored = true;
                    }
                }
                return;
            }
            if (deck == null) build(player);
            if (checkEquipment && !serverEquipmentReady) serverEquipmentReady = BunnySlippersEquipment.ready(player, slippers);
            if (deck.isRemoved()) throw new IllegalStateException("The fixture deck unloaded");
            if (visible && player.isNoGravity()) player.setNoGravity(false);
            double speed = standing ? SPEED * Math.min(++rampTicks / 12.0, 1.0) : 0;
            var correction = pipeline.getLinearVelocity(deck, new Vector3d()).negate();
            correction.add(axis.equals("x") ? speed : 0,
                    0.25 + 3 * (holdHeight - deck.logicalPose().transformPosition(deckFloor).y),
                    axis.equals("z") ? speed : 0);
            pipeline.addLinearAndAngularVelocity(deck, correction, pipeline.getAngularVelocity(deck, new Vector3d()).negate());
            int sampledTick = inputTick;
            if (sampledTick >= 0) {
                var row = sample(player, deck, sampledTick);
                row.put("ragdolled", ExpandedRagdollRecovery.active(player));
                serverRows.add(row);
                if (landingOnly) observeJump(player, deck, sampledTick, serverJump, row);
                if (flightOnly && sampledTick >= JUMP_AT - 2) {
                    observeEquipment(player, sampledTick, row);
                    if (player.getAbilities().flying) serverFlyingTicks++;
                }
            }
        }

        private void build(ServerPlayer player) {
            // A preceding fixture's return teleport can trigger an archive-configured ragdoll after teardown.
            ExpandedRagdollRecovery.release(player);
            if (player.isPassenger()) throw new IllegalStateException("The fixture requires an unmounted player");
            snapshot = new Snapshot(player);
            if (requestedMode == GameType.SURVIVAL) {
                originalFood = player.getFoodData().getFoodLevel();
                originalSaturation = player.getFoodData().getSaturationLevel();
                originalExhaustion = player.getFoodData().getExhaustionLevel();
            }
            if (checkEquipment) savedEquipment = slippers ? BunnySlippersEquipment.equip(player) : BunnySlippersEquipment.bare(player);
            var level = player.serverLevel();
            var container = SubLevelContainer.getContainer(level);
            int x = player.getBlockX() + 24;
            int z = player.getBlockZ() + 24;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 35;
            Pose3d pose = new Pose3d();
            pose.position().set(x + .5, y, z + .5);
            deck = (ServerSubLevel) container.allocateNewSubLevel(pose);
            deck.setName("Disposable Sable airship motion fixture");
            var plot = deck.getPlot();
            origin = plot.getCenterBlock();
            ChunkPos center = plot.getCenterChunk();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) plot.newEmptyChunk(new ChunkPos(center.x + dx, center.z + dz));
            }
            for (int dx = -deckRadius; dx <= deckRadius; dx++) {
                for (int dz = -deckRadius; dz <= deckRadius; dz++) {
                    plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(dx, 0, dz),
                            (dx == 0 || dz == 0 ? Blocks.QUARTZ_BLOCK : Blocks.OAK_PLANKS).defaultBlockState(), 3);
                }
            }
            if (deck.getMassTracker().getMass() <= 0) throw new IllegalStateException("The fixture deck has no mass");
            deck.logicalPose().position().set(x + .5, y, z + .5);
            deck.updateLastPose();
            pipeline = container.physicsSystem().getPipeline();
            pipeline.teleport(deck, new Vector3d(x + .5, y, z + .5), new Quaterniond());
            deckFloor = Vec3.atBottomCenterOf(origin).add(0, 1, 0);
            Vec3 spawn = deck.logicalPose().transformPosition(deckFloor.add(0, 0.25, 0));
            holdHeight = deck.logicalPose().transformPosition(deckFloor).y;
            player.setGameMode(requestedMode);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            player.setNoGravity(true);
            player.teleportTo(level, spawn.x, spawn.y, spawn.z, yaw(), 0);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0;
            deckId = deck.getUniqueId();
        }

        void clientTick() throws Exception {
            if (stopping) {
                if (restored) finish();
                return;
            }
            SubLevel current = clientDeck();
            if (inputTick < 0) {
                if (++setupTicks > 180) throw new IllegalStateException("Deck setup did not settle");
                visible = current != null && !client.level.getBlockState(current.getPlot().getCenterBlock()).isAir();
                if (visible && client.player.onGround() && Sable.HELPER.getTrackingSubLevel(client.player) == current) {
                    standing = true;
                }
                if (!standing || ++warmupTicks < 32) return;
                if (checkEquipment && (!serverEquipmentReady || !BunnySlippersEquipment.ready(client.player, slippers))) return;
                if (!selectedCamera) {
                    camera.begin(client);
                    selectedCamera = true;
                }
                camera.ensureAtStart(client);
                if (!camera.matches(client)) return;
                aim(yaw());
            }
            if (current == null || current.isRemoved()) throw new IllegalStateException("The client lost the deck");
            int tick = ++inputTick;
            Map<String, Object> row = sample(client.player, current, tick);
            row.put("cameraMatches", camera.matches(client));
            row.put("ragdolled", !ExpandedRagdollRecovery.clientRestored(client.player));
            row.put("forwardInput", client.player.input.up);
            row.put("jumpInput", client.player.input.jumping);
            clientRows.add(row);
            if (flightOnly) {
                if (tick >= JUMP_AT - 2) observeEquipment(client.player, tick, row);
                if (tick == JUMP_AT && (!client.player.onGround() || client.player.getAbilities().flying
                        || Sable.HELPER.getTrackingSubLevel(client.player) != current)) {
                    throw new IllegalStateException("Native flight first tap requires standing support");
                }
                if (client.player.getAbilities().flying) clientFlyingTicks++;
                client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
                client.options.keyJump.setDown(tick == JUMP_AT || tick >= JUMP_AT + 5 && tick < JUMP_AT + 20);
                if (tick == JUMP_AT + 48) {
                    if (clientFlyingTicks < 30 || serverFlyingTicks < 30) throw new IllegalStateException("Double tap did not produce sustained native flight");
                    stopping = true;
                }
                return;
            }
            if (landingOnly) {
                observeJump(client.player, current, tick, clientJump, row);
                boolean runToJump = sprintJump && tick >= JUMP_AT - 6 && tick <= JUMP_AT + 1;
                client.options.keyUp.setDown(runToJump);
                client.options.keySprint.setDown(runToJump);
                client.options.keyJump.setDown(tick == JUMP_AT);
                if (tick == JUMP_AT && (!client.player.onGround() || Sable.HELPER.getTrackingSubLevel(client.player) != current
                        || !BunnySlippersEquipment.ready(client.player, slippers)
                        || client.player.isSprinting() != sprintJump || client.player.input.up != sprintJump)) {
                    throw new IllegalStateException("Native jump setup differed from the requested support, loadout or sprint input");
                }
                if (clientJump.landed >= 0 && serverJump.landed >= 0
                        && tick >= Math.max(clientJump.landed, serverJump.landed) + 6) stopping = true;
                if (tick >= JUMP_AT + 100) throw new IllegalStateException("Native jump did not land on the test deck on both sides");
                return;
            }
            if (walkOff) {
                if (tick > WALK_AT && Sable.HELPER.getTrackingSubLevel(client.player) != current
                        && !client.player.onGround()) leftDeck = true;
                client.options.keyUp.setDown(tick >= WALK_AT && !leftDeck);
                client.options.keySprint.setDown(false);
                client.options.keyJump.setDown(false);
                if (tick >= END_AT) stopping = true;
                return;
            }
            boolean running = tick >= RUN_AT && tick < RUN_END
                    || sprintJump && tick >= JUMP_AT - 6 && tick <= JUMP_AT + 1;
            if (tick == RUN_AT) aim(yaw() + 180);
            if (tick == RUN_END) camera.resetLook();
            camera.stopOrbitRequest();
            if (running) camera.lookAround(tick - RUN_AT + 1);
            client.options.keyUp.setDown((tick >= WALK_AT && tick < WALK_AT + 16) || running);
            client.options.keySprint.setDown(running);
            client.options.keyJump.setDown(tick == JUMP_AT || tick == FLY_AT || (tick >= FLY_AT + 5 && tick < FLY_AT + 20));
            if (tick >= END_AT) stopping = true;
        }

        private float yaw() { return axis.equals("x") ? 0 : -90; }

        private void aim(float yaw) {
            client.player.setYRot(yaw);
            client.player.setXRot(0);
            var freeCamera = BetterThirdPerson.getCameraManager().getCustomCamera();
            if (freeCamera != null) freeCamera.setCameraRotation(new Rotation(yaw, 0));
        }

        private SubLevel clientDeck() {
            var container = SubLevelContainer.getContainer(client.level);
            return deckId == null || container == null ? null : container.getSubLevel(deckId);
        }

        void frame() {
            if (!(clientDeck() instanceof ClientSubLevel current) || frames.size() >= 1600) return;
            Vec3 center = Vec3.atBottomCenterOf(current.getPlot().getCenterBlock());
            float partialTick = client.getTimer().getGameTimeDeltaPartialTick(true);
            var renderPose = current.renderPose(partialTick);
            Map<String, Object> row = camera.sample(client, deckId, renderPose.transformPosition(center.add(0, 1, 0)),
                    phase(inputTick), false);
            if (row.isEmpty()) return;
            row.put("inputTick", inputTick);
            row.put("entityTick", client.player.tickCount);
            row.put("carrierRelativeEye", vector(renderPose.transformPositionInverse(
                    Sable.HELPER.getEyePositionInterpolated(client.player, partialTick)).subtract(center)));
            row.put("carrierRelativeCamera", vector(renderPose.transformPositionInverse(
                    client.gameRenderer.getMainCamera().getPosition()).subtract(center)));
            row.put("riderBodyYaw", client.player.yBodyRot);
            row.put("riderHeadYaw", client.player.yHeadRot);
            row.put("trackingDeck", Sable.HELPER.getTrackingSubLevel(client.player) == current);
            frames.add(row);
        }

        private String phase(int tick) {
            if (flightOnly) return tick <= JUMP_AT ? "FAST_STANDING" : "FAST_FLIGHT_DOUBLE_TAP";
            if (landingOnly) {
                if (tick <= JUMP_AT - 6) return "FAST_STANDING";
                return tick <= JUMP_AT ? (sprintJump ? "FAST_SPRINT_APPROACH" : "FAST_STANDING") : "FAST_JUMP_AND_LANDING";
            }
            if (tick <= WALK_AT) return "FAST_STANDING";
            if (tick <= RUN_AT) return "FAST_WALKING";
            if (tick <= JUMP_AT) return "FAST_RUNNING_LOOK";
            if (tick <= FLY_AT) return "FAST_JUMP";
            return "FAST_CREATIVE_FLIGHT";
        }

        private Map<String, Object> sample(Player player, SubLevel deck, int tick) {
            Map<String, Object> row = new LinkedHashMap<>();
            var pose = deck.logicalPose();
            var collision = ((EntityMovementExtension) player).sable$getCollisionInfo();
            row.put("inputTick", tick);
            row.put("phase", phase(tick));
            row.put("position", vector(player.position()));
            row.put("previous", List.of(player.xo, player.yo, player.zo));
            row.put("carrierPosition", List.of(pose.position().x(), pose.position().y(), pose.position().z()));
            row.put("relativeToDeck", vector(pose.transformPositionInverse(player.position())
                    .subtract(Vec3.atBottomCenterOf(deck.getPlot().getCenterBlock()).add(0, 1, 0))));
            row.put("motion", vector(player.getDeltaMovement()));
            row.put("inheritedMotion", collision == null || collision.inheritedMotion == null
                    ? List.of() : vector(collision.inheritedMotion));
            row.put("previouslyTrackedDeck", collision != null && collision.preTrackingSubLevel == deck);
            row.put("trackingDeck", Sable.HELPER.getTrackingSubLevel(player) == deck);
            row.put("grounded", player.onGround());
            row.put("sprinting", player.isSprinting());
            row.put("flying", player.getAbilities().flying);
            row.put("passenger", player.isPassenger());
            row.put("nanoTime", System.nanoTime());
            return row;
        }

        private static final class JumpWindow {
            int airborne = -1;
            volatile int landed = -1;
        }

        private void observeJump(Player player, SubLevel deck, int tick, JumpWindow window, Map<String, Object> row) {
            if (tick < JUMP_AT - 2) return;
            observeEquipment(player, tick, row);
            if (player.getAbilities().flying) throw new IllegalStateException("Creative flight during the single-tap jump");
            double height = deck.logicalPose().transformPositionInverse(player.position()).y - deckFloor.y;
            if (tick >= JUMP_AT && window.airborne < 0 && !player.onGround() && height > .15) window.airborne = tick;
            if (window.airborne >= 0 && window.landed < 0 && player.onGround() && Math.abs(height) < .15
                    && Sable.HELPER.getTrackingSubLevel(player) == deck) window.landed = tick;
        }

        private void observeEquipment(Player player, int tick, Map<String, Object> row) {
            row.put("slippersEquipment", BunnySlippersEquipment.sample(player));
            GameType mode = player instanceof ServerPlayer sp ? sp.gameMode.getGameModeForPlayer() : client.gameMode.getPlayerMode();
            row.put("gameMode", mode.name());
            if (mode != requestedMode || !BunnySlippersEquipment.ready(player, slippers) || player.isPassenger() || Boolean.TRUE.equals(row.get("ragdolled"))) {
                throw new IllegalStateException("Game mode, loadout, passenger or ragdoll state invalidated the action at "
                        + tick + " on " + (player.level().isClientSide() ? "client" : "server"));
            }
        }

        private static List<Double> vector(Vec3 value) { return List.of(value.x, value.y, value.z); }

        void fail(Exception error) {
            LogUtils.getLogger().error("Disposable Sable airship fixture failed", error);
            failure = error.toString();
            stopping = true;
        }

        private void finish() throws Exception {
            try {
                camera.finish(client);
                client.options.keyUp.setDown(forwardWasDown);
                client.options.keyJump.setDown(jumpWasDown);
                client.options.keySprint.setDown(sprintWasDown);
                Path file = client.gameDirectory.toPath().resolve("emfattachments-sable-airship-" + view + "-" + axis + ".json");
                try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    var report = new LinkedHashMap<String, Object>(Map.of("view", view, "axis", axis, "sprintJump", sprintJump, "walkOff", walkOff,
                            "targetBlocksPerMinute", SPEED * 60, "client", clientRows, "server", serverRows,
                            "frames", frames, "failure", failure));
                    if (checkEquipment) {
                        report.put("jumpLoadout", slippers ? "artifacts:bunny_hoppers" : "bare-reference");
                        report.put("deckWidth", 2 * deckRadius + 1);
                        report.put("flightOnly", flightOnly);
                        report.put("requestedGameMode", requestedMode.name());
                    }
                    if (landingOnly) {
                        report.put("clientAirborneAndLanded", List.of(clientJump.airborne, clientJump.landed));
                        report.put("serverAirborneAndLanded", List.of(serverJump.airborne, serverJump.landed));
                    }
                    new GsonBuilder().setPrettyPrinting().create().toJson(report, out);
                }
                LogUtils.getLogger().info("Finished disposable Sable airship motion recording {} {}", view, axis);
            } finally {
                active = null;
            }
        }
    }

    private record Snapshot(Vec3 position, Vec3 motion, GameType gameType, float yaw, float pitch,
                            float fallDistance, boolean flying, boolean gravity) {
        Snapshot(ServerPlayer player) {
            this(player.position(), player.getDeltaMovement(), player.gameMode.getGameModeForPlayer(),
                    player.getYRot(), player.getXRot(), player.fallDistance, player.getAbilities().flying, !player.isNoGravity());
        }

        void restore(ServerPlayer player) {
            player.setGameMode(gameType);
            player.getAbilities().flying = flying;
            player.onUpdateAbilities();
            player.setNoGravity(!gravity);
            player.teleportTo(player.serverLevel(), position.x, position.y, position.z, yaw, pitch);
            player.setDeltaMovement(motion);
            player.fallDistance = fallDistance;
        }
    }
}
