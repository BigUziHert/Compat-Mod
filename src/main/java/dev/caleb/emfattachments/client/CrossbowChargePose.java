package dev.caleb.emfattachments.client;

/** Re-aim native loading around the same primary-arm pitch used by charged holding. */
public final class CrossbowChargePose {
    private CrossbowChargePose() {}

    // Exact AnimationUtils charge/hold constants and HumanoidModel crouch bias.
    private static final float CHARGE_MAIN_PITCH = -0.97079635F;
    private static final float CHARGE_SUPPORT_END = -(float) Math.PI / 2F;
    private static final float HOLD_MAIN_PITCH = -(float) Math.PI / 2F + 0.1F;
    private static final float HOLD_SUPPORT_PITCH = -1.5F;
    private static final float CROUCH_ARM_BIAS = 0.4F;

    public static boolean isHoldingArm(boolean rightArmPart, boolean rightMainArm, boolean mainHandUsed) {
        return rightArmPart == (rightMainArm == mainHandUsed);
    }

    public static float firstPersonPitch(float nativePitch, float headPitch, boolean crouching,
                                         boolean clampSelected, boolean holdingArm, float chargeProgress) {
        if (!Float.isFinite(nativePitch) || !Float.isFinite(headPitch) || !Float.isFinite(chargeProgress)) return nativePitch;
        float progress = Math.max(0F, Math.min(1F, chargeProgress));
        float sourcePitch = holdingArm ? CHARGE_MAIN_PITCH
                : CHARGE_MAIN_PITCH + progress * (CHARGE_SUPPORT_END - CHARGE_MAIN_PITCH);
        float targetPitch = holdingArm ? HOLD_MAIN_PITCH
                : HOLD_MAIN_PITCH + progress * (HOLD_SUPPORT_PITCH - HOLD_MAIN_PITCH);
        // Keep native bobbing and native charge timing/yaw. Rebase the support
        // pitch range onto native holding pitches instead of tipping the fully
        // pulled hand backward through the camera. No arm pivot is displaced.
        float aimed = nativePitch - sourcePitch + headPitch + targetPitch
                - (crouching ? CROUCH_ARM_BIAS : 0F);
        // Preserve the exact optional NEA clamp after changing the aim basis.
        return clampSelected ? Math.max(-1.75F, Math.min(0F, aimed)) : aimed;
    }
}
