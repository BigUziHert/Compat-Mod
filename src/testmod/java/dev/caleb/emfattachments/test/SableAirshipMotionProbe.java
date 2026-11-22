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
                || client.getOverlay() != null || client.screen != null) return;
        Path trigger = client.gameDirectory.toPath().resolve("emfattachments-sable-airship.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            List<String> args = List.of(Files.readString(trigger).strip().split("\\s+"));
            Files.delete(trigger);
            if (args.size() != 2 || !List.of("rear", "left").contains(args.get(0))
                    || !List.of("x", "z").contains(args.get(1))) {
                throw new IllegalArgumentException("Expected rear|left x|z");
            }
            active = new Session(client, args.get(0), args.get(1));
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

        Session(Minecraft client, String view, String axis) {
            this.client = client;
            this.server = client.getSingleplayerServer();
            this.view = view;
            this.axis = axis;
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
                    if (snapshot != null) snapshot.restore(player);
                    restored = true;
                }
                return;
            }
            if (deck == null) build(player);
            if (deck.isRemoved()) throw new IllegalStateException("The fixture deck unloaded");
            if (visible && player.isNoGravity()) player.setNoGravity(false);
            double speed = standing ? SPEED * Math.min(++rampTicks / 12.0, 1.0) : 0;
            var correction = pipeline.getLinearVelocity(deck, new Vector3d()).negate();
            correction.add(axis.equals("x") ? speed : 0,
                    0.25 + 3 * (holdHeight - deck.logicalPose().transformPosition(deckFloor).y),
                    axis.equals("z") ? speed : 0);
            pipeline.addLinearAndAngularVelocity(deck, correction, pipeline.getAngularVelocity(deck, new Vector3d()).negate());
            if (inputTick >= 0) {
                var row = sample(player, deck, inputTick);
                row.put("ragdolled", ExpandedRagdollRecovery.active(player));
                serverRows.add(row);
            }
        }

        private void build(ServerPlayer player) {
            if (player.isPassenger()) throw new IllegalStateException("The fixture requires an unmounted player");
            snapshot = new Snapshot(player);
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
            for (int dx = -9; dx <= 9; dx++) {
                for (int dz = -9; dz <= 9; dz++) {
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
            player.setGameMode(GameType.CREATIVE);
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
            boolean running = tick >= RUN_AT && tick < RUN_END;
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

        private static String phase(int tick) {
            if (tick <= WALK_AT) return "FAST_STANDING";
            if (tick <= RUN_AT) return "FAST_WALKING";
            if (tick <= JUMP_AT) return "FAST_RUNNING_LOOK";
            if (tick <= FLY_AT) return "FAST_JUMP";
            return "FAST_CREATIVE_FLIGHT";
        }

        private static Map<String, Object> sample(Player player, SubLevel deck, int tick) {
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
                    new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("view", view, "axis", axis,
                            "targetBlocksPerMinute", SPEED * 60, "client", clientRows, "server", serverRows,
                            "frames", frames, "failure", failure), out);
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
