package dev.caleb.emfattachments.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrossbowChargePoseTest {
    @Test void loadingPrimaryArmTracksNativeHoldingAimWithoutCrouchDrop() {
        for (float head : new float[]{-(float) Math.PI / 2F, 0F, 0.9599311F, (float) Math.PI / 2F}) {
            for (boolean crouch : new boolean[]{false, true}) {
                for (float bob : new float[]{-0.05F, 0F, 0.05F}) {
                    float nativeLoading = -0.97079635F + (crouch ? 0.4F : 0F) + bob;
                    float nativeHoldingAim = -(float) Math.PI / 2F + head + 0.1F + bob;
                    assertEquals(nativeHoldingAim,
                            CrossbowChargePose.firstPersonPitch(nativeLoading, head, crouch, false, true, 0.5F), 1e-6F);
                }
            }
        }
    }

    @Test void supportPitchTracksNativeProgressWithoutPassingBackwardThroughCamera() {
        float previous = -(float) Math.PI / 2F + 0.1F;
        for (int step = 0; step <= 25; step++) {
            float primary = -0.97079635F;
            float progress = step / 25F;
            float support = primary + (-(float) Math.PI / 2F - primary) * progress;
            for (boolean crouch : new boolean[]{false, true}) {
                float bias = crouch ? 0.4F : 0F;
                float target = CrossbowChargePose.firstPersonPitch(support + bias, 0F, crouch, false, false, progress);
                assertTrue(target >= -1.500001F && target <= -1.470795F);
                assertTrue(target <= previous + 1e-6F && previous - target < 0.002F);
                assertEquals(target + 0.03F, CrossbowChargePose.firstPersonPitch(support + bias + 0.03F,
                        0F, crouch, false, false, progress), 1e-6F, "Keep native bobbing residual");
                if (step == 25) assertEquals(-1.5F, target, 1e-6F, "Finish at native support holding pitch");
                if (crouch) previous = target;
            }
        }
    }

    @Test void invalidAimCannotIntroduceNonfiniteModelRotation() {
        for (float invalid : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY})
            assertEquals(-1F, CrossbowChargePose.firstPersonPitch(-1F, invalid, false, false, true, 0.5F), 0F);
    }

    @Test void selectedNativeClampKeepsItsOwnBoundsAfterAimCorrection() {
        for (float head : new float[]{-(float) Math.PI / 2F, 0F, (float) Math.PI / 2F}) {
            for (boolean crouch : new boolean[]{false, true}) {
                float result = CrossbowChargePose.firstPersonPitch(-0.97079635F + (crouch ? 0.4F : 0F),
                        head, crouch, true, true, 0.5F);
                assertTrue(result >= -1.75F && result <= 0F);
            }
        }
        assertEquals(CrossbowChargePose.firstPersonPitch(-0.97079635F, 0F, false, false, true, 0.5F),
                CrossbowChargePose.firstPersonPitch(-0.97079635F, 0F, false, true, true, 0.5F), 0F);
    }

    @Test void nativeHandednessChoosesExactlyOneHoldingArmAndMirrorsOffhand() {
        for (boolean mainHand : new boolean[]{false, true}) {
            for (boolean rightDominant : new boolean[]{false, true}) {
                boolean right = CrossbowChargePose.isHoldingArm(true, rightDominant, mainHand);
                boolean left = CrossbowChargePose.isHoldingArm(false, rightDominant, mainHand);
                assertNotEquals(right, left);
                assertEquals(rightDominant == mainHand, right);
                assertEquals(!right, CrossbowChargePose.isHoldingArm(true, rightDominant, !mainHand));
            }
        }
    }
}
