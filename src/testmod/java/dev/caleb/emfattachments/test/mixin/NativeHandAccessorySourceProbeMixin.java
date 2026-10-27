package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.NativeHandAccessoryProbe;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets="artifacts.integration.accessories.AccessoriesRenderingHandler$ArtifactAccessoryRenderer",remap=false)
public abstract class NativeHandAccessorySourceProbeMixin {
    @WrapMethod(method="renderOnFirstPerson")
    private void emfattachments$nativeHandSource(HumanoidArm side,ItemStack stack,SlotReference reference,PoseStack poses,
            EntityModel<?> model,MultiBufferSource buffers,int light,Operation<Void> original) {
        try(var scope=NativeHandAccessoryProbe.source(side,stack,reference,model)) { original.call(side,stack,reference,poses,model,buffers,light); }
    }
}
