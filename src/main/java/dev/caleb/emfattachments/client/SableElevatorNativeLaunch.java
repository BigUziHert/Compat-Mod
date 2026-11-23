package dev.caleb.emfattachments.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.CollisionInfo;
import java.lang.ref.WeakReference;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Follows Sable's own bank from a tracked deck jump until the same cabin receives the rider. */
final class SableElevatorNativeLaunch {
    private final WeakReference<LocalPlayer> rider;
    private final WeakReference<AbstractContraptionEntity> cabin;
    private final WeakReference<SubLevel> carrier;
    private final int launch;
    private int tick;
    private boolean detached;
    private Vec3 lastFeet;
    private Vec3 bank = Vec3.ZERO;

    SableElevatorNativeLaunch(LocalPlayer player, AbstractContraptionEntity cabin, SubLevel carrier) {
        this.rider = new WeakReference<>(player);
        this.cabin = new WeakReference<>(cabin);
        this.carrier = new WeakReference<>(carrier);
        this.launch = player.tickCount;
        this.tick = launch - 1;
        this.lastFeet = player.position();
    }

    boolean matches(LocalPlayer player, AbstractContraptionEntity entity) {
        return player == rider.get() && entity == cabin.get();
    }

    boolean continues(LocalPlayer player) {
        var entity = cabin.get();
        return rider.get() == player && entity != null && !entity.isRemoved() && entity.level() == player.level()
                && player.tickCount == tick + 1 && player.tickCount - launch <= 40
                && lastFeet.distanceToSqr(player.position()) <= .03125 * .03125;
    }

    boolean observe(LocalPlayer player, SubLevel own, SubLevel tracking, CollisionInfo collision, Vector3d current) {
        if (own == null || own != carrier.get() || own.isRemoved() || tracking != null && tracking != own
                || player.tickCount != tick + 1 || collision == null || foreignContact(collision, own)) return false;
        if (!detached) {
            Vec3 source = collision.inheritedMotion;
            if (source == null || collision.preTrackingSubLevel != own && collision.trackingSubLevel != own
                    || Math.abs(source.x - current.x) > 1e-7 || Math.abs(source.z - current.z) > 1e-7) return false;
            detached = tracking == null;
        } else if (tracking != null || !decayed(collision, current)) return false;
        bank = new Vec3(current.x, 0, current.z);
        lastFeet = player.position();
        tick = player.tickCount;
        return bank.horizontalDistanceSqr() > 1e-8 && bank.horizontalDistanceSqr() <= SableElevatorReleaseMotion.MAX_STEP * SableElevatorReleaseMotion.MAX_STEP;
    }

    Vec3 applied(LocalPlayer player, SubLevel own, CollisionInfo collision, Vector3d current) {
        if (!detached || own != carrier.get() || player.tickCount != tick + 1 || collision == null
                || foreignContact(collision, own) || !decayed(collision, current)) return Vec3.ZERO;
        // Sable applied the previous bank during travel. Credit at most the resolved displacement along it.
        double fraction = Mth.clamp(player.position().subtract(lastFeet).dot(bank) / bank.horizontalDistanceSqr(), 0, 1);
        return fraction > 0 ? bank.scale(fraction) : Vec3.ZERO;
    }

    private boolean decayed(CollisionInfo collision, Vector3d current) {
        // Unobstructed airborne travel in Sable 2.0.6 applies a 0.99 horizontal multiplier.
        return collision.inheritedMotion == null && collision.preTrackingSubLevel == null && collision.trackingSubLevel == null
                && Math.abs(current.x - .99 * bank.x) < 1e-7 && Math.abs(current.z - .99 * bank.z) < 1e-7;
    }

    private static boolean foreignContact(CollisionInfo collision, SubLevel own) {
        return collision.preTrackingSubLevel != null && collision.preTrackingSubLevel != own
                || collision.trackingSubLevel != null && collision.trackingSubLevel != own
                || collision.firstCollisions != null && !collision.firstCollisions.isEmpty()
                    && (collision.firstCollisions.size() != 1 || !collision.firstCollisions.containsKey(own));
    }
}
