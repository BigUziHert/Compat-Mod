package dev.caleb.emfattachments.test.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.ArmVisibilityMatrixProbe;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(targets="io.wispforest.accessories.client.AccessoriesRenderLayer",remap=false)
public abstract class ArmVisibilityAccessoryProbeMixin {
    @WrapOperation(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at=@At(value="INVOKE",target="Lio/wispforest/accessories/api/client/AccessoryRenderer;render(Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/client/renderer/MultiBufferSource;IFFFFFF)V"))
    private void emfattachments$matrixAccessory(AccessoryRenderer renderer,ItemStack item,SlotReference ref,PoseStack poses,EntityModel<?> model,MultiBufferSource buffers,int light,
            float swing,float amount,float partial,float age,float yaw,float pitch,Operation<Void> original){
        try(var scope=ArmVisibilityMatrixProbe.accessory(BuiltInRegistries.ITEM.getKey(item.getItem()).toString())){original.call(renderer,item,ref,poses,model,buffers,light,swing,amount,partial,age,yaw,pitch);}
    }
}
