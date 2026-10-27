package dev.caleb.emfattachments.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.leo.sableplayerragdoll.entity.RagdollSeatEntity;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollCameraHelper;
import dev.leo.sableplayerragdoll.neoforge.config.RagdollClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;

/** Native ragdoll seats move the source entity independently of the first-person camera. */
public final class RagdollBlockOverlayOrigin {
    private RagdollBlockOverlayOrigin() {}

    public static boolean applies(Player player) {
        if(!RenderSystem.isOnRenderThread()) return false;
        var mc=Minecraft.getInstance(); var camera=mc.gameRenderer.getMainCamera();
        return player!=null && player==mc.player && mc.getCameraEntity()==player && camera.getEntity()==player
                && mc.options.getCameraType().isFirstPerson() && !camera.isDetached()
                && player.getVehicle() instanceof RagdollSeatEntity
                && RagdollClientConfig.useFirstPersonCamera() && RagdollCameraHelper.isFirstPersonAligned();
    }

    /** Retains the native eight-corner sampling and predicates; only the origin changes. */
    public static Pair<BlockState,BlockPos> atCamera(Player player) {
        Vec3 center=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        var position=new BlockPos.MutableBlockPos();
        for(int i=0;i<8;i++) {
            double x=center.x+(((i>>0)%2-.5F)*player.getBbWidth()*.8F);
            double y=center.y+(((i>>1)%2-.5F)*.1F*player.getScale());
            double z=center.z+(((i>>2)%2-.5F)*player.getBbWidth()*.8F);
            position.set(x,y,z); BlockState state=player.level().getBlockState(position);
            if(state.getRenderShape()!=RenderShape.INVISIBLE && state.isViewBlocking(player.level(),position))
                return Pair.of(state,position.immutable());
        }
        return null;
    }
}
