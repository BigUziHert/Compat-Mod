package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.caleb.emfattachments.client.ArtificeGunPose;
import dev.caleb.emfattachments.client.ArtificeSixShooterPose;
import io.redspace.irons_artifice.client.gun.GunArmPoses;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = GunArmPoses.class, remap = false)
public abstract class ArtificeSixShooterReloadMixin {
    @Shadow private static ItemStack itemHeldByArm(LivingEntity entity, HumanoidArm arm) { throw new AssertionError(); }

    @WrapOperation(method = "applyPistolPose", at = @At(value = "INVOKE",
            target = "Lio/redspace/irons_artifice/client/gun/GunArmPoses;animateCrossbowCharge(Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;FFZ)V"),
            require = 1)
    private static void emfattachments$reloadSixShooterWithGunArm(ModelPart right, ModelPart left,
            float duration, float elapsed, boolean rightHanded, Operation<Void> original,
            @Local(argsOnly = true) LivingEntity entity, @Local(argsOnly = true) HumanoidArm arm) {
        if (entity instanceof Player && ArtificeSixShooterPose.isSixShooter(itemHeldByArm(entity, arm))) {
            ModelPart freeArm = rightHanded ? left : right;
            ArtificeGunPose.reloadWithFreeArm(freeArm, () -> original.call(right, left, duration, elapsed, rightHanded));
        } else {
            original.call(right, left, duration, elapsed, rightHanded);
        }
    }
}
