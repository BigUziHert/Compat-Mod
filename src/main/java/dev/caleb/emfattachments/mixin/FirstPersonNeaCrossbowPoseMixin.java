package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.CrossbowChargePose;
import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import dev.caleb.emfattachments.config.CompatConfig;
import dev.tr7zw.notenoughanimations.access.PlayerData;
import dev.tr7zw.notenoughanimations.animations.hands.ClampCrossbowAnimations;
import dev.tr7zw.notenoughanimations.animations.vanilla.VanillaTwoHandedAnimation;
import dev.tr7zw.notenoughanimations.versionless.animations.BodyPart;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.UseAnim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the selected native loading motion in the local first-person field of view. */
@Mixin(value = {VanillaTwoHandedAnimation.class, ClampCrossbowAnimations.class}, remap = false)
public abstract class FirstPersonNeaCrossbowPoseMixin {
    @Inject(method = "apply(Lnet/minecraft/client/player/AbstractClientPlayer;Ldev/tr7zw/notenoughanimations/access/PlayerData;Lnet/minecraft/client/model/PlayerModel;Ldev/tr7zw/notenoughanimations/versionless/animations/BodyPart;FF)V",
            at = @At("RETURN"), require = 1)
    private void emfattachments$aimLocalCrossbowLoading(AbstractClientPlayer entity, PlayerData data,
                                                        PlayerModel<?> model, BodyPart part,
                                                        float delta, float tickCounter, CallbackInfo ci) {
        if (part != BodyPart.LEFT_ARM && part != BodyPart.RIGHT_ARM) return;
        if (!CompatConfig.firstPersonCrossbowPose() || !FirstPersonRenderPolicy.isLocalBody(entity)
                || !entity.isUsingItem() || entity.getUseItem().getUseAnimation() != UseAnim.CROSSBOW) return;
        int duration = CrossbowItem.getChargeDuration(entity.getUseItem(), entity);
        if (duration <= 0) return;
        var arm = part == BodyPart.LEFT_ARM ? model.leftArm : model.rightArm;
        boolean holdingArm = CrossbowChargePose.isHoldingArm(part == BodyPart.RIGHT_ARM,
                entity.getMainArm() == HumanoidArm.RIGHT, entity.getUsedItemHand() == InteractionHand.MAIN_HAND);
        arm.xRot = CrossbowChargePose.firstPersonPitch(arm.xRot, model.head.xRot, model.crouching,
                (Object) this instanceof ClampCrossbowAnimations, holdingArm,
                (float) entity.getTicksUsingItem() / duration);
        // No pivot, yaw, item or camera changes. The existing bridge saves this
        // actual selected pose and owns interpolation, sleeves and release cleanup.
    }
}
