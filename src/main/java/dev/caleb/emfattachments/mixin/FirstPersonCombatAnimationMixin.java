package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.FirstPersonCombat;
import dev.caleb.emfattachments.compat.EmfCrouchCompatibility;
import dev.caleb.emfattachments.config.CompatConfig;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.core.impl.AnimationProcessor;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AnimationProcessor.class, remap = false)
public abstract class FirstPersonCombatAnimationMixin {
    @Shadow private float tickDelta;
    @Inject(method = "getFirstPersonMode", at = @At("HEAD"), cancellable = true, require = 1)
    private void emfattachments$oneBodyPass(CallbackInfoReturnable<FirstPersonMode> cir) {
        // PlayerAnimator otherwise forces the camera detached and renders another
        // player model when Better Combat's firstPersonAnimations setting is YES.
        if (FirstPersonCombat.ownsProcessor(this)) cir.setReturnValue(FirstPersonMode.NONE);
    }

    @Inject(method = "isFirstPersonAnimationDisabled", at = @At("HEAD"), cancellable = true, require = 1)
    private void emfattachments$consistentPassDecision(CallbackInfoReturnable<Boolean> cir) {
        if (FirstPersonCombat.ownsProcessor(this)) cir.setReturnValue(true);
    }

    @Inject(method = "get3DTransform", at = @At("RETURN"), cancellable = true, require = 1)
    private void emfattachments$cameraRelativeAttack(String part, TransformType type, Vec3f initial,
                                                     CallbackInfoReturnable<Vec3f> cir) {
        if (!FirstPersonCombat.modifiesBodyProcessor(this)) return;
        Vec3f current = cir.getReturnValue();
        if (current == null) return;
        float weight = FirstPersonCombat.correctionWeight(part, type, tickDelta);
        if (weight <= 0) return;
        var player = Minecraft.getInstance().player;
        if (part.equals("body")) {
            if (type == TransformType.ROTATION) cir.setReturnValue(new Vec3f(current.getX() * (1 - weight), current.getY(), current.getZ() * (1 - weight)));
            else if (type == TransformType.POSITION) {
                // The root channel is measured in blocks, unlike model parts.
                // Core's active crouch normalizer owns this vertical correction.
                // Keep our fallback when Core is absent or its normalizer is off.
                float crouchOffset = player.isCrouching() && !EmfCrouchCompatibility.ownsCrouchHeight()
                        ? (float) CompatConfig.combatBodyYOffset() * weight : 0;
                cir.setReturnValue(current.scale(1 - weight).add(new Vec3f(0, crouchOffset, 0)));
            } else if (type == TransformType.BEND) cir.setReturnValue(current.scale(1 - weight));
        } else if (part.equals("torso")) {
            if (type == TransformType.ROTATION) cir.setReturnValue(new Vec3f(current.getX() * (1 - weight), current.getY(), current.getZ() * (1 - weight)));
            else if (type == TransformType.BEND) cir.setReturnValue(current.scale(1 - weight));
        } else if (part.equals("rightArm") || part.equals("leftArm")
                || part.equals("right_arm") || part.equals("left_arm")) {
            float pitch = Mth.clamp(player.getXRot(), -89.8f, 89.8f) * Mth.DEG_TO_RAD;
            if (type == TransformType.ROTATION) {
                // Preserve the complete weapon keyframe; add only camera aim.
                // No mutable bias that changes with FPS or number of render passes.
                cir.setReturnValue(new Vec3f(current.getX() + weight * (pitch * (float) CompatConfig.armPitchMultiplier()
                        + (float) CompatConfig.armPitchOffset()), current.getY(), current.getZ()));
            } else if (type == TransformType.POSITION) {
                float lookUp = Math.max(0, -pitch / Mth.HALF_PI);
                cir.setReturnValue(new Vec3f(current.getX() + weight * (float) CompatConfig.armPositionX(),
                        current.getY() + weight * (float) CompatConfig.armPositionY(),
                        current.getZ() + weight * ((float) CompatConfig.armPositionZ() + lookUp * (float) CompatConfig.lookUpZShift())));
            }
        } else if (type == TransformType.ROTATION && (part.equals("rightLeg") || part.equals("leftLeg")
                || part.equals("right_leg") || part.equals("left_leg"))) {
            // Keep vanilla gait/crouch rather than the inverse leg pitch used to
            // compensate Better Combat's root-body tilt, which is removed above.
            cir.setReturnValue(current.scale(1 - weight).add(initial.scale(weight)));
        }
    }
}
