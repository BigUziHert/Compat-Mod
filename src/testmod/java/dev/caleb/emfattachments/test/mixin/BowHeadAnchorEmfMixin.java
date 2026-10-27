package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.BowHeadAnchorControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import traben.entity_model_features.models.parts.EMFModelPartRoot;
import traben.entity_model_features.models.parts.EMFModelPartWithState;

@Mixin(value=EMFModelPartWithState.class,remap=false)
public abstract class BowHeadAnchorEmfMixin {
    @WrapOperation(method="render",at=@At(value="INVOKE",target="Ltraben/entity_model_features/models/parts/EMFModelPartRoot;animate()V"),require=1)
    private void emfattachments$animated(EMFModelPartRoot root,Operation<Void> original,@Local(argsOnly=true) PoseStack pose) {
        original.call(root);
        BowHeadAnchorControl.afterAnimation(root,pose);
    }
}
