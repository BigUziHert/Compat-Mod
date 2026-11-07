package dev.caleb.emfattachments.test.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.ArtificeRagdollRenderProbe;
import dev.caleb.emfattachments.test.ArtificeBodyGeometryProbe;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(targets="io.wispforest.accessories.client.AccessoriesRenderLayer",remap=false)
public abstract class ArtificeWearableProbeMixin{
    @WrapOperation(method="render",at=@At(value="INVOKE",target="Lio/wispforest/accessories/api/client/AccessoryRenderer;render(Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"),require=1)
    private void observe(AccessoryRenderer renderer,ItemStack stack,SlotReference ref,PoseStack poses,EntityModel<?> model,MultiBufferSource buffers,int light,float walk,float amount,float partial,float age,float yaw,float pitch,Operation<Void> original){
        String path="normal/"+ref.slotName()+"/"+ref.slot()+"/"+BuiltInRegistries.ITEM.getKey(stack.getItem());
        boolean local=ArtificeRagdollRenderProbe.local(ref.entity().getUUID());
        String previous=local?ArtificeBodyGeometryProbe.accessory(path):null;
        try{original.call(renderer,stack,ref,poses,model,local?ArtificeRagdollRenderProbe.buffers(path,buffers):buffers,light,walk,amount,partial,age,yaw,pitch);}
        finally{if(local)ArtificeBodyGeometryProbe.accessory(previous);}
    }
}
