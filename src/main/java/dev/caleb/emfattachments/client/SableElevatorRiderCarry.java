package dev.caleb.emfattachments.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.lang.ref.WeakReference;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3d;

/** Transfers lateral movement between Sable's deck and a Create elevator on that deck. */
public final class SableElevatorRiderCarry {
    private static final int MAX_MISSED_CONTACT_TICKS = 8;
    private static final double MAX_FLOOR_GAP = 0.75;
    private static final double FLOOR_TOLERANCE = 0.03125;
    private static final double MAX_CARRIER_STEP = 1.5;

    private static WeakReference<LocalPlayer> supportedPlayer = new WeakReference<>(null);
    private static WeakReference<AbstractContraptionEntity> supportingCabin = new WeakReference<>(null);
    private static int contactTick;
    private static int carriedTick;
    private static AirborneCarry airborneCarry;
    private static int animationFloorTick = Integer.MIN_VALUE;
    private static boolean animationFloor;
    private static Vec3 cameraMoveFrom;
    private static Vec3 cameraMoveTo;
    private static int cameraMoveTick;

    private SableElevatorRiderCarry() {}

    public static void contact(Entity entity, AbstractContraptionEntity cabin) {
        if (!(entity instanceof LocalPlayer player)) return;
        if (!(cabin.getContraption() instanceof ElevatorContraption) || !canRide(player)) {
            clear();
            return;
        }
        if (supportedPlayer.get() != player || supportingCabin.get() != cabin) {
            supportedPlayer = new WeakReference<>(player);
            supportingCabin = new WeakReference<>(cabin);
            animationFloorTick = Integer.MIN_VALUE;
            cameraMoveFrom = null;
            cameraMoveTo = null;
        }
        contactTick = player.tickCount;
        carriedTick = player.tickCount;
    }

    public static Vec3 removeOverlappingCarrierMotion(Vec3 movement, Entity rider, AbstractContraptionEntity cabin) {
        if (movement == Vec3.ZERO || !(rider instanceof LocalPlayer player)
                || !(cabin.getContraption() instanceof ElevatorContraption)) return movement;
        SubLevel carrier = Sable.HELPER.getContaining(cabin);
        if (carrier == null || carrier.isRemoved()) return movement;
        var release = airborneCarry;
        boolean usingRelease = release != null && release.player.get() == player && release.cabin.get() == cabin
                && release.tick == player.tickCount;
        var collision = ((EntityMovementExtension) player).sable$getCollisionInfo();
        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
        boolean alreadyCarried = collision != null && collision.inheritedMotion != null
                && (collision.preTrackingSubLevel == carrier || tracking == carrier);
        if (!alreadyCarried && !usingRelease) return movement;

        Vec3 carrierStep = carrierStep(carrier, player.position());
        double stepSquared = carrierStep.lengthSqr();
        if (stepSquared <= 1e-8) return movement;
        Vec3 applied = alreadyCarried ? collision.inheritedMotion : release.motion;
        double overlap = Mth.clamp(applied.dot(carrierStep) / stepSquared, 0, 1);
        if (tracking == null && canRide(player) && player.onGround() && (alreadyCarried || player.getDeltaMovement().y <= 0)) {
            // Create has taken over on the floor, so Sable must stop replaying the same velocity in travel.
            var inherited = inheritedVelocity(player);
            Vec3 direction = usingRelease ? release.motion : carrierStep;
            double along = (inherited.x * direction.x + inherited.z * direction.z) / direction.lengthSqr();
            double transferred = Mth.clamp(along, 0, usingRelease ? 1 : overlap);
            inherited.sub(direction.x * transferred, 0, direction.z * transferred);
            if (usingRelease) airborneCarry = null;
        }
        return movement.subtract(carrierStep.scale(overlap));
    }

