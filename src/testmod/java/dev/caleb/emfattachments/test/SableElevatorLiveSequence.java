package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.socol.betterthirdperson.BetterThirdPerson;
import io.socol.betterthirdperson.api.util.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.ModList;

/** Observes powered cabin and rider motion in an offline, disposable single-player save. */
public final class SableElevatorLiveSequence {
    private static final UUID OFFLINE_PLAYER = UUID.nameUUIDFromBytes("OfflinePlayer:AttachmentTest".getBytes(StandardCharsets.UTF_8));
    private static final List<String> CAMERA_MODES = List.of("first", "rear", "front", "left", "right");
    private static volatile Session active;

    private SableElevatorLiveSequence() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(SableElevatorLiveSequence::clientTick);
        NeoForge.EVENT_BUS.addListener(SableElevatorLiveSequence::serverTick);
        NeoForge.EVENT_BUS.addListener(SableElevatorLiveSequence::beforeFrame);
        NeoForge.EVENT_BUS.addListener(SableElevatorLiveSequence::frame);
    }

    public static boolean observes(Player rider, AbstractContraptionEntity cabin) {
        Session session = active;
        Scene scene = session == null ? null : session.scene;
        ServerSample sample = scene == null ? null : scene.lastSample;
        return sample != null && rider == session.player && cabin.getId() == sample.cabinId;
    }

    public static void correction(Player rider, double before, boolean accepted) {
        Session session = active;
        if (session != null && session.scene != null) {
            var scene = session.scene;
            scene.corrections.add(new Correction(scene.stage.name(), rider.tickCount, scene.rideDirection,
                    rider instanceof LocalPlayer local && local.input.jumping, rider.getAbilities().flying,
                    before, rider.getY(), accepted));
        }
    }

    private static boolean disposable(Minecraft client) {
        return client.player != null && client.level != null && client.getSingleplayerServer() != null
                && !client.getSingleplayerServer().isPublished() && client.getUser().getType() == User.Type.LEGACY
                && OFFLINE_PLAYER.equals(client.getUser().getProfileId()) && OFFLINE_PLAYER.equals(client.player.getUUID())
                && client.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"));
    }

    private static void clientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (active != null) {
            active.clientTick();
            return;
        }
        if (!disposable(client) || client.screen != null || client.getOverlay() != null) return;
        Path trigger = client.gameDirectory.toPath().resolve("emfattachments-sable-elevator-live.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            List<String> args = List.of(Files.readString(trigger).strip().split("\\s+"));
            Files.delete(trigger);
            String camera = args.stream().filter(CAMERA_MODES::contains).findFirst()
                    .orElse(System.getProperty("emfattachments.elevatorCamera", "first"));
            List<Motion> motions = java.util.Arrays.stream(Motion.values()).filter(m -> args.contains(m.label)).toList();
            boolean movingBoard = args.contains("moving-board");
            List<Motion> plan = motions.isEmpty() && !movingBoard ? List.of(Motion.values()) : motions;
            if (movingBoard) {
                if (motions.isEmpty()) plan = List.of(Motion.X, Motion.Z);
                if (plan.stream().noneMatch(m -> m.x != 0 || m.z != 0) || args.contains("direct")) {
                    throw new IllegalArgumentException("Moving boarding requires a horizontal route and the landing");
                }
            }
            active = new Session(client, plan, camera, args.contains("controls"), args.contains("direct"),
                    args.contains("release"), movingBoard, args.contains("ride-actions"), args.contains("air-in-ride"));
            active.beginScene();
        } catch (Exception error) {
            LogUtils.getLogger().error("Could not start the live elevator fixture", error);
        }
    }

    private static void serverTick(ServerTickEvent.Pre event) {
        Session session = active;
        if (session == null || event.getServer() != session.server) return;
        Scene scene = session.scene;
        ServerPlayer player = session.server.getPlayerList().getPlayer(OFFLINE_PLAYER);
        if (scene == null || player == null) return;
        try {
            scene.serverTick(player);
        } catch (Exception error) {
            scene.failure = error.toString();
            LogUtils.getLogger().error("Live elevator fixture failed", error);
        }
    }

    private static void beforeFrame(RenderFrameEvent.Pre event) {
        Session session = active;
        if (session != null && session.scene != null && !session.scene.restoreRequested) {
            session.camera.applyOrbit(session.client);
        }
    }

    private static void frame(RenderFrameEvent.Post event) {
        Session session = active;
        if (session != null) session.frame();
    }

    private enum Stage {
        BUILD, ASSEMBLE, WAIT_FOR_CLIENT, CARRIER_RUNUP, BOARDING, SETTLE, MOVING_CARRIER,
        RIDING, RETURN_CONTROL, RIDER_ACTIONS, FINISHED
    }

    private enum Motion {
        WORLD("world", false, 0, 0), STILL("still", true, 0, 0), X("x", true, 1, 0), Z("z", true, 0, 1),
        DIAGONAL("diagonal", true, Math.sqrt(0.5), Math.sqrt(0.5));

        final String label;
        final boolean sable;
        final double x;
        final double z;

        Motion(String label, boolean sable, double x, double z) {
            this.label = label;
            this.sable = sable;
            this.x = x;
            this.z = z;
        }
    }

    private static final class Session {
        final Minecraft client;
        final LocalPlayer player;
        final IntegratedServer server;
        final List<Motion> motions;
        final String cameraMode;
        final boolean controls;
        final boolean direct;
        final boolean release;
        final boolean movingBoard;
        final boolean rideActions;
        final boolean airInRide;
        final List<Map<String, Object>> results = new ArrayList<>();
        volatile Scene scene;
        int index;
        SableElevatorCameraProbe camera;
        SableElevatorControlsProbe upInteraction;
        SableElevatorControlsProbe downInteraction;
        int restoreTicks;
        int lookTicks;
        int lookDirection;
        boolean forwardHeld;
        boolean jumpHeld;
        boolean sprintHeld;
        boolean facedCabin;

        Session(Minecraft client, List<Motion> motions, String cameraMode, boolean controls, boolean direct,
                boolean release, boolean movingBoard, boolean rideActions, boolean airInRide) {
            this.client = client;
            this.player = client.player;
            this.server = client.getSingleplayerServer();
            this.motions = motions;
            this.cameraMode = cameraMode;
            this.controls = controls;
            this.direct = direct;
            this.release = release;
            this.movingBoard = movingBoard;
            this.rideActions = rideActions;
            this.airInRide = airInRide;
        }

        void beginScene() {
            Motion motion = motions.get(index);
            boolean boardAtSpeed = movingBoard && (motion.x != 0 || motion.z != 0);
            camera = new SableElevatorCameraProbe(cameraMode);
            camera.begin(client, boardAtSpeed);
            if (controls) {
                upInteraction = new SableElevatorControlsProbe(1);
                downInteraction = new SableElevatorControlsProbe(-1);
            }
            forwardHeld = client.options.keyUp.isDown();
            jumpHeld = client.options.keyJump.isDown();
            sprintHeld = client.options.keySprint.isDown();
            lookTicks = 0;
            lookDirection = 0;
            facedCabin = false;
            scene = new Scene(motion, controls, direct, release, boardAtSpeed, rideActions, airInRide);
        }

        void clientTick() {
            Scene current = scene;
            if (current == null) return;
            try {
                camera.stopOrbitRequest();
                if (!disposable(client) || client.player != player || client.getSingleplayerServer() != server) {
                    current.failure = "Disposable level or player changed";
                }
                if (current.restored) {
                    if (restoreTicks++ == 0) {
                        var extension = (EntityMovementExtension) player;
                        var living = (LivingEntityMovementExtension) player;
                        extension.sable$setTrackingSubLevel(null);
                        living.sable$getInheritedVelocity().zero();
                        player.setDeltaMovement(Vec3.ZERO);
                    }
                    if (restoreTicks < 40) return;
                    if (!ExpandedRagdollRecovery.clientRestored(player) && restoreTicks < 100) return;
                    restoreTicks = 0;
                    results.add(current.result(camera));
                    if (++index < motions.size()) beginScene();
                    else finish();
                    return;
                }
                if (current.restoreRequested) return;
                if (current.failure != null || current.stage == Stage.FINISHED) {
                    client.options.keyUp.setDown(forwardHeld);
                    if (rideActions || airInRide) client.options.keyJump.setDown(jumpHeld);
                    if (rideActions) {
                        client.options.keySprint.setDown(sprintHeld);
                    }
                    camera.finish(client);
                    current.restoreRequested = true;
                    return;
                }
                AbstractContraptionEntity cabin = current.clientCabin(client);
                if (cabin == null) return;
                Vec3 floor = current.clientFloor(cabin);
                if (floor == null) return;
                current.clientReady = true;
                current.takeClientSample(player, cabin, floor);
                if (current.stage == Stage.SETTLE || current.stage == Stage.CARRIER_RUNUP) {
                    camera.ensureAtStart(client);
                    current.cameraReady = camera.matches(client);
                }
                steerBoarding(current);
                if (airInRide) steerAirInRide(current, cabin, floor);
                if (current.stage == Stage.RIDER_ACTIONS) steerRiderActions(current);
                boolean walkingOff = release && steerRelease(current, cabin, floor);
                if (!walkingOff && current.stage == Stage.RIDING && current.travelingVertically
                        && camera.matches(client)) {
                    if (lookDirection != current.rideDirection) {
                        lookDirection = current.rideDirection;
                        lookTicks = 0;
                        camera.resetLook();
                    }
                    camera.lookAround(++lookTicks);
                }
                if (controls && camera.matches(client)) {
                    if ((current.stage == Stage.SETTLE || current.stage == Stage.MOVING_CARRIER)
                            && !upInteraction.clicked()) {
                        boolean fullSpeed = current.stage == Stage.MOVING_CARRIER && current.elapsed >= 24;
                        current.upControl = upInteraction.sample(client, cabin, fullSpeed);
                        if (upInteraction.clicked()) current.upControlTarget = upInteraction.requestedFloor();
                    }
                    if (current.stage == Stage.RETURN_CONTROL && !downInteraction.clicked()) {
                        current.downControl = downInteraction.sample(client, cabin, true);
                        if (downInteraction.clicked()) current.downControlTarget = downInteraction.requestedFloor();
                    }
                }
            } catch (Exception error) {
                current.failure = error.toString();
                LogUtils.getLogger().error("Live elevator client observation failed", error);
            }
        }

        private void steerAirInRide(Scene current, AbstractContraptionEntity cabin, Vec3 floor) {
            if (current.stage != Stage.RIDING || !current.travelingVertically) {
                client.options.keyJump.setDown(false);
                return;
            }
            int tick = player.tickCount;
            boolean withinFloorContact = Math.abs(player.getY() - floor.y) < 0.2 && Scene.overCabinFloor(player, cabin);
            if (current.rideDirection > 0) {
                if (current.rideJumpTick < 0 && withinFloorContact) current.rideJumpTick = tick;
                client.options.keyJump.setDown(tick == current.rideJumpTick);
                return;
            }
            if (current.rideFlightTick < 0 && withinFloorContact) current.rideFlightTick = tick;
            client.options.keyJump.setDown(current.rideFlightTick >= 0 &&
                    (tick == current.rideFlightTick || tick >= current.rideFlightTick + 5 && tick < current.rideFlightTick + 15));
        }

        private void steerRiderActions(Scene current) {
            int tick = ++current.riderActionTick;
            if (tick == 1) {
                // Run along the open northern half of the cabin, away from the controls.
                player.setYRot(180);
                player.setXRot(0);
                var movementCamera = BetterThirdPerson.getCameraManager().getCustomCamera();
                if (movementCamera != null) movementCamera.setCameraRotation(new Rotation(180, 0));
                camera.resetLook();
            }
            boolean running = tick >= 3 && tick < 13;
            client.options.keyUp.setDown(running);
            client.options.keySprint.setDown(running);
            if (running) camera.lookAround(tick - 2);
            client.options.keyJump.setDown(tick == 26 || tick == 55 || (tick >= 60 && tick < 72));
        }

        private boolean steerRelease(Scene current, AbstractContraptionEntity cabin, Vec3 floor) {
            if (current.stage != Stage.RIDING || current.rideDirection >= 0 || current.elapsed < 12) return false;
            boolean overFloor = Scene.overCabinFloor(player, cabin);
            if (current.releaseInputTick < 0) {
                if (!current.travelingVertically || !overFloor || !camera.matches(client)) return false;
                current.releaseInputTick = player.tickCount;
            }
            current.releaseMinimumY = Math.min(current.releaseMinimumY, player.getY() - floor.y);
            if (!overFloor && current.releaseExitTick < 0) current.releaseExitTick = player.tickCount;
            if (current.releaseExitTick < 0 && player.tickCount - current.releaseInputTick <= 55) {
                // The controls are on the south side. Walk toward the open north edge.
                player.setYRot(180);
                player.setXRot(0);
                var freeCamera = BetterThirdPerson.getCameraManager().getCustomCamera();
                if (freeCamera != null) freeCamera.setCameraRotation(new Rotation(180, 0));
                client.options.keyUp.setDown(true);
            }
            return true;
        }

        private void steerBoarding(Scene current) {
            if (current.stage != Stage.BOARDING) {
                client.options.keyUp.setDown(false);
                return;
            }
            if (current.movingBoard) {
                camera.ensureAtStart(client);
                if (!camera.matches(client)) return;
            }
            if (!current.movingBoard || !facedCabin) {
                player.setYRot(-90);
                player.setXRot(0);
                var movementCamera = BetterThirdPerson.getCameraManager().getCustomCamera();
                if (movementCamera != null) movementCamera.setCameraRotation(new Rotation(-90, 0));
                facedCabin = true;
            }
            client.options.keyUp.setDown(current.walkwayTracked);
        }

        void frame() {
            Scene current = scene;
            if (current == null || current.failure != null || current.restoreRequested) return;
            try {
                var cabin = current.clientCabin(client);
                if (cabin == null) return;
                Vec3 floor = current.clientFloor(cabin);
                if (floor != null && current.cameraRows.size() < 3600) {
                    var sample = camera.sample(client, current.platformId, floor, current.phaseName(),
                            current.travelingVertically);
                    if (!sample.isEmpty()) {
                        float partial = client.getTimer().getGameTimeDeltaPartialTick(true);
                        Vec3 previousFloor = cabin.toGlobalVector(PoweredElevatorFixture.CABIN_FEET, 0, true);
                        Vec3 renderedFloor = previousFloor.lerp(cabin.toGlobalVector(PoweredElevatorFixture.CABIN_FEET, 1), partial);
                        if (Sable.HELPER.getContaining(cabin) instanceof ClientSubLevel carrier) {
                            renderedFloor = carrier.renderPose(partial).transformPosition(renderedFloor);
                        }
                        sample.put("riderRenderedYAboveFloor", Mth.lerp(partial, player.yo, player.getY()) - renderedFloor.y);
                        if (ModList.get().isLoaded("entity_model_features")) {
                            sample.put("freshAnimation", FreshAnimationProbe.sample(player));
                        }
                        current.cameraRows.add(sample);
                    }
                }
                if (floor != null && current.stage == Stage.RIDING && current.travelingVertically
                        && lookDirection == current.rideDirection && camera.matches(client)) {
                    camera.captureLook(client, current.motion.label, current.rideDirection > 0 ? "ASCENDING" : "DESCENDING");
                }
            } catch (Exception error) {
                current.failure = error.toString();
                LogUtils.getLogger().error("Live elevator camera observation failed", error);
            }
        }

        void finish() throws java.io.IOException {
            Path output = client.gameDirectory.toPath().resolve("emfattachments-sable-elevator-live-report.json");
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create()
                    .toJson(Map.of("cameraMode", cameraMode, "controlsMode", controls,
                            "intentionalReleaseMode", release, "scenarios", results)));
            LogUtils.getLogger().info("Live powered elevator report finished with {} scenarios", results.size());
            active = null;
        }
    }

    private static final class FreshAnimationProbe {
        private static final List<String> KEYS = List.of("var.pre_posy", "var.verti_drag", "var.vertical_speed",
                "var.fall_amp", "var.land", "var.t_land", "varb.falling", "varb.jumping", "var.frame_counter_prev");

        static Map<String, Object> sample(LocalPlayer rider) {
            var variables = ((traben.entity_model_features.utils.EMFEntity) rider).emf$getVariableMap();
            var result = new LinkedHashMap<String, Object>();
            for (String key : KEYS) {
                Float value = variables.get(key);
                if (value == null) continue;
                if (key.startsWith("varb.")) result.put(key, value > 0);
                else if (Float.isFinite(value)) result.put(key, value);
            }
            return result;
        }
    }

    private static final class Scene {
        private static final int LAST_RIDER_ACTION_TICK = 82;
        final Motion motion;
        final boolean controls;
        final boolean boardFromWalkway;
        final boolean release;
        final boolean movingBoard;
        final boolean rideActions;
        final boolean airInRide;
        final List<ServerSample> serverRows = new ArrayList<>();
        final List<ClientSample> clientRows = new ArrayList<>();
        final List<Correction> corrections = new ArrayList<>();
        final List<Map<String, Object>> cameraRows = new ArrayList<>();
        volatile ServerSample lastSample;
        volatile ClientSample lastClientSample;
        volatile Stage stage = Stage.BUILD;
        volatile String failure;
        volatile UUID platformId;
        volatile boolean clientReady, cameraReady, restoreRequested, restored, walkwayTracked, clientGrounded;
        volatile int elapsed, upControlTarget = Integer.MIN_VALUE, downControlTarget = Integer.MIN_VALUE;
        volatile int riderActionTick;
        PoweredElevatorFixture rig;
        Snapshot original;
        Map<String, Object> upControl = Map.of();
        Map<String, Object> downControl = Map.of();
        int gameTick;
        int rideTarget;
        int firstTarget;
        int stableTicks;
        int rampTicks;
        int arrivedTicks;
        Vec3 previousSettledOffset;
        boolean reachedUpper, reachedLower, releasedRagdoll, groundedWhileSettling;
        volatile int rideDirection;
        volatile boolean travelingVertically;
        double previousCabinY;
        int releaseInputTick = -1, releaseExitTick = -1;
        double releaseMinimumY;
        boolean boarded;
        int rideJumpTick = -1;
        int rideFlightTick = -1;

        Scene(Motion motion, boolean controls, boolean direct, boolean release, boolean movingBoard,
              boolean rideActions, boolean airInRide) {
            this.motion = motion;
            this.controls = controls;
            this.boardFromWalkway = motion.sable && !direct;
            this.release = release;
            this.movingBoard = movingBoard;
            this.rideActions = rideActions;
            this.airInRide = airInRide;
        }

        void serverTick(ServerPlayer player) {
            if (restoreRequested) {
                if (!restored) {
                    try {
                        releasedRagdoll = ExpandedRagdollRecovery.release(player);
                        if (rig != null) rig.close();
                    } finally {
                        if (original != null) original.restore(player);
                        restored = true;
                    }
                }
                return;
            }
            if (failure != null || stage == Stage.FINISHED) return;
            elapsed++;
            gameTick++;
            if (gameTick > 660) throw new IllegalStateException("Timed out at " + stage + " after " + elapsed + " ticks");
            switch (stage) {
                case BUILD -> build(player);
                case ASSEMBLE -> {
                    rig.drive(0, 0);
                    if (rig.assembleWhenPowered() && elapsed >= 30) spawnRider(player);
                    else if (elapsed > 100) throw new IllegalStateException("Cannot assemble: pulley RPM "
                            + rig.rpm() + ", floors " + rig.floors());
                }
                case WAIT_FOR_CLIENT -> {
                    rig.drive(0, 0);
                    if (clientReady && elapsed > 12) {
                        player.setNoGravity(false);
                        stage(movingBoard ? Stage.CARRIER_RUNUP : boardFromWalkway ? Stage.BOARDING : Stage.SETTLE);
                    }
                }
                case CARRIER_RUNUP -> {
                    var rider = lastClientSample;
                    if (rider != null && rider.trackingPlatform && rider.grounded && cameraReady) walkwayTracked = true;
                    if (walkwayTracked) rampTicks++;
                    drive(Math.min(1.0, rampTicks / 12.0));
                    if (rampTicks >= 26) stage(Stage.BOARDING);
                    else if (elapsed > 100) throw new IllegalStateException("The rider did not establish moving-deck tracking");
                }
                case BOARDING -> {
                    drive(movingBoard ? 1 : 0);
                    walkwayTracked |= tracking(player) && player.onGround();
                    var rider = lastClientSample;
                    boolean crossed = movingBoard ? rider != null && rider.overCabinFloor && rider.error.x >= -0.45
                            && Math.abs(rider.error.y) < 0.2 && !rider.passenger
                            : player.getX() >= rig.floor().x - 0.45;
                    if (walkwayTracked && crossed) {
                        boarded = true;
                        stage(Stage.SETTLE);
                    } else if (elapsed > 140) throw new IllegalStateException("Player could not board from the Sable deck (tracked="
                            + walkwayTracked + ", floor=" + rig.floor() + ", rider=" + player.position() + ")");
                }
                case SETTLE -> {
                    drive(movingBoard ? 1 : 0);
                    var rider = lastClientSample;
                    Vec3 error = movingBoard && rider != null ? rider.error.vector() : player.position().subtract(rig.floor());
                    boolean nearFloor = error.horizontalDistance() < (movingBoard ? 1.25 : 0.9) && Math.abs(error.y) < 0.15
                            && (!movingBoard || rider != null && rider.overCabinFloor && !rider.ragdolled)
                            && !ExpandedRagdollRecovery.active(player) && !player.isPassenger() && !player.isNoGravity();
                    boolean stable = nearFloor && previousSettledOffset != null && error.distanceTo(previousSettledOffset) < 0.06;
                    stableTicks = stable ? stableTicks + 1 : 0;
                    previousSettledOffset = error;
                    groundedWhileSettling |= nearFloor && (player.onGround() || clientGrounded);
                    boolean feetTouchFloor = groundedWhileSettling || Math.abs(error.y) < 0.002;
                    if (elapsed >= 40 && stableTicks >= 6 && feetTouchFloor && cameraReady) {
                        stage(Stage.MOVING_CARRIER);
                    } else if (elapsed > 140) {
                        throw new IllegalStateException("Rider or requested camera did not settle on the cabin: "
                                + error + ", camera ready=" + cameraReady);
                    }
                }
                case MOVING_CARRIER -> {
                    drive(movingBoard ? 1.0 : Math.min(1.0, elapsed / 12.0));
                    if (!controls && elapsed == 24) rig.callFloor(PoweredElevatorFixture.TOP_FLOOR);
                    int target = rig.target();
                    if (target == PoweredElevatorFixture.TOP_FLOOR && acceptedControl(target, upControlTarget)) {
                        firstTarget = target;
                        beginRide(target, 1);
                    } else if (elapsed > 150) throw new IllegalStateException("Native upward floor request was not accepted");
                }
                case RIDING -> {
                    drive(1);
                    double cabinY = rig.cabin().getY();
                    travelingVertically = (cabinY - previousCabinY) * rideDirection > 0.001;
                    previousCabinY = cabinY;
                    boolean atFloor = Math.abs(cabinY - rig.origin.getY() - rideTarget) <= 0.12;
                    arrivedTicks = atFloor ? arrivedTicks + 1 : 0;
                    if (arrivedTicks >= 24 && rideDirection > 0) {
                        reachedUpper = true;
                        stage(Stage.RETURN_CONTROL);
                    } else if (arrivedTicks >= 24) {
                        reachedLower = true;
                        stage(rideActions ? Stage.RIDER_ACTIONS : Stage.FINISHED);
                    } else if (elapsed >= 150) {
                        throw new IllegalStateException("Cabin did not reach requested floor " + rideTarget);
                    }
                }
                case RETURN_CONTROL -> {
                    drive(1);
                    int target = PoweredElevatorFixture.BOTTOM_FLOOR;
                    if (!controls && elapsed == 1) rig.callFloor(target);
                    if (rig.target() == target && acceptedControl(target, downControlTarget)) {
                        beginRide(target, -1);
                    } else if (elapsed > 150) throw new IllegalStateException("Native downward floor request was not accepted");
                }
                case RIDER_ACTIONS -> {
                    drive(1);
                    if (riderActionTick >= LAST_RIDER_ACTION_TICK) stage(Stage.FINISHED);
                    else if (elapsed > 130) throw new IllegalStateException("Rider actions did not finish");
                }
                case FINISHED -> { }
            }
            if (rig.cabin() != null) takeServerSample(player);
        }

        private boolean acceptedControl(int floor, int requestedY) {
            if (!controls) return true;
            int plotY = rig.origin.getY() + floor;
            return requestedY == plotY && Boolean.TRUE.equals(
                    SableElevatorControlsProbe.packetObservation(rig.cabin().getId(), plotY).get("accepted"));
        }

        private void beginRide(int target, int direction) {
            rideTarget = target;
            rideDirection = direction;
            arrivedTicks = 0;
            previousCabinY = rig.cabin().getY();
            stage(Stage.RIDING);
        }

        private void build(ServerPlayer player) {
            original = new Snapshot(player);
            int x = player.getBlockX() + 12;
            int z = player.getBlockZ() + 12;
            int y = player.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 22;
            rig = new PoweredElevatorFixture(player.serverLevel(), new BlockPos(x, y, z), motion.sable);
            rig.build();
            if (rig.platform != null) platformId = rig.platform.getUniqueId();
            stage(Stage.ASSEMBLE);
        }

        private void spawnRider(ServerPlayer player) {
            Vec3 feet = boardFromWalkway ? rig.boardingPosition() : rig.floor().add(0, 0.03, 0);
            player.setGameMode(GameType.CREATIVE);
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            player.inventoryMenu.broadcastChanges();
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            player.setNoGravity(true);
            player.teleportTo(player.serverLevel(), feet.x, feet.y, feet.z, boardFromWalkway ? -90 : 0, 15);
            ((LivingEntityMovementExtension) player).sable$getInheritedVelocity().zero();
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0;
            stage(Stage.WAIT_FOR_CLIENT);
        }

        private void drive(double scale) {
            double speed = PoweredElevatorFixture.SPEED_PER_SECOND * scale;
            rig.drive(speed * motion.x, speed * motion.z);
        }

        private void stage(Stage next) {
            elapsed = 0;
            travelingVertically = false;
            stage = next;
        }

        String phaseName() {
            String phase = switch (stage) {
                case RIDING -> rideDirection > 0 ? "ASCENDING" : "DESCENDING";
                case RIDER_ACTIONS -> riderActionTick < 26 ? "RUN_LOOK" : riderActionTick < 55 ? "JUMP" : "FLY";
                default -> stage.name();
            };
            return motion.label + ":" + phase;
        }

        private void takeServerSample(ServerPlayer player) {
            Vec3 floor = rig.floor();
            Vec3 error = player.position().subtract(floor);
            ServerSample row = new ServerSample(stage.name(), gameTick, System.nanoTime(), rig.cabin().getId(),
                    rig.rpm(), rig.cabin().getY(), Numbers.of(rig.carrierPosition()), Numbers.of(floor), Numbers.of(error),
                    player.onGround(), tracking(player), rig.target(), rideDirection, ExpandedRagdollRecovery.active(player),
                    player.isPassenger(), overCabinFloor(player, rig.cabin()));
            lastSample = row;
            serverRows.add(row);
        }

        private void takeClientSample(LocalPlayer rider, AbstractContraptionEntity cabin, Vec3 floor) {
            clientGrounded = rider.onGround();
            var carrier = Sable.HELPER.getContaining(cabin);
            Vec3 position = rider.position();
            Vec3 cabinPoint = carrier == null ? position : carrier.logicalPose().transformPositionInverse(position);
            Vec3 projectedPrevious = carrier == null ? position : carrier.lastPose().transformPosition(cabinPoint);
            var collision = ((EntityMovementExtension) rider).sable$getCollisionInfo();
            var storedVelocity = ((LivingEntityMovementExtension) rider).sable$getInheritedVelocity();
            var sample = new ClientSample(stage.name(), rider.tickCount, System.nanoTime(), riderActionTick,
                    Numbers.of(position), Numbers.of(position.subtract(floor)), Numbers.of(cabin.toLocalVector(cabinPoint, 1)),
                    Numbers.of(position.subtract(projectedPrevious)),
                    collision == null || collision.inheritedMotion == null ? null : Numbers.of(collision.inheritedMotion),
                    new Numbers(storedVelocity.x, storedVelocity.y, storedVelocity.z),
                    collision != null && carrier != null && collision.preTrackingSubLevel == carrier,
                    rider.input.forwardImpulse, rider.input.jumping, rider.isSprinting(), rider.getAbilities().flying,
                    rider.getDeltaMovement().y, clientGrounded,
                    tracking(rider), rideDirection, !ExpandedRagdollRecovery.clientRestored(rider),
                    rider.isPassenger(), overCabinFloor(rider, cabin));
            lastClientSample = sample;
            clientRows.add(sample);
        }

        private static boolean overCabinFloor(Player rider, AbstractContraptionEntity cabin) {
            Vec3 position = rider.position();
            var platform = Sable.HELPER.getContaining(cabin);
            if (platform != null) position = platform.logicalPose().transformPositionInverse(position);
            Vec3 local = cabin.toLocalVector(position, 1);
            var floor = cabin.getContraption().getBlocks().get(BlockPos.containing(
                    local.x, PoweredElevatorFixture.CABIN_FEET.y - 0.01, local.z));
            return floor != null && floor.state().is(Blocks.SMOOTH_STONE);
        }

        private boolean tracking(Player rider) {
            var support = Sable.HELPER.getTrackingSubLevel(rider);
            return support != null && support.getUniqueId().equals(platformId);
        }

        private AbstractContraptionEntity clientCabin(Minecraft client) {
            var sample = lastSample;
            return sample != null && client.level != null
                    && client.level.getEntity(sample.cabinId) instanceof AbstractContraptionEntity cabin ? cabin : null;
        }

        private Vec3 clientFloor(AbstractContraptionEntity cabin) {
            Vec3 floor = cabin.toGlobalVector(PoweredElevatorFixture.CABIN_FEET, 1);
            if (!motion.sable) return floor;
            var platform = Sable.HELPER.getContaining(cabin);
            return platform != null && platform.getUniqueId().equals(platformId)
                    ? platform.logicalPose().transformPosition(floor) : null;
        }

        Map<String, Object> result(SableElevatorCameraProbe camera) {
            var ridden = serverRows.stream().filter(s -> s.stage.equals(Stage.RIDING.name())).toList();
            var report = new LinkedHashMap<String, Object>();
            report.put("scenario", motion.label);
            report.put("failure", failure == null ? "" : failure);
            report.put("controls", upControl);
            report.put("descentControls", downControl);
            if (controls && lastSample != null) {
                var upPacket = SableElevatorControlsProbe.packetObservation(lastSample.cabinId,
                        rig.origin.getY() + PoweredElevatorFixture.TOP_FLOOR);
                var downPacket = SableElevatorControlsProbe.packetObservation(lastSample.cabinId,
                        rig.origin.getY() + PoweredElevatorFixture.BOTTOM_FLOOR);
                report.put("nativeFloorPacket", upPacket);
                report.put("descentFloorPacket", downPacket);
                report.put("controlRoundTripPassed", reachedUpper && reachedLower
                        && Boolean.TRUE.equals(upPacket.get("accepted")) && Boolean.TRUE.equals(downPacket.get("accepted")));
            }
            report.put("ascentCamera", camera.travelEvidence(cameraRows, "ASCENDING"));
            report.put("descentCamera", camera.travelEvidence(cameraRows, "DESCENDING"));
            if (release) {
                report.put("intentionalRelease", Map.of("inputTick", releaseInputTick, "footprintExitTick", releaseExitTick,
                        "minimumRiderYRelativeToCabin", releaseMinimumY, "passed",
                        releaseInputTick >= 0 && releaseExitTick > releaseInputTick && releaseMinimumY < -0.5));
            }
            report.put("targetFloor", firstTarget);
            report.put("reachedFloor", reachedUpper);
            report.put("returnedToBottom", reachedLower);
            report.put("boarding", boardFromWalkway ? "walkway" : "direct-rider-placement");
            if (movingBoard) {
                var boarding = serverRows.stream().filter(s -> s.stage.equals(Stage.BOARDING.name())).toList();
                var carrierSpeed = boarding.size() < 2 ? 0.0 : horizontalSpeed(boarding.getFirst(), boarding.getLast());
                var walking = clientRows.stream().filter(s -> s.stage.equals(Stage.BOARDING.name())).toList();
                var advance = walking.size() < 2 ? 0.0 : walking.getLast().error.x - walking.getFirst().error.x;
                report.put("movingBoarding", Map.of("crossed", boarded, "horizontalBlocksPerMinute", carrierSpeed,
                        "clientAdvanceTowardCabin", advance, "clientSamples", walking.size(),
                        "forwardInputSamples", walking.stream().filter(sample -> sample.forwardInput > 0).count(),
                        "clientRagdollSamples", walking.stream().filter(ClientSample::ragdolled).count()));
            }
            if (rideActions) {
                var actions = clientRows.stream().filter(s -> s.stage.equals(Stage.RIDER_ACTIONS.name())).toList();
                report.put("riderActions", Map.of(
                        "clientSamples", actions.size(),
                        "runSamples", actions.stream().filter(s -> s.actionTick < 26 && s.sprinting && s.forwardInput > 0).count(),
                        "jumpSamples", actions.stream().filter(s -> s.actionTick >= 26 && s.actionTick < 55
                                && !s.flying && s.verticalMotion > 0.1).count(),
                        "flySamples", actions.stream().filter(s -> s.actionTick >= 55 && s.flying).count(),
                        "clientRagdollSamples", actions.stream().filter(ClientSample::ragdolled).count(),
                        "clientOutsideCabinFootprintSamples", actions.stream().filter(s -> !s.overCabinFloor).count()));
                report.put("riderActionCamera", Map.of("runLook", actionCamera("RUN_LOOK"),
                        "jump", actionCamera("JUMP"), "fly", actionCamera("FLY")));
            }
            if (airInRide) {
                var flight = clientRows.stream().filter(s -> s.stage.equals(Stage.RIDING.name()) && s.flying).toList();
                report.put("airInRide", Map.of("ascentJumpInputTick", rideJumpTick, "descentFlightInputTick", rideFlightTick,
                        "nativeFlightSamples", flight.size(), "flightFloorCorrections",
                        corrections.stream().filter(value -> value.flying && value.afterY != value.beforeY).count()));
            }
            report.put("walkwayTrackedBeforeBoarding", walkwayTracked);
            report.put("nativeGroundFlagWhileSettling", groundedWhileSettling);
            report.put("stableFeetTicksBeforeCarrierMotion", stableTicks);
            report.put("releasedRiderRagdollDuringCleanup", releasedRagdoll);
            report.put("rideTicks", ridden.size());
            if (!ridden.isEmpty()) {
                ServerSample start = ridden.getFirst();
                ServerSample end = ridden.getLast();
                double distance = Math.hypot(end.carrier.x - start.carrier.x, end.carrier.z - start.carrier.z);
                report.put("nominalHorizontalBlocksPerMinute", horizontalSpeed(start, end));
                report.put("wallHorizontalBlocksPerMinute", distance * 60e9 / Math.max(1, end.nanoTime - start.nanoTime));
                report.put("serverMaximumRiderHorizontalError", ridden.stream().mapToDouble(s -> s.error.horizontal()).max().orElseThrow());
                report.put("serverMaximumRiderVerticalError", ridden.stream().mapToDouble(s -> Math.abs(s.error.y)).max().orElseThrow());
                report.put("serverPassengerTicks", ridden.stream().filter(ServerSample::passenger).count());
                report.put("serverOutsideCabinFootprintTicks", ridden.stream().filter(s -> !s.overCabinFloor).count());
                report.put("upwardPlotTravel", plotTravel(ridden, 1));
                report.put("downwardPlotTravel", plotTravel(ridden, -1));
            }
            var clientRide = clientRows.stream().filter(s -> s.stage.equals(Stage.RIDING.name())).toList();
            if (!clientRide.isEmpty()) {
                report.put("clientMaximumRiderHorizontalError", clientRide.stream().mapToDouble(s -> s.error.horizontal()).max().orElseThrow());
                report.put("clientMaximumRiderVerticalError", clientRide.stream().mapToDouble(s -> Math.abs(s.error.y)).max().orElseThrow());
                report.put("clientPassengerTicks", clientRide.stream().filter(ClientSample::passenger).count());
                report.put("clientOutsideCabinFootprintTicks", clientRide.stream().filter(s -> !s.overCabinFloor).count());
            }
            report.put("server", serverRows);
            report.put("client", clientRows);
            report.put("antiClipping", corrections);
            report.put("camera", cameraRows);
            return report;
        }

        private Map<String, Object> actionCamera(String phase) {
            var frames = cameraRows.stream().filter(row -> (motion.label + ":" + phase).equals(row.get("stage"))).toList();
            return Map.of("renderedFrames", frames.size(),
                    "matchingFrames", frames.stream().filter(row -> Boolean.TRUE.equals(row.get("cameraMatchesRequest"))).count());
        }

        private static double plotTravel(List<ServerSample> rows, int direction) {
            var leg = rows.stream().filter(row -> row.direction == direction).toList();
            return leg.size() < 2 ? 0 : leg.getLast().cabinPlotY - leg.getFirst().cabinPlotY;
        }

        private static double horizontalSpeed(ServerSample start, ServerSample end) {
            return Math.hypot(end.carrier.x - start.carrier.x, end.carrier.z - start.carrier.z)
                    * 1200 / Math.max(1, end.tick - start.tick);
        }
    }

    private record Numbers(double x, double y, double z) {
        static Numbers of(Vec3 value) { return new Numbers(value.x, value.y, value.z); }
        Vec3 vector() { return new Vec3(x, y, z); }
        double horizontal() { return Math.hypot(x, z); }
    }

    private record ServerSample(String stage, int tick, long nanoTime, int cabinId, float rpm, double cabinPlotY,
                                Numbers carrier, Numbers floor, Numbers error, boolean grounded,
                                boolean trackingPlatform, int target, int direction, boolean ragdolled,
                                boolean passenger, boolean overCabinFloor) { }

    private record ClientSample(String stage, int tick, long nanoTime, int actionTick, Numbers position, Numbers error, Numbers cabinLocal,
                                Numbers carrierStep, Numbers nativeInheritedStep, Numbers storedNativeVelocity, boolean previouslyTrackingCarrier,
                                float forwardInput, boolean jumpInput, boolean sprinting, boolean flying, double verticalMotion,
                                boolean grounded, boolean trackingPlatform,
                                int direction, boolean ragdolled, boolean passenger, boolean overCabinFloor) { }

    private record Correction(String stage, int tick, int direction, boolean jumping, boolean flying,
                              double beforeY, double afterY, boolean accepted) { }

    private record Snapshot(Vec3 position, Vec3 movement, float yaw, float pitch, float fallDistance,
                            boolean flying, boolean noGravity, GameType gameType, ItemStack mainHand, ItemStack offHand) {
        Snapshot(ServerPlayer player) {
            this(player.position(), player.getDeltaMovement(), player.getYRot(), player.getXRot(), player.fallDistance,
                    player.getAbilities().flying, player.isNoGravity(), player.gameMode.getGameModeForPlayer(),
                    player.getMainHandItem().copy(), player.getOffhandItem().copy());
        }

        void restore(ServerPlayer player) {
            var extension = (EntityMovementExtension) player;
            extension.sable$setTrackingSubLevel(null);
            ((LivingEntityMovementExtension) player).sable$getInheritedVelocity().zero();
            player.setGameMode(gameType);
            player.setItemSlot(EquipmentSlot.MAINHAND, mainHand);
            player.setItemSlot(EquipmentSlot.OFFHAND, offHand);
            player.inventoryMenu.broadcastChanges();
            player.setNoGravity(noGravity);
            player.getAbilities().flying = flying;
            player.onUpdateAbilities();
            player.teleportTo(player.serverLevel(), position.x, position.y, position.z, yaw, pitch);
            player.setDeltaMovement(movement);
            player.fallDistance = fallDistance;
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
        }
    }
}
