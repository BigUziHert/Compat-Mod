package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.RagdollBlockOverlayOrigin;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ScreenEffectRenderer.class)
public abstract class RagdollBlockOverlayOriginMixin {
    // getOverlayBlock is added by NeoForge and therefore has no vanilla obfuscation mapping.
    @Inject(method="getOverlayBlock",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private static void emfattachments$nativeCameraOverlay(Player player,CallbackInfoReturnable<Pair<BlockState,BlockPos>> cir) {
        if(RagdollBlockOverlayOrigin.applies(player)) cir.setReturnValue(RagdollBlockOverlayOrigin.atCamera(player));
    }
}
