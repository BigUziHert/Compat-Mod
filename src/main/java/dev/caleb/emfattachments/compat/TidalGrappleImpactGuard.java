package dev.caleb.emfattachments.compat;

import com.github.L_Ender.cataclysm.entity.projectile.Tidal_Hook_Entity;
import com.github.L_Ender.cataclysm.init.ModDataAttachments;
import com.github.L_Ender.cataclysm.items.Tidal_Claws;
import dev.caleb.emfattachments.mixin.TidalHookStateAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** The native Tidal hook applies deliberate movement, like a Riptide or rope ride. */
public final class TidalGrappleImpactGuard {
    private TidalGrappleImpactGuard() {}

    public static boolean isNativePulling(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.isPassenger() || !player.isUsingItem()) return false;
        ItemStack using = player.getUseItem();
        if (!(using.getItem() instanceof Tidal_Claws)
                || !player.getData(ModDataAttachments.HOOK_FALLING)) return false;
        // Tidal_Claws.use configures maxRange=30. Keep the query bounded and
        // require the real owned, live, pulling hook rather than a gravity flag.
        for (Tidal_Hook_Entity hook : player.serverLevel().getEntitiesOfClass(
                Tidal_Hook_Entity.class, player.getBoundingBox().inflate(31))) {
            if (hook.isRemoved() || hook.getOwner() != player || player.distanceToSqr(hook) > 30 * 30) continue;
            TidalHookStateAccessor state = (TidalHookStateAccessor) (Object) hook;
            if (state.emfattachments$isPulling() && state.emfattachments$stack() == using
                    && (player.getMainHandItem() == using || player.getOffhandItem() == using)) return true;
        }
        return false;
    }
}
