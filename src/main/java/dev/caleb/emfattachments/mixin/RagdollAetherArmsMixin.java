package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.caleb.emfattachments.client.RagdollAccessoryArmScope;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Aether and Skyjade use the same paired model for their base, trim and glint passes. */
@Mixin(targets = "com.aetherteam.aether.client.renderer.accessory.model.GlovesModel", remap = false)
public abstract class RagdollAetherArmsMixin {
    @ModifyReturnValue(method = "bodyParts", at = @At("RETURN"), require = 1)
    private Iterable<ModelPart> emfattachments$onlyPhysicalGlove(Iterable<ModelPart> nativeParts) {
        // Filtering the draw leaves every shared transform and visibility flag untouched.
        return RagdollAccessoryArmScope.visibleGloveArms((HumanoidModel<?>) (Object) this, nativeParts);
    }
}
