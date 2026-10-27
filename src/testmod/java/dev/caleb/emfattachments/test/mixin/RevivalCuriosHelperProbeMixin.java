package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.RevivalFaceRenderProbe;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets="dev.leo.sableplayerragdoll.neoforge.client.CuriosRenderHelper",remap=false)
public abstract class RevivalCuriosHelperProbeMixin {
    @WrapMethod(method="renderFromStored")
    private static void stored(BodyPart part,Map<String,List<ItemStack>> items,LivingEntity entity,RenderLayerParent<?,?> parent,PoseStack pose,MultiBufferSource buffers,int light,float partial,Operation<Void> original){
        RevivalFaceRenderProbe.stored("curios/stored/"+part,items,entity);
        original.call(part,items,entity,parent,pose,RevivalFaceRenderProbe.buffers("curios/stored/"+part,buffers),light,partial);
    }
    @WrapMethod(method="render")
    private static void live(BodyPart part,LivingEntity entity,RenderLayerParent<?,?> parent,PoseStack pose,MultiBufferSource buffers,int light,float partial,Operation<Void> original){
        original.call(part,entity,parent,pose,RevivalFaceRenderProbe.buffers("curios/live/"+part,buffers),light,partial);
    }
}