    public static void beforeTravel(LocalPlayer player) {
        var release = airborneCarry;
        if (release != null) {
            var cabin = release.cabin.get();
            if (release.player.get() == player && liveCabin(player, cabin)
                    && Sable.HELPER.getTrackingSubLevel(player) == null) {
                var inherited = inheritedVelocity(player);
                Vec3 motion = new Vec3(inherited.x, 0, inherited.z);
                if (motion.lengthSqr() > 1e-8) {
                    release.tick = player.tickCount;
                    release.motion = motion;
                    return;
                }
            }
            airborneCarry = null;
        }
        if (supportedPlayer.get() != player || player.tickCount - contactTick != 1 || player.isPassenger()
                || !(player.getAbilities().flying || player.input.jumping && player.getDeltaMovement().y > 0.1)) return;
        var cabin = supportingCabin.get();
        if (!liveCabin(player, cabin) || Sable.HELPER.getTrackingSubLevel(player) != null) return;
        var carrier = Sable.HELPER.getContaining(cabin);
        if (carrier == null || carrier.isRemoved()) return;
        Vec3 step = carrierStep(carrier, player.position());
        double distance = step.lengthSqr();
        if (!(distance > 1e-8 && distance <= MAX_CARRIER_STEP * MAX_CARRIER_STEP)) return;
        var inherited = inheritedVelocity(player);
        if (inherited.x * inherited.x + inherited.z * inherited.z > 1e-8) return;

        // Use Sable's normal airborne drag rather than continuing to move the player with the cabin.
        inherited.add(step.x, 0, step.z);
        airborneCarry = new AirborneCarry(player, cabin, step);
    }

    /** Gives the Fresh renderer a floor witness for the same physical support used by Create. */
    public static Object animationSupport(LocalPlayer player) {
        if (!canRide(player) || supportedPlayer.get() != player || player.tickCount - contactTick > MAX_MISSED_CONTACT_TICKS) {
            return null;
        }
        var cabin = supportingCabin.get();
        if (!liveCabin(player, cabin)) return null;
        if (animationFloorTick != player.tickCount) {
            SubLevel carrier = Sable.HELPER.getContaining(cabin);
            animationFloorTick = player.tickCount;
            // A short descent gap is normal. Reject the rising phase of an independent jump and require a solid top.
            double risingSpeed = cabin.getY() - cabin.getPrevPositionVec().y;
            animationFloor = player.getDeltaMovement().y <= Math.max(0, risingSpeed) + 0.12
                    && hasFloor(cabin, carrier, player.position(), 0.25, 0.625);
        }
        return animationFloor ? cabin : null;
    }

    public static void recordCameraMovement(LocalPlayer player, AbstractContraptionEntity cabin, Vec3 before) {
        if (before == null || supportedPlayer.get() != player || supportingCabin.get() != cabin
                || player.tickCount - contactTick > MAX_MISSED_CONTACT_TICKS
                || !canRide(player) || before.equals(player.position())) return;
        if (cameraMoveTick != player.tickCount || !before.equals(cameraMoveTo)) cameraMoveFrom = before;
        cameraMoveTo = player.position();
        cameraMoveTick = player.tickCount;
    }

    public static boolean isRecordedCameraMovement(LocalPlayer player, Vec3 before, Vec3 after) {
        int elapsed = player.tickCount - cameraMoveTick;
        return elapsed >= 0 && elapsed <= 1 && supportedPlayer.get() == player
                && liveCabin(player, supportingCabin.get()) && before.equals(cameraMoveFrom) && after.equals(cameraMoveTo);
    }

