package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.client.ArtificeGunPose;
import dev.caleb.emfattachments.client.ArtificeSixShooterPose;
import io.redspace.irons_artifice.gun.ArmPoseKind;
import io.redspace.irons_artifice.item.GunItem;
import io.redspace.irons_artifice.item.ReloadState;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import traben.entity_model_features.EMFManager;
import traben.entity_model_features.models.animation.state.EMFState;
import traben.entity_model_features.models.parts.EMFModelPartRoot;

/** Let the gun's selected native pose survive the later resource-pack animation. */
@Mixin(value = EMFModelPartRoot.class, remap = false)
public abstract class ArtificeEmfGunPoseMixin {
    @Shadow private long lastMobCountAnimatedOn;

    @WrapOperation(method = {"animate", "animateNoPause"},
            at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"), require = 2)
    private void emfattachments$preserveNativeGunPose(Runnable animation, Operation<Void> original) {
        EMFModelPartRoot root = (EMFModelPartRoot) (Object) this;
        ArtificeGunPose pose = null;
        var state = EMFState.state();
        // EMF invokes animate for every part and may invoke it again for layers.
        // Match its evaluation boundary so this allocates only when it will run.
        if (lastMobCountAnimatedOn != EMFManager.getInstance().entityRenderCount
                && (root.isMainModel || EMFState.isMainPhase)
                && state != null && !state.isFirstPersonHand()
                && state.emfEntity() instanceof AbstractClientPlayer player) {
            ItemStack main = player.getMainHandItem();
            ItemStack off = player.getOffhandItem();
            boolean leftMain = player.getMainArm() == HumanoidArm.LEFT;
            boolean mainGun = main.getItem() instanceof GunItem;
            boolean offGun = off.getItem() instanceof GunItem;
            int arms = ArtificeGunPose.selectedArms(leftMain ? mainGun : offGun,
                    leftMain ? offGun : mainGun,
                    emfattachments$usesBothArms(main) || emfattachments$usesBothArms(off));
            if (arms != 0) {
                pose = ArtificeGunPose.capture(root.getAllVanillaPartsByNameEMF(),
                        (arms & 1) != 0, (arms & 2) != 0);
            }
        }
        try {
            original.call(animation);
        } finally {
            // Run after every EMF hook, including existing NEA/Better Combat pose
            // restores. Do not create global pause claims or interpolate reloads.
            if (pose != null) pose.restore();
        }
    }

    @Unique
    private static boolean emfattachments$usesBothArms(ItemStack stack) {
        if (!(stack.getItem() instanceof GunItem gun) || ArtificeSixShooterPose.isSixShooter(stack)) return false;
        ReloadState reload = ReloadState.get(stack);
        return gun.getGun().armPoseKind() == ArmPoseKind.RIFLE || reload != null && !reload.isFinished();
    }
}
