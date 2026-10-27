package dev.caleb.emfattachments.test.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.NativeAetherHandProbe;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(targets="com.aetherteam.aether.client.renderer.accessory.GlovesRenderer",remap=false)
public abstract class NativeAetherHandSourceProbeMixin {
    @WrapMethod(method="render")
    private void emfattachments$aetherBody(ItemStack stack,SlotReference reference,PoseStack poses,EntityModel<?> model,MultiBufferSource buffers,int light,float limbSwing,float limbSwingAmount,float partialTicks,float ageInTicks,float netHeadYaw,float headPitch,Operation<Void> original) {
        try(var scope=NativeAetherHandProbe.bodySource(stack,reference,model,poses)) { original.call(stack,reference,poses,model,NativeAetherHandProbe.buffers(buffers),light,limbSwing,limbSwingAmount,partialTicks,ageInTicks,netHeadYaw,headPitch); }
    }
    @WrapMethod(method="renderOnFirstPerson")
    private void emfattachments$aetherSource(HumanoidArm arm,ItemStack stack,SlotReference reference,PoseStack poses,EntityModel<?> model,MultiBufferSource buffers,int light,Operation<Void> original) {
        try(var scope=NativeAetherHandProbe.source(arm,stack,reference,model,poses)) { original.call(arm,stack,reference,poses,model,NativeAetherHandProbe.buffers(buffers),light); }
    }
}
