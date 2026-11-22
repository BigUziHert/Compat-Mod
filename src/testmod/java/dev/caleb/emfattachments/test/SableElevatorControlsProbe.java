package dev.caleb.emfattachments.test;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionHandlerClient;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlock;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity.ControlsSlot;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsMovement.ElevatorFloorSelection;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorControlsHandler;
import dev.ryanhcode.sable.Sable;
import io.socol.betterthirdperson.BetterThirdPerson;
import io.socol.betterthirdperson.api.util.Rotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Drives the assembled cabin's native scroll selection and right-click floor request. */
public final class SableElevatorControlsProbe {
    private static final ControlsSlot SLOT = nativeSlot();
    private static final Map<FloorRequest, Map<String, Object>> PACKETS = new ConcurrentHashMap<>();
    private static volatile int watchedCabin = Integer.MIN_VALUE;

    private final int scrollDirection;
    private Integer requestedFloor;
    private boolean clicked;
    private Float beforeScrollZoom;
    private Float afterScrollZoom;
    private int attempts;
    private int aimMisses;

    SableElevatorControlsProbe(int scrollDirection) {
        this.scrollDirection = scrollDirection;
    }

    boolean clicked() {
        return clicked;
    }

    Integer requestedFloor() {
        return requestedFloor;
    }

