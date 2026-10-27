package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.test.NativeFirstPersonCombatControl;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import java.util.function.Function;
import com.llamalad7.mixinextras.sugar.Local;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.util.Vec3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=AdjustmentModifier.class,remap=false)
public abstract class BowVisibilityNativeAimControlMixin {
    @WrapOperation(method="get3DTransform",at=@At(value="INVOKE",target="Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"),require=1)
    private Object emfattachments$exactNativeAim(Function<?,?> selector,Object part,Operation<Object> original) {
        return NativeFirstPersonCombatControl.nativeAdjustment(this,(String)part,()->original.call(selector,part));
    }

    @WrapOperation(method="get3DTransform",at=@At(value="INVOKE",target="Ldev/kosmx/playerAnim/api/layered/modifier/AdjustmentModifier;transformVector(Ldev/kosmx/playerAnim/core/util/Vec3f;Ldev/kosmx/playerAnim/api/TransformType;Ldev/kosmx/playerAnim/api/layered/modifier/AdjustmentModifier$PartModifier;F)Ldev/kosmx/playerAnim/core/util/Vec3f;"),require=1)
    private Vec3f emfattachments$composeAim(AdjustmentModifier modifier,Vec3f vector,TransformType type,AdjustmentModifier.PartModifier adjustment,float fade,Operation<Vec3f> original,@Local(argsOnly=true) String part) {
        return NativeFirstPersonCombatControl.composeRotation(modifier,part,vector,type,adjustment,fade,()->original.call(modifier,vector,type,adjustment,fade));
    }
}
