package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import dev.caleb.emfattachments.config.CompatConfig;
import dev.tr7zw.notenoughanimations.access.PlayerData;
import dev.tr7zw.notenoughanimations.animations.hands.CustomBowAnimation;
import dev.tr7zw.notenoughanimations.versionless.animations.BodyPart;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.UseAnim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve the native bow draw on the local FirstPerson body without changing NEA settings. */
@Mixin(value = CustomBowAnimation.class, remap = false)
public abstract class FirstPersonNeaBowPoseMixin {
    @Inject(method = "apply(Lnet/minecraft/client/player/AbstractClientPlayer;Ldev/tr7zw/notenoughanimations/access/PlayerData;Lnet/minecraft/client/model/PlayerModel;Ldev/tr7zw/notenoughanimations/versionless/animations/BodyPart;FF)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void emfattachments$nativeLocalBowDraw(AbstractClientPlayer entity, PlayerData data,
                                                   PlayerModel<?> model, BodyPart part,
                                                   float delta, float tickCounter, CallbackInfo ci) {
        if (!CompatConfig.firstPersonBowPose() || !FirstPersonRenderPolicy.isLocalBody(entity)
                || !entity.isUsingItem() || entity.getUseItem().getUseAnimation() != UseAnim.BOW) return;
        // Vanilla setupAnim has already applied BOW_AND_ARROW. NEA's own
        // VanillaTwoHandedAnimation.apply is empty: retaining that pose here
        // follows the native draw rather than introducing arm translations.
        // The actual selected CustomBowAnimation remains visible to the EMF
        // bridge, which owns preservation, smoothing and pose cleanup.
        ci.cancel();
    }
}