    Map<String, Object> sample(Minecraft client, AbstractContraptionEntity elevator, boolean allowInput) {
        watchedCabin = elevator.getId();
        if (client.player == null || !(elevator.getContraption() instanceof ElevatorContraption contraption)
                || contraption.namesList == null || contraption.namesList.size() < 2) {
            return Map.of("status", "Waiting for synchronized floor list");
        }
        int destination = (scrollDirection > 0 ? contraption.namesList.getLast()
                : contraption.namesList.getFirst()).getFirst();
        if (clicked || contraption.clientYTarget == destination) {
            return Map.of("status", "Waiting for floor state to synchronize", "destinationFloor", destination,
                    "clientCurrentFloor", contraption.clientYTarget);
        }
        for (var actor : contraption.getActors()) {
            var block = actor.getLeft();
            if (!(block.state().getBlock() instanceof ContraptionControlsBlock)) continue;
            var context = actor.getRight();
            BlockPos localBlock = block.pos();
            Vec3 slotOffset = SLOT.getLocalOffset(client.level, localBlock, block.state());
            Vec3 localSlot = Vec3.atLowerCornerOf(localBlock).add(slotOffset);
            var ray = ContraptionHandlerClient.getRayInputs(client.player);
            var hit = ContraptionHandlerClient.rayTraceContraption(ray.getFirst(), ray.getSecond(), elevator);
            boolean hitSlot = hitsSlot(client, hit, localBlock, block.state());
            boolean shoulder = UnlockedCameraClient.isUnlockedCameraActive();
            if (!shoulder && !hitSlot) {
                // Better Third Person can restore the player's facing during the next tick.
                // Create's ordinary ray reads the player's current rotation, independent of the detached camera.
                aim(client, elevator, localSlot, ray.getFirst(), false);
                ray = ContraptionHandlerClient.getRayInputs(client.player);
                hit = ContraptionHandlerClient.rayTraceContraption(ray.getFirst(), ray.getSecond(), elevator);
                hitSlot = hitsSlot(client, hit, localBlock, block.state());
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("nativeSlot", vec(localSlot));
            result.put("nativeRayOrigin", vec(ray.getFirst()));
            result.put("nativeRayEnd", vec(ray.getSecond()));
            if (hit != null) result.put("nativeRayHit", vec(hit.getLocation()));
            result.put("nativeRayHitSlot", hitSlot);
            result.put("synchronizedFloors", contraption.namesList.size());
            result.put("clientCurrentFloor", contraption.clientYTarget);
            result.put("destinationFloor", destination);
            result.put("nativeCameraType", client.options.getCameraType().name());
            result.put("nativeShoulderActive", shoulder);
            if (hitSlot && allowInput) {
                aimMisses = 0;
                if (beforeScrollZoom == null) beforeScrollZoom = SableElevatorCameraProbe.targetDistance();
                scroll(client, scrollDirection);
                attempts++;
                afterScrollZoom = SableElevatorCameraProbe.targetDistance();
                if (context.temporaryData instanceof ElevatorFloorSelection selection) {
                    result.put("selectedFloor", selection.currentTargetY);
                    if (selection.currentTargetY == destination) {
                        use(client);
                        requestedFloor = destination;
                        clicked = true;
                        result.put("nativeUseSlotHit", vec(hit.getLocation()));
                    }
                }
                result.put("status", clicked ? "Native use processed while the ray hit the floor slot"
                        : "Scrolled the native floor slot");
            } else if (!hitSlot) {
                if (shoulder) aim(client, elevator, localSlot, ray.getFirst(), true);
                aimMisses++;
                result.put("status", "Aiming at the native floor slot");
            } else {
                aimMisses = 0;
                result.put("status", "Native floor slot selected before carrier reaches full speed");
            }
            result.put("scrollAttempts", attempts);
            if (beforeScrollZoom != null) result.put("zoomBeforeScroll", beforeScrollZoom);
            if (afterScrollZoom != null) result.put("zoomAfterScroll", afterScrollZoom);
            return result;
        }
        return Map.of("status", "Cabin has no native Contraption Controls actor");
    }

    /** Records the server's native packet path without changing its validation or destination. */
    public static void floorPacket(ServerPlayer player, int cabinId, int target, boolean accepted) {
        if (cabinId != watchedCabin) return;
        var result = new LinkedHashMap<String, Object>();
        result.put("received", true);
        result.put("accepted", accepted);
        result.put("target", target);
        var cabin = player.serverLevel().getEntity(cabinId);
        if (cabin != null) result.put("nativeCabinDistanceSquared", cabin.distanceToSqr(player));
        PACKETS.put(new FloorRequest(cabinId, target), result);
    }

    static Map<String, Object> packetObservation(int cabinId, int target) {
        return PACKETS.getOrDefault(new FloorRequest(cabinId, target), Map.of("received", false, "accepted", false));
    }

    private record FloorRequest(int cabinId, int target) {}

    private static boolean hitsSlot(Minecraft client, BlockHitResult hit, BlockPos block, BlockState state) {
        return hit != null && hit.getBlockPos().equals(block)
                && SLOT.testHit(client.level, block, state, hit.getLocation().subtract(Vec3.atLowerCornerOf(block)));
    }

    private void aim(Minecraft client, AbstractContraptionEntity elevator, Vec3 localSlot, Vec3 rayOrigin,
                     boolean unlocked) {
        var player = client.player;
        Vec3 target = elevator.toGlobalVector(localSlot, 1);
        var subLevel = Sable.HELPER.getContaining(elevator);
        if (subLevel != null) target = subLevel.logicalPose().transformPosition(target);
        float partialTick = client.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 eye = unlocked ? Sable.HELPER.getEyePositionInterpolated(player, partialTick) : player.getEyePosition();
        Vec3 direction = target.subtract(eye);
        if (direction.lengthSqr() < 1.0e-6) return;
        double yaw = Math.atan2(-direction.x, direction.z);
        double distance = direction.horizontalDistance();
        if (unlocked && distance > 1.0e-3) {
            // A shoulder ray does not start at the rider. Solve the sideways shift before turning it.
            Vec3 offset = rayOrigin.subtract(eye);
            double actualYaw = Math.toRadians(client.gameRenderer.getMainCamera().getYRot());
            double lateral = offset.x * Math.cos(actualYaw) + offset.z * Math.sin(actualYaw);
            double turn = Math.asin(Math.clamp(lateral / distance, -0.85, 0.85));
            yaw += turn;
            distance *= Math.cos(turn);
        }
        float yawDegrees = (float) Math.toDegrees(yaw);
        float pitch = (float) Math.toDegrees(Math.atan2(-direction.y, distance));
        if (unlocked && aimMisses >= 4) {
            // Try neighboring camera angles when the rendered camera and the physics pose are a frame apart.
            int step = ((aimMisses - 4) / 2) % 15;
            yawDegrees += (step % 5 - 2) * 4;
            pitch += (step / 5 - 1) * 4;
        }
        player.setYRot(yawDegrees);
        player.yRotO = yawDegrees;
        player.setXRot(pitch);
        player.xRotO = pitch;
        var freeCamera = BetterThirdPerson.getCameraManager().getCustomCamera();
        if (freeCamera != null) freeCamera.setCameraRotation(new Rotation(yawDegrees, pitch));
    }

    private static ControlsSlot nativeSlot() {
        try {
            Field field = ElevatorControlsHandler.class.getDeclaredField("slot");
            field.setAccessible(true);
            return (ControlsSlot) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not read the native elevator controls hitbox", failure);
        }
    }

    private static void scroll(Minecraft client, double delta) {
        try {
            Method callback = MouseHandler.class.getDeclaredMethod("onScroll", long.class, double.class, double.class);
            callback.setAccessible(true);
            callback.invoke(client.mouseHandler, client.getWindow().getWindow(), 0, delta);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not feed the vanilla mouse-wheel callback", failure);
        }
    }

    private static void use(Minecraft client) {
        try {
            Method callback = Minecraft.class.getDeclaredMethod("startUseItem");
            callback.setAccessible(true);
            callback.invoke(client);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not feed the vanilla use action", failure);
        }
    }

    private static List<Double> vec(Vec3 value) {
        return List.of(value.x, value.y, value.z);
    }
}
