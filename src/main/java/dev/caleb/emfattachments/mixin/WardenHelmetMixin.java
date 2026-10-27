package dev.caleb.emfattachments.mixin;

import com.kyanite.deeperdarker.client.model.WardenHelmetModel;
import com.kyanite.deeperdarker.client.render.WardenHelmetRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the extra Warden helmet's baked EMF model for the renderer lifetime. */
@Mixin(value = WardenHelmetRenderer.class, remap = false)
public abstract class WardenHelmetMixin {
    @Unique private ModelPart emfattachments$wardenRoot;
    @Unique private WardenHelmetModel emfattachments$wardenModel;

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/EntityModelSet;bakeLayer(Lnet/minecraft/client/model/geom/ModelLayerLocation;)Lnet/minecraft/client/model/geom/ModelPart;"))
    private ModelPart emfattachments$reuseBakedHelmet(EntityModelSet models, ModelLayerLocation layer, Operation<ModelPart> original) {
        // Each renderer is rebuilt on resource reload. Keeping its baked part
        // avoids EMF's repeated-model-creation cutoff during ordinary play.
        if (emfattachments$wardenRoot == null) emfattachments$wardenRoot = original.call(models, layer);
        return emfattachments$wardenRoot;
    }

    @WrapOperation(method = "render", at = @At(value = "NEW", target = "(Lnet/minecraft/client/model/geom/ModelPart;)Lcom/kyanite/deeperdarker/client/model/WardenHelmetModel;"))
    private WardenHelmetModel emfattachments$reuseHelmetModel(ModelPart root, Operation<WardenHelmetModel> original) {
        if (emfattachments$wardenModel == null) emfattachments$wardenModel = original.call(root);
        return emfattachments$wardenModel;
    }
}
