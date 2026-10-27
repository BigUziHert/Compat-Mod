package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.test.WardenHelmetProbe;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityModelSet.class)
public abstract class WardenModelBakeProbeMixin {
    @Inject(method="bakeLayer",at=@At("HEAD"))
    private void emfattachments$countHelmetBake(ModelLayerLocation location, CallbackInfoReturnable<ModelPart> cir) {
        if (WardenHelmetProbe.active && location.equals(com.kyanite.deeperdarker.client.render.WardenHelmetRenderer.MODEL)) WardenHelmetProbe.bakes++;
    }
}
