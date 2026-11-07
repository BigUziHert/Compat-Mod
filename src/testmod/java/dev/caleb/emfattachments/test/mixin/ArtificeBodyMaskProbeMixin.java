package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.client.FirstPersonArmGeometry;
import dev.caleb.emfattachments.test.ArtificeBodyMaskProbe;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=FirstPersonArmGeometry.class,remap=false)
public abstract class ArtificeBodyMaskProbeMixin{
    @Inject(method="open",at=@At("HEAD"),require=1)
    private static void openBefore(LivingEntity entity,EntityModel<?> model,float partial,CallbackInfoReturnable<FirstPersonArmGeometry.Scope> cir){ArtificeBodyMaskProbe.open("before",entity,model);}
    @Inject(method="open",at=@At("RETURN"),require=1)
    private static void openAfter(LivingEntity entity,EntityModel<?> model,float partial,CallbackInfoReturnable<FirstPersonArmGeometry.Scope> cir){ArtificeBodyMaskProbe.open("after",entity,model);}
    @Inject(method="copied",at=@At("HEAD"),require=1)
    private static void copyBefore(ModelPart source,ModelPart target,CallbackInfo ci){ArtificeBodyMaskProbe.copied("before",source,target);}
    @Inject(method="copied",at=@At("RETURN"),require=1)
    private static void copyAfter(ModelPart source,ModelPart target,CallbackInfo ci){ArtificeBodyMaskProbe.copied("after",source,target);}
    @Inject(method="hides",at=@At("RETURN"),require=1)
    private static void hides(ModelPart part,CallbackInfoReturnable<Boolean> cir){ArtificeBodyMaskProbe.hides(part,cir.getReturnValue());}
}
