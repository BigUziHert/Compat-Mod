package dev.caleb.emfattachments.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** Keeps the normal movement packet and the rendered rider at the same point in the client tick. */
public final class SableElevatorPositionSync {
    private static final double LEAD_DISTANCE_SQUARED = 4 * 4;
    private static WeakReference<LocalPlayer> nearbyRider = new WeakReference<>(null);
    private static WeakReference<AbstractContraptionEntity> nearbyCabin = new WeakReference<>(null);
    private static int nearbyTick;
    private static LocalPlayer pendingSender;
    private static Operation<Void> pendingSend;
    private static int pendingTick;

    private SableElevatorPositionSync() {}

    private static boolean nearCabin(LocalPlayer player, int maxAge) {
        int age = player.tickCount - nearbyTick;
        if (nearbyRider.get() != player || age < 0 || age > maxAge) return false;
        var cabin = nearbyCabin.get();
        return cabin != null && !cabin.isRemoved() && cabin.level() == player.level();
    }

    public static void observeCabin(LocalPlayer player, AbstractContraptionEntity cabin) {
        if (nearCabin(player, 0)) return;
        SubLevel carrier = Sable.HELPER.getContaining(cabin);
        if (carrier == null || carrier.isRemoved()) return;
        Vec3 plotFeet = carrier.logicalPose().transformPositionInverse(player.position());
        // Start before the rider crosses the seam, while the packet phases still agree.
        if (cabin.getBoundingBox().distanceToSqr(plotFeet) <= LEAD_DISTANCE_SQUARED) {
            if (nearbyRider.get() != player) nearbyRider = new WeakReference<>(player);
            if (nearbyCabin.get() != cabin) nearbyCabin = new WeakReference<>(cabin);
            nearbyTick = player.tickCount;
        }
    }

    public static void sendPosition(LocalPlayer player, Operation<Void> send) {
        if (!nearCabin(player, 1)) {
            send.call(player);
            return;
        }
        pendingSender = player;
        pendingTick = player.tickCount;
        pendingSend = send;
    }

    public static void finishPositionSend() {
        LocalPlayer player = pendingSender;
        if (player == null) return;
        Operation<Void> send = pendingSend;
        pendingSender = null;
        pendingSend = null;
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == player && minecraft.level == player.level() && !player.isRemoved()
                && player.tickCount == pendingTick && !player.isPassenger()) send.call(player);
    }
}
