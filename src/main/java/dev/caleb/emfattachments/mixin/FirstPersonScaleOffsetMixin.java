package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.caleb.emfattachments.client.FirstPersonOffsets;
import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import dev.caleb.emfattachments.config.CompatConfig;
import dev.tr7zw.firstperson.LogicHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = LogicHandler.class, remap = false)
public abstract class FirstPersonScaleOffsetMixin {
    @ModifyExpressionValue(method = "updatePositionOffset",
            at = @At(value = "NEW", target = "(DDD)Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 emfattachments$scaleOriginalBodyDistance(Vec3 original, Entity entity, float delta) {
        var player = Minecraft.getInstance().player;
        if (entity != player
                || !FirstPersonRenderPolicy.isLocalFirstPersonCamera(player)) return original;
        // This allocation precedes FPM's PlayerOffsetHandler loop. Contributions
        // from other registered handlers are therefore never scaled a second time.
        Vec3 offset = CompatConfig.scaleBodyOffset()
                ? FirstPersonOffsets.scaleOriginalBodyOffset(original, player.getScale()) : original;
        if (player.isVisuallySwimming() || player.isFallFlying() || player.isSleeping()) return offset;
        if (ModList.get().isLoaded("sable_player_ragdoll") && RagdollState.active(player)) return offset;
        if (ModList.get().isLoaded("combat_roll") && RollState.active(player)) return offset;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return FirstPersonOffsets.addDownwardClearance(offset, camera.getXRot(), camera.getYRot(),
                player.getScale(), CompatConfig.downwardBodyClearance());
    }

    private static final class RagdollState {
        static boolean active(Entity player) {
            return player.getVehicle() instanceof dev.leo.sableplayerragdoll.entity.RagdollSeatEntity;
        }
    }

    private static final class RollState {
        static boolean active(Entity player) {
            return player instanceof net.combat_roll.internals.RollingEntity rolling
                    && rolling.getRollManager().isRolling();
        }
    }
}
