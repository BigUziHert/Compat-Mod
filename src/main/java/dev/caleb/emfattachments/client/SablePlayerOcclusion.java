package dev.caleb.emfattachments.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Tick-only policy; rendering remains subject to Entity Culling's native visibility decisions. */
public final class SablePlayerOcclusion {
    private SablePlayerOcclusion() {}

    private static final Attachments<Entity, SubLevel> SABLE = new Attachments<>() {
        @Override public boolean isClientPlayer(Entity entity) {
            return entity instanceof Player && entity.level() != null && entity.level().isClientSide;
        }

        @Override public SubLevel tracking(Entity entity) {
            return Sable.HELPER.getTrackingSubLevel(entity);
        }

        @Override public SubLevel vehicle(Entity entity) {
            return Sable.HELPER.getVehicleSubLevel(entity);
        }

        @Override public SubLevel pendingPlotPosition(Entity entity) {
            // A remote movement packet can arrive before the first complete tick
            // assigns trackingSubLevel. Tick culling must not block that assignment.
            var plotPosition = ((EntityStickExtension) entity).sable$getPlotPosition();
            return plotPosition == null ? null : Sable.HELPER.getContaining(entity.level(), plotPosition);
        }

        @Override public SubLevel containing(Entity entity) {
            return Sable.HELPER.getContaining(entity);
        }

        @Override public boolean isRemoved(SubLevel support) {
            return support.isRemoved();
        }
    };

    public static boolean keepsFullTick(boolean original, Entity entity) {
        return resolve(original, entity, SABLE);
    }

    // The shared, allocation-free resolver makes the lifetime and short-circuit
    // contract testable without constructing Minecraft's client singleton.
    static <E, S> boolean resolve(boolean original, E entity, Attachments<E, S> attachments) {
        if (original || !attachments.isClientPlayer(entity)) return original;
        return live(attachments.tracking(entity), attachments)
                || live(attachments.vehicle(entity), attachments)
                || live(attachments.pendingPlotPosition(entity), attachments)
                || live(attachments.containing(entity), attachments);
    }

    private static <E, S> boolean live(S support, Attachments<E, S> attachments) {
        return support != null && !attachments.isRemoved(support);
    }

    interface Attachments<E, S> {
        boolean isClientPlayer(E entity);
        S tracking(E entity);
        S vehicle(E entity);
        S pendingPlotPosition(E entity);
        S containing(E entity);
        boolean isRemoved(S support);
    }
}
