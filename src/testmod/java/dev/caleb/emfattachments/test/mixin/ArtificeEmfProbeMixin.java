package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.caleb.emfattachments.test.ArtificeRenderProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import traben.entity_model_features.EMFManager;
import traben.entity_model_features.models.animation.state.EMFState;
import traben.entity_model_features.models.parts.EMFModelPartRoot;

@Mixin(value=EMFModelPartRoot.class,remap=false)
public abstract class ArtificeEmfProbeMixin {
    @Shadow private long lastMobCountAnimatedOn;
    @WrapMethod(method={"animate","animateNoPause"})
    private void emfattachments$observePose(Operation<Void> original){
        var root=(EMFModelPartRoot)(Object)this;var state=EMFState.state();
        boolean observe=lastMobCountAnimatedOn!=EMFManager.getInstance().entityRenderCount&&(root.isMainModel||EMFState.isMainPhase)
                &&state!=null&&!state.isFirstPersonHand()&&(Object)state.emfEntity()==Minecraft.getInstance().player;
        try(var probe=observe?ArtificeRenderProbe.pose(root.getAllVanillaPartsByNameEMF()):null){original.call();}
    }
}