    public static Vec3 missingCarry(LocalPlayer player, AbstractContraptionEntity cabin) {
        if (supportedPlayer.get() != player || supportingCabin.get() != cabin || carriedTick == player.tickCount) {
            return Vec3.ZERO;
        }
        int missedTicks = player.tickCount - contactTick;
        if (missedTicks < 1 || missedTicks > MAX_MISSED_CONTACT_TICKS || !canRide(player)
                || cabin.isRemoved() || cabin.level() != player.level()) return release();
        SubLevel carrier = Sable.HELPER.getContaining(cabin);
        if (carrier == null) return Vec3.ZERO;
        if (carrier.isRemoved() || Sable.HELPER.getTrackingSubLevel(player) != null) return release();
        var collision = ((EntityMovementExtension) player).sable$getCollisionInfo();
        if (collision != null && collision.preTrackingSubLevel == carrier && collision.inheritedMotion != null) {
            // Sable has already carried this player during their move, before Create's collider tick.
            return Vec3.ZERO;
        }

        var pose = carrier.logicalPose();
        double cabinRise = pose.transformNormal(new Vec3(0, cabin.getY() - cabin.getPrevPositionVec().y, 0)).y;
        double upwardMotion = Math.max(0, cabinRise);
        // A cabin pushing its floor up can give the rider a legitimate upward velocity.
        if (player.getDeltaMovement().y > upwardMotion + 0.12) return release();
        Vec3 position = player.position();
        Vec3 carriedPosition = pose.transformPosition(carrier.lastPose().transformPositionInverse(position));
        Vec3 movement = carriedPosition.subtract(position).multiply(1, 0, 1);
        double distance = movement.horizontalDistanceSqr();
        if (!(distance > 1e-8 && distance <= MAX_CARRIER_STEP * MAX_CARRIER_STEP)) return Vec3.ZERO;

        Vec3 proposed = position.add(movement);
        double maxRise = Math.min(MAX_FLOOR_GAP, upwardMotion + FLOOR_TOLERANCE);
        if (!hasFloor(cabin, carrier, proposed, maxRise, MAX_FLOOR_GAP)) return release();
        carriedTick = player.tickCount;
        return movement;
    }

    private static boolean hasFloor(AbstractContraptionEntity cabin, SubLevel carrier, Vec3 feet,
                                    double maxRise, double maxGap) {
        Vec3 start = feet.add(0, maxRise, 0);
        Vec3 end = feet.add(0, -maxGap, 0);
        if (carrier != null) {
            start = carrier.logicalPose().transformPositionInverse(start);
            end = carrier.logicalPose().transformPositionInverse(end);
        }
        BlockGetter cabinBlocks = cabin.getContraption().getContraptionWorld();
        var ray = new ClipContext(cabin.toLocalVector(start, 1), cabin.toLocalVector(end, 1),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        ((ClipContextExtension) ray).sable$setDoNotProject(true);
        var floor = cabinBlocks.clip(ray);
        if (floor.getType() == HitResult.Type.MISS || floor.getDirection() != Direction.UP) return false;
        Vec3 position = cabin.toGlobalVector(floor.getLocation(), 1);
        double floorY = carrier == null ? position.y : carrier.logicalPose().transformPosition(position).y;
        double gap = feet.y - floorY;
        return gap >= -maxRise && gap <= maxGap;
    }

    public static void clear() {
        supportedPlayer.clear();
        supportingCabin.clear();
        animationFloorTick = Integer.MIN_VALUE;
        cameraMoveFrom = null;
        cameraMoveTo = null;
    }

    private static Vec3 release() {
        clear();
        return Vec3.ZERO;
    }

    private static boolean canRide(LocalPlayer player) {
        return player.isAlive() && !player.isPassenger() && !player.getAbilities().flying
                && !player.isSwimming() && !player.isFallFlying() && !player.isSpectator()
                && !player.input.jumping;
    }

    private static boolean liveCabin(LocalPlayer player, AbstractContraptionEntity cabin) {
        return cabin != null && !cabin.isRemoved() && cabin.level() == player.level();
    }

    private static Vec3 carrierStep(SubLevel carrier, Vec3 feet) {
        Vec3 previousFeet = carrier.lastPose().transformPosition(carrier.logicalPose().transformPositionInverse(feet));
        return feet.subtract(previousFeet).multiply(1, 0, 1);
    }

    private static Vector3d inheritedVelocity(LocalPlayer player) {
        return ((LivingEntityMovementExtension) player).sable$getInheritedVelocity();
    }

    private static final class AirborneCarry {
        final WeakReference<LocalPlayer> player;
        final WeakReference<AbstractContraptionEntity> cabin;
        int tick;
        Vec3 motion;

        AirborneCarry(LocalPlayer player, AbstractContraptionEntity cabin, Vec3 motion) {
            this.player = new WeakReference<>(player);
            this.cabin = new WeakReference<>(cabin);
            this.tick = player.tickCount;
            this.motion = motion;
        }
    }
}
