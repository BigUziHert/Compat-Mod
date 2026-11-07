package dev.caleb.emfattachments.test.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.ArtificeRagdollRenderProbe;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(targets="dev.leo.sableplayerragdoll.neoforge.client.AccessoriesRenderHelper",remap=false)
public abstract class ArtificeRagdollAccessoriesProbeMixin{
    @WrapMethod(method="renderFromStored")
    private static void stored(BodyPart part,RagdollPartBlockEntity block,LivingEntity owner,RenderLayerParent<?,?> parent,PoseStack poses,MultiBufferSource buffers,int light,float partial,Operation<Void> original){
        boolean observe=ArtificeRagdollRenderProbe.local(block.skinProfile().getId());String path="accessories/stored/"+part;
        if(observe)ArtificeRagdollRenderProbe.stored(path,block.getAccessoriesItems(),block.getAccessoriesCosmeticItems(),block.getAccessoriesRenderOptions());
        original.call(part,block,owner,parent,poses,observe?ArtificeRagdollRenderProbe.buffers(path,buffers):buffers,light,partial);
    }
    @WrapMethod(method="render")
    private static void live(BodyPart part,LivingEntity owner,RenderLayerParent<?,?> parent,PoseStack poses,MultiBufferSource buffers,int light,float partial,Operation<Void> original){
        original.call(part,owner,parent,poses,ArtificeRagdollRenderProbe.local(owner.getUUID())?ArtificeRagdollRenderProbe.buffers("accessories/live/"+part,buffers):buffers,light,partial);
    }
}
