package dev.caleb.emfattachments.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.CollisionInfo;
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
    private static final double SURFACE_EPSILON = 0.001;
    private static final double MAX_CARRIER_STEP = SableElevatorReleaseMotion.MAX_STEP;
    private static final int MAX_RETURN_TICKS = 40;
    private static final double MAX_RETURN_STEP_SQUARED = 4 * MAX_CARRIER_STEP * MAX_CARRIER_STEP;

    private static WeakReference<LocalPlayer> supportedPlayer = new WeakReference<>(null);
    private static WeakReference<AbstractContraptionEntity> supportingCabin = new WeakReference<>(null);
    private static int contactTick;
    private static int carriedTick;
    private static Vec3 lastSupportedFeet;
    private static boolean resolvingFloorContact;
    private static Vec3 floorCredit;
    private static AirborneCarry airborneCarry;
    private static SableElevatorNativeLaunch nativeLaunch;
    private static int animationFloorTick = Integer.MIN_VALUE;
    private static boolean animationFloor;
    private static Vec3 cameraMoveFrom;
    private static Vec3 cameraMoveTo;
    private static int cameraMoveTick;
    private SableElevatorRiderCarry() {}

    public static void contact(Entity entity, AbstractContraptionEntity cabin) {
        if (!(entity instanceof LocalPlayer player)) return;
        if (!(cabin.getContraption() instanceof ElevatorContraption)) return;
        if (!canRide(player)) {
            // Native contact can still carry the rider on the first jump/flight input.
            if (supportedPlayer.get() == player && supportingCabin.get() == cabin) carriedTick = player.tickCount;
            return;
        }
        if (supportedPlayer.get() != player || supportingCabin.get() != cabin) {
            if (nativeLaunch != null && !nativeLaunch.matches(player, cabin)) nativeLaunch = null;
            if (airborneCarry != null && airborneCarry.player.get() == player && airborneCarry.cabin.get() != cabin) {
                airborneCarry.landingCarrier = null;
            }
            supportedPlayer = new WeakReference<>(player);
            supportingCabin = new WeakReference<>(cabin);
            lastSupportedFeet = null;
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
        boolean alreadyCarried = carriedDuringTravel(collision, tracking, carrier, player, cabin);
        if (!alreadyCarried && !usingRelease) {
            if (nativeLaunch == null || !nativeLaunch.matches(player, cabin) || tracking != null
                    || !canRide(player) || !player.onGround() || player.getDeltaMovement().y > 0) return movement;
            Vec3 applied = nativeLaunch.applied(player, carrier, collision, inheritedVelocity(player));
            if (applied == Vec3.ZERO) return movement;
            Vec3 step = carrierStep(carrier, player.position());
            double size = step.horizontalDistanceSqr();
            if (!(size > 1e-8 && size <= MAX_CARRIER_STEP * MAX_CARRIER_STEP)) return movement;
            double overlap = Mth.clamp(Math.min(applied.dot(step), movement.dot(step)) / size, 0, 1);
            if (overlap <= 0) return movement;
            Vec3 credit = step.scale(overlap);
            consumeHorizontalCredit(player, credit, 1);
            nativeLaunch = null;
            return movement.subtract(credit);
        }

        Vec3 carrierStep = carrierStep(carrier, player.position());
        double stepSquared = carrierStep.lengthSqr();
        if (stepSquared <= 1e-8) return movement;
        Vec3 applied = alreadyCarried ? collision.inheritedMotion : release.motion;
        double overlap = Mth.clamp(applied.dot(carrierStep) / stepSquared, 0, 1);
        if (tracking == null && canRide(player) && player.onGround() && (alreadyCarried || player.getDeltaMovement().y <= 0)) {
            // Create has taken over on the floor, so Sable must stop replaying the same velocity in travel.
            Vec3 direction = usingRelease ? release.motion : carrierStep;
            consumeHorizontalCredit(player, direction, usingRelease ? 1 : overlap);
            if (usingRelease) airborneCarry = null;
        }
        return movement.subtract(carrierStep.scale(overlap));
    }

    private static boolean carriedDuringTravel(CollisionInfo collision, SubLevel tracking, SubLevel carrier,
                                               LocalPlayer player, AbstractContraptionEntity cabin) {
        if (collision == null || collision.inheritedMotion == null) return false;
        if (tracking == carrier || collision.preTrackingSubLevel == carrier || collision.trackingSubLevel == carrier) return true;
        // A side contact can borrow the deck pose for some of Sable's substeps while ending untracked.
        return tracking == null && collision.preTrackingSubLevel == null && collision.trackingSubLevel == null
                && supportedPlayer.get() == player && supportingCabin.get() == cabin
                && collision.firstCollisions != null && collision.firstCollisions.size() == 1
                && collision.firstCollisions.containsKey(carrier);
    }

    private static void consumeHorizontalCredit(LocalPlayer player, Vec3 direction, double limit) {
        var inherited = inheritedVelocity(player);
        double distance = direction.horizontalDistanceSqr();
        if (distance <= 1e-8) return;
        double along = (inherited.x * direction.x + inherited.z * direction.z) / distance;
        double transfer = Mth.clamp(along, 0, limit);
        inherited.sub(direction.x * transfer, 0, direction.z * transfer);
    }

    public static void beforeTravel(LocalPlayer player) {
        if (nativeLaunch != null && (!nativeLaunch.continues(player) || !mayInherit(player)
                || player.getAbilities().flying)) nativeLaunch = null;
        var release = airborneCarry;
        if (release != null) {
            var cabin = release.cabin.get();
            if (release.player.get() == player && mayInherit(player) && liveCabin(player, cabin)
                    && Sable.HELPER.getTrackingSubLevel(player) == null) {
                var inherited = inheritedVelocity(player);
                Vec3 motion = new Vec3(inherited.x, 0, inherited.z);
                if (motion.lengthSqr() > 1e-8) {
                    if (release.landingCarrier != null && (player.getAbilities().flying
                            || player.tickCount - release.tick != 1 || player.tickCount - release.launchTick > MAX_RETURN_TICKS
                            || release.lastFeet.distanceToSqr(player.position()) > FLOOR_TOLERANCE * FLOOR_TOLERANCE)) {
                        release.landingCarrier = null;
                    }
                    release.tick = player.tickCount;
                    release.motion = motion;
                    return;
                }
            }
            airborneCarry = null;
        }
        if (supportedPlayer.get() != player || player.tickCount - contactTick != 1 || !mayInherit(player)
                || lastSupportedFeet == null || lastSupportedFeet.distanceToSqr(player.position()) > FLOOR_TOLERANCE * FLOOR_TOLERANCE
                || !(player.getAbilities().flying || player.input.jumping && player.getDeltaMovement().y > 0.1)) return;
        var cabin = supportingCabin.get();
        if (!liveCabin(player, cabin)) return;
        var carrier = Sable.HELPER.getContaining(cabin);
        if (carrier == null || carrier.isRemoved()) return;
        var tracking = Sable.HELPER.getTrackingSubLevel(player);
        if (tracking != null) {
            if (tracking == carrier && !player.getAbilities().flying) nativeLaunch = new SableElevatorNativeLaunch(player, cabin, carrier);
            return;
        }
        Vec3 position = player.position();
        Vec3 step = SableElevatorReleaseMotion.forRelease(carrier, position, carrierStep(carrier, position));
        double distance = step.lengthSqr();
        if (!(distance > 1e-8 && distance <= MAX_CARRIER_STEP * MAX_CARRIER_STEP)) return;
        var inherited = inheritedVelocity(player);
        if (inherited.x * inherited.x + inherited.z * inherited.z > 1e-8) return;

        // Use Sable's normal airborne drag rather than continuing to move the player with the cabin.
        inherited.add(step.x, 0, step.z);
        airborneCarry = new AirborneCarry(player, cabin, step);
        if (!player.getAbilities().flying) airborneCarry.landingCarrier = new WeakReference<>(carrier);
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
        if (nativeLaunch != null && nativeLaunch.matches(player, cabin)
                && !nativeLaunch.observe(player, Sable.HELPER.getContaining(cabin), Sable.HELPER.getTrackingSubLevel(player),
                        ((EntityMovementExtension) player).sable$getCollisionInfo(), inheritedVelocity(player))) nativeLaunch = null;
        var release = airborneCarry;
        if (release != null && release.landingCarrier != null && release.player.get() == player
                && release.cabin.get() == cabin && release.tick == player.tickCount) release.lastFeet = player.position();
        if (supportedPlayer.get() == player && supportingCabin.get() == cabin && carriedTick == player.tickCount) {
            lastSupportedFeet = player.position();
        }
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
        boolean sameSupport = supportedPlayer.get() == player && supportingCabin.get() == cabin;
        if (sameSupport && carriedTick == player.tickCount) return Vec3.ZERO;
        var jump = airborneCarry;
        if (jump != null && jump.landingCarrier != null && jump.tick == player.tickCount
                && jump.player.get() == player && jump.cabin.get() == cabin) {
            Vec3 landing = returningJump(player, cabin, jump);
            if (landing != Vec3.ZERO) return landing;
        }
        if (!sameSupport) return Vec3.ZERO;
        int missedTicks = player.tickCount - contactTick;
        if (missedTicks < 1 || missedTicks > MAX_MISSED_CONTACT_TICKS || !mayInherit(player)
                || cabin.isRemoved() || cabin.level() != player.level()) return release();
        SubLevel carrier = Sable.HELPER.getContaining(cabin);
        if (carrier == null) return Vec3.ZERO;
        if (carrier.isRemoved() || Sable.HELPER.getTrackingSubLevel(player) != null) return release();
        var collision = ((EntityMovementExtension) player).sable$getCollisionInfo();
        boolean nativeCarry = carriedDuringTravel(collision, null, carrier, player, cabin);

        var pose = carrier.logicalPose();
        double cabinRise = pose.transformNormal(new Vec3(0, cabin.getY() - cabin.getPrevPositionVec().y, 0)).y;
        double upwardMotion = Math.max(0, cabinRise);
        // A cabin pushing its floor up can give the rider a legitimate upward velocity.
        Vec3 position = player.position();
        Vec3 carriedPosition = pose.transformPosition(carrier.lastPose().transformPositionInverse(position));
        Vec3 movement = carriedPosition.subtract(position).multiply(1, 0, 1);
        double distance = movement.horizontalDistanceSqr();
        boolean justCarried = player.tickCount - carriedTick == 1 && lastSupportedFeet != null;
        boolean ordinaryStep = distance > 1e-8 && distance <= MAX_CARRIER_STEP * MAX_CARRIER_STEP;
        Vec3 releaseStep = null;
        if (!ordinaryStep) {
            if (justCarried) releaseStep = SableElevatorReleaseMotion.forRelease(carrier, position, movement);
            if (releaseStep == null || !(releaseStep.horizontalDistanceSqr() > 1e-8)) {
                return distance <= 1e-8 ? Vec3.ZERO : release();
            }
        }
        Vec3 remaining = movement;
        if (nativeCarry && distance > 1e-8) {
            double fraction = Mth.clamp(collision.inheritedMotion.dot(movement) / distance, 0, 1);
            remaining = movement.subtract(movement.scale(fraction));
        }
        Vec3 proposed = position.add(remaining);
        double maxRise = Math.min(MAX_FLOOR_GAP, upwardMotion + FLOOR_TOLERANCE);
        if (justCarried && lastSupportedFeet.distanceToSqr(player.xo, player.yo, player.zo) > FLOOR_TOLERANCE * FLOOR_TOLERANCE) {
            return release();
        }
        double floorY = Double.NaN;
        if (canRide(player) && player.getDeltaMovement().y <= upwardMotion + 0.12) {
            if (justCarried) {
                // Sweep from the previous feet, advanced with the cabin, so a descending
                // rider reaches the top face even if travel crossed it before Create ran.
                double previousY = pose.transformPosition(carrier.lastPose().transformPositionInverse(lastSupportedFeet)).y;
                maxRise = Mth.clamp(previousY + cabinRise - proposed.y + FLOOR_TOLERANCE, maxRise, MAX_FLOOR_GAP);
            }
            floorY = floorHeight(cabin, carrier, proposed, maxRise, MAX_FLOOR_GAP, player);
            if (!Double.isFinite(floorY) && justCarried) {
                floorY = SableElevatorFootprint.height(cabin, carrier, player, proposed, maxRise, MAX_FLOOR_GAP);
            }
        }
        if (!Double.isFinite(floorY)) {
            clear();
            var inherited = inheritedVelocity(player);
            boolean groundedElsewhere = player.onGround() && player.verticalCollisionBelow;
            if (justCarried && !nativeCarry && !groundedElsewhere && inherited.x * inherited.x + inherited.z * inherited.z <= 1e-8
                    && (airborneCarry == null || airborneCarry.player.get() != player)) {
                // No travel or contact supplied this tick's carrier motion. Resolve one last
                // step now, then give the collision-allowed result to Sable for future travel.
                if (releaseStep == null) releaseStep = SableElevatorReleaseMotion.forRelease(carrier, position, movement);
                if (!(releaseStep.horizontalDistanceSqr() > 1e-8)) return Vec3.ZERO;
                airborneCarry = new AirborneCarry(player, cabin, releaseStep);
                airborneCarry.awaitingCollision = true;
                return releaseStep;
            }
            return Vec3.ZERO;
        }
        // While supported, follow the real cabin pose. Keep the existing displacement limit
        // for a possible teleport, even when a prior server snapshot implies a modest velocity.
        if (distance > MAX_CARRIER_STEP * MAX_CARRIER_STEP) return release();
        carriedTick = player.tickCount;
        resolvingFloorContact = justCarried && floorY >= proposed.y - SURFACE_EPSILON;
        floorCredit = resolvingFloorContact && nativeCarry ? movement.subtract(remaining) : null;
        return justCarried && floorY > proposed.y ? remaining.add(0, floorY - proposed.y, 0) : remaining;
    }

    private static Vec3 returningJump(LocalPlayer player, AbstractContraptionEntity cabin, AirborneCarry jump) {
        if (!canRide(player) || player.tickCount == jump.launchTick || player.getDeltaMovement().y >= 0
                || player.onGround() && player.verticalCollisionBelow || !liveCabin(player, cabin)) return Vec3.ZERO;
        SubLevel carrier = jump.landingCarrier.get();
        if (carrier == null || carrier.isRemoved() || Sable.HELPER.getContaining(cabin) != carrier
                || Sable.HELPER.getTrackingSubLevel(player) != null) return Vec3.ZERO;
        Vec3 end = player.position();
        if (jump.lastFeet.distanceToSqr(player.xo, player.yo, player.zo) > FLOOR_TOLERANCE * FLOOR_TOLERANCE
                || jump.lastFeet.distanceToSqr(end) > MAX_RETURN_STEP_SQUARED) return Vec3.ZERO;
        Vec3 previousLocal = cabin.toLocalVector(carrier.lastPose().transformPositionInverse(jump.lastFeet), 0, true);
        Vec3 start = carrier.logicalPose().transformPosition(cabin.toGlobalVector(previousLocal, 1));
        if (start.distanceToSqr(jump.lastFeet) > MAX_RETURN_STEP_SQUARED) return Vec3.ZERO;
        Vec3 correction = landingCorrection(start, end,
                (feet, rise, gap) -> SableElevatorFootprint.height(cabin, carrier, player, feet, rise, gap));
        jump.landingPending = correction != Vec3.ZERO;
        return correction;
    }

    @FunctionalInterface
    interface FloorProbe {
        double height(Vec3 feet, double rise, double gap);
    }

    static Vec3 landingCorrection(Vec3 start, Vec3 end, FloorProbe floorAt) {
        double descent = start.y - end.y;
        if (!(descent > SURFACE_EPSILON && descent <= MAX_FLOOR_GAP)) return Vec3.ZERO;
        double floor = floorAt.height(end, descent, SURFACE_EPSILON);
        if (!Double.isFinite(floor) || floor < end.y || floor >= start.y) return Vec3.ZERO;
        Vec3 crossing = start.lerp(end, (start.y - floor) / descent);
        // An airborne foot can pass an adjacent top before it overlaps the final footprint.
        double crossed = floorAt.height(crossing, SURFACE_EPSILON, SURFACE_EPSILON);
        if (!Double.isFinite(crossed)) return Vec3.ZERO;
        // Distinguish exact contact (including a zero lift) from the no-contact sentinel above.
        return new Vec3(0, floor - end.y, 0);
    }

    public static void finishCarry(LocalPlayer player, AbstractContraptionEntity cabin, Vec3 requested, Vec3 allowed) {
        boolean resolved = allowed.distanceToSqr(requested) <= 1e-8;
        boolean allowedHeight = allowed.y == 0 || requested.y > 0 && allowed.y >= 0 && allowed.y <= requested.y + 1e-7;
        var jump = airborneCarry;
        if (jump != null && jump.landingPending && jump.tick == player.tickCount && jump.player.get() == player
                && jump.cabin.get() == cabin) {
            jump.landingPending = false;
            jump.landingCarrier = null;
            if (resolved && allowedHeight) {
                supportedPlayer = jump.player;
                supportingCabin = jump.cabin;
                carriedTick = player.tickCount;
                resolvingFloorContact = true;
                double length = jump.initialMotion.horizontalDistanceSqr();
                double applied = player.position().subtract(jump.lastFeet).dot(jump.initialMotion);
                double borrowed = jump.motion.dot(jump.initialMotion);
                floorCredit = jump.initialMotion.scale(Mth.clamp(Math.min(applied, borrowed) / length, 0, 1));
                airborneCarry = null;
            }
        }
        if (!resolved) clear();
        if (allowedHeight) {
            player.setPos(player.position().add(allowed));
            if (resolvingFloorContact && resolved) {
                // Renew only on a verified solid surface. An actual descent gap still expires.
                contactTick = player.tickCount;
                Vec3 velocity = player.getDeltaMovement();
                if (velocity.y < 0) player.setDeltaMovement(velocity.x, 0, velocity.z);
                player.setOnGround(true);
                player.fallDistance = 0;
                if (floorCredit != null) consumeHorizontalCredit(player, floorCredit, 1);
            }
        }
        resolvingFloorContact = false;
        floorCredit = null;
        var release = airborneCarry;
        if (release != null && release.awaitingCollision && release.tick == player.tickCount
                && release.player.get() == player && release.cabin.get() == cabin) {
            if (allowed.y == 0 && allowed.horizontalDistanceSqr() > 1e-8) {
                inheritedVelocity(player).add(allowed.x, 0, allowed.z);
                release.motion = allowed;
                release.awaitingCollision = false;
            } else {
                airborneCarry = null;
            }
        }
    }

    private static boolean hasFloor(AbstractContraptionEntity cabin, SubLevel carrier, Vec3 feet,
                                    double maxRise, double maxGap) {
        return Double.isFinite(floorHeight(cabin, carrier, feet, maxRise, maxGap, null));
    }

    private static double floorHeight(AbstractContraptionEntity cabin, SubLevel carrier, Vec3 feet,
                                      double maxRise, double maxGap, LocalPlayer rider) {
        Vec3 start = feet.add(0, maxRise, 0);
        Vec3 end = feet.add(0, -maxGap, 0);
        if (carrier != null) {
            start = carrier.logicalPose().transformPositionInverse(start);
            end = carrier.logicalPose().transformPositionInverse(end);
        }
        BlockGetter cabinBlocks = cabin.getContraption().getContraptionWorld();
        Vec3 localStart = cabin.toLocalVector(start, 1);
        var context = rider == null ? CollisionContext.empty() : SableElevatorFootprint.context(rider, localStart.y);
        var ray = new ClipContext(localStart, cabin.toLocalVector(end, 1),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context);
        ((ClipContextExtension) ray).sable$setDoNotProject(true);
        var floor = cabinBlocks.clip(ray);
        if (floor.getType() == HitResult.Type.MISS || floor.isInside() || floor.getDirection() != Direction.UP) return Double.NaN;
        Vec3 position = cabin.toGlobalVector(floor.getLocation(), 1);
        double floorY = carrier == null ? position.y : carrier.logicalPose().transformPosition(position).y;
        double gap = feet.y - floorY;
        return gap >= -maxRise && gap <= maxGap ? floorY : Double.NaN;
    }

    public static void clear() {
        supportedPlayer.clear();
        supportingCabin.clear();
        lastSupportedFeet = null;
        resolvingFloorContact = false;
        floorCredit = null;
        animationFloorTick = Integer.MIN_VALUE;
        cameraMoveFrom = null;
        cameraMoveTo = null;
    }

    private static Vec3 release() {
        clear();
        return Vec3.ZERO;
    }

    private static boolean canRide(LocalPlayer player) {
        return mayInherit(player) && !player.getAbilities().flying && !player.input.jumping;
    }

    private static boolean mayInherit(LocalPlayer player) {
        return player.isAlive() && !player.isPassenger() && !player.isSwimming() && !player.isFallFlying() && !player.isSpectator();
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
        final int launchTick;
        final Vec3 initialMotion;
        Vec3 motion;
        WeakReference<SubLevel> landingCarrier;
        Vec3 lastFeet;
        boolean landingPending;
        boolean awaitingCollision;

        AirborneCarry(LocalPlayer player, AbstractContraptionEntity cabin, Vec3 motion) {
            this.player = new WeakReference<>(player);
            this.cabin = new WeakReference<>(cabin);
            this.tick = player.tickCount;
            this.launchTick = player.tickCount;
            this.initialMotion = motion;
            this.motion = motion;
            this.lastFeet = player.position();
        }
    }
}
