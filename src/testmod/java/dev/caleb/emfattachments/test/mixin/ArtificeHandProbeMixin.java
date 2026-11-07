package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.ArtificeRenderProbe;
import io.redspace.irons_artifice.client.gun.GunInHandRenderer;
import io.redspace.irons_artifice.item.GunItem;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

@Mixin(value=GunInHandRenderer.class,remap=false)
public abstract class ArtificeHandProbeMixin extends GeoItemRenderer<GunItem> {
    protected ArtificeHandProbeMixin(GeoModel<GunItem> model){super(model);}
    @WrapMethod(method="renderFirstPersonHand")
    private void emfattachments$observeHand(MultiBufferSource buffers,RenderType type,ModelPart arm,PoseStack poses,int light,Operation<Void> original){
        try(var probe=ArtificeRenderProbe.hand(arm,renderPerspective)){original.call(probe==null?buffers:probe.buffers(buffers),type,arm,poses,light);}
    }
}
