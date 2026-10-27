package dev.caleb.emfattachments.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Gives Sable's dedicated bed transform sole ownership of sleeping player rotation. */
public final class SableSleepingPose {
    private SableSleepingPose() {}

    private static final BedTransform<Entity> SABLE = new BedTransform<>() {
        @Override public boolean isSleepingClientPlayer(Entity entity) {
            return entity instanceof Player player && player.level().isClientSide && player.isSleeping();
        }

        @Override public boolean hasBedDirection(Entity entity) {
            return ((Player) entity).getBedOrientation() != null;
        }

        @Override public boolean hasLiveClientBed(Entity entity) {
            var player = (Player) entity;
            var bedPosition = player.getSleepingPos();
            if (bedPosition.isEmpty()) return false;
            var subLevel = Sable.HELPER.getContaining(player.level(), bedPosition.get());
            return subLevel instanceof ClientSubLevel && !subLevel.isRemoved();
        }
    };

    public static boolean keepsGeneralRotation(boolean original, Entity entity) {
        return resolve(original, entity, SABLE);
    }

    // Keep Sable's dedicated LivingEntityRenderer sleeping transform, including
    // its placement before vanilla's bed-direction translation. Only exclude
    // the second, eye-pivoted rotation in its general dispatcher handler.
    static <E> boolean resolve(boolean original, E entity, BedTransform<E> bed) {
        if (!original || !bed.isSleepingClientPlayer(entity)) return original;
        return !(bed.hasBedDirection(entity) && bed.hasLiveClientBed(entity));
    }

    interface BedTransform<E> {
        boolean isSleepingClientPlayer(E entity);
        boolean hasBedDirection(E entity);
        boolean hasLiveClientBed(E entity);
    }
}
