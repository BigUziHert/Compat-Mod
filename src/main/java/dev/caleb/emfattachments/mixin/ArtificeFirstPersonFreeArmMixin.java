package dev.caleb.emfattachments.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.client.FirstPersonArmGeometry;
import dev.tr7zw.firstperson.versionless.mixinbase.ModelPartBase;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the empty support arm on the body when Iron renders only its pistol arm. */
@Mixin(value = LivingEntityRenderer.class, priority = 1500)
public abstract class ArtificeFirstPersonFreeArmMixin {
    @TargetHandler(mixin = "dev.tr7zw.firstperson.mixins.LivingEntityRendererMixin", name = "renderPostAnim")
    @WrapOperation(method = "@MixinSquared:Handler", at = @At(value = "INVOKE",
            target = "Ldev/tr7zw/firstperson/versionless/mixinbase/ModelPartBase;setHidden()V", remap = false),
            require = 1)
    private void emfattachments$keepFreePistolArm(ModelPartBase part, Operation<Void> original) {
        if (!FirstPersonArmGeometry.keepsNativeBodyArm(part)) original.call(part);
    }
}
