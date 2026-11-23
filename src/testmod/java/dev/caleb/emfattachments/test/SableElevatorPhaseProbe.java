package dev.caleb.emfattachments.test;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Observes resolved endpoints on both sides of movement, Create's collider and vanilla movement packets. */
public final class SableElevatorPhaseProbe {
    private static final boolean ENABLED = Boolean.getBoolean("emfattachments.sableElevatorPhases");
    private static ArrayList<Row> rows;
    private static volatile UUID rider;
    private static AbstractContraptionEntity lastCabin;

    private SableElevatorPhaseProbe() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static synchronized void reset() {
        if (!ENABLED) return;
        var player = Minecraft.getInstance().player;
        rows = new ArrayList<>();
        lastCabin = null;
        rider = player != null ? player.getUUID() : null;
    }

    public static synchronized List<?> snapshot() {
        if (rows == null) return List.of();
        rider = null;
        lastCabin = null;
        List<Row> finished = rows;
        rows = null;
        return finished;
    }

    public static void travel(String phase, Player player) {
        if (player instanceof LocalPlayer && player.getUUID().equals(rider)) add(phase, player, null);
    }

    public static void collider(String phase, AbstractContraptionEntity cabin) {
        if (rider == null || !cabin.level().isClientSide()) return;
        var player = Minecraft.getInstance().player;
        if (player == null || !SableElevatorLiveSequence.observes(player, cabin)) return;
        lastCabin = cabin;
        add(phase, player, null);
    }

    public static void sent(ServerboundMovePlayerPacket packet) {
        if (rider == null || !Minecraft.getInstance().isSameThread()) return;
        var player = Minecraft.getInstance().player;
        if (player != null && player.getUUID().equals(rider)) add("packetSend", player, packet);
    }

    public static void received(String phase, ServerPlayer player, ServerboundMovePlayerPacket packet) {
        if (player.getUUID().equals(rider) && player.serverLevel().getServer().isSameThread()) {
            add(phase, player, packet);
        }
    }

    private static synchronized void add(String phase, Player player, ServerboundMovePlayerPacket packet) {
        if (!player.getUUID().equals(rider)) return;
        var velocity = ((LivingEntityMovementExtension) player).sable$getInheritedVelocity();
        Vec3 cabinFloor = null;
        CollisionSample collisionSample = null;
        if (player instanceof LocalPlayer && lastCabin != null && !lastCabin.isRemoved()) {
            var cabin = lastCabin;
            cabinFloor = cabin.toGlobalVector(PoweredElevatorFixture.CABIN_FEET, 1);
            var carrier = Sable.HELPER.getContaining(cabin);
            if (carrier != null) cabinFloor = carrier.logicalPose().transformPosition(cabinFloor);
            var info = ((EntityMovementExtension) player).sable$getCollisionInfo();
            if (info != null) {
                var contacts = info.firstCollisions;
                collisionSample = new CollisionSample(info.inheritedMotion, info.motion, info.preDeltaMovement,
                        contacts == null ? 0 : contacts.size(), contacts != null && carrier != null && contacts.containsKey(carrier),
                        carrier != null && info.preTrackingSubLevel == carrier, carrier != null && info.trackingSubLevel == carrier,
                        info.verticalCollisionBelow);
            }
        }
        Vec3 reported = packet != null && packet.hasPosition()
                ? new Vec3(packet.getX(0), packet.getY(0), packet.getZ(0)) : null;
        rows.add(new Row(System.nanoTime(), phase, player.tickCount, player.position(), player.getDeltaMovement(),
                new Vec3(velocity.x, velocity.y, velocity.z), player.onGround(), player.getAbilities().flying,
                Sable.HELPER.getTrackingSubLevel(player) != null, cabinFloor, collisionSample, reported,
                packet == null ? null : packet.isOnGround(), packet == null ? null : packet.getClass().getSimpleName()));
    }

    private record Row(long nanoTime, String phase, int playerTick, Vec3 position, Vec3 motion,
                       Vec3 inheritedVelocity, boolean grounded, boolean flying, boolean trackingPlatform,
                       Vec3 cabinFloor, CollisionSample collision, Vec3 packetPosition, Boolean packetGrounded, String packetType) {}

    private record CollisionSample(Vec3 inheritedMotion, Vec3 motion, Vec3 preDeltaMovement, int firstCollisionCount,
                                   boolean firstCollisionWithCarrier, boolean preTrackingCarrier,
                                   boolean trackingCarrier, boolean verticalCollisionBelow) {}
}
