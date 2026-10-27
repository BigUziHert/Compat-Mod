package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.caleb.emfattachments.client.FirstPersonPoseStates;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import strm.emfcompat.core.PoseInterpolator;

/** Keep the bridge's easing and cleanup, with separate state for the local body pass. */
@Mixin(value = PoseInterpolator.class, remap = false)
public abstract class FirstPersonPoseInterpolatorMixin {
    @ModifyExpressionValue(method = {
            "claim(Ljava/util/UUID;)Z",
            "isActive(Ljava/util/UUID;)Z",
            "blend(Ljava/util/UUID;Ljava/lang/String;Lnet/minecraft/client/model/geom/ModelPart;Lstrm/emfcompat/core/PoseSnapshot;FFFZFFF)V",
            "fadeReleased(Ljava/util/UUID;Ljava/util/Map;)V",
            "copyBlended(Ljava/util/UUID;Ljava/lang/String;Lnet/minecraft/client/model/geom/ModelPart;)V"
    }, at = @At(value = "FIELD", target = "Lstrm/emfcompat/core/PoseInterpolator;STATES:Ljava/util/Map;", opcode = Opcodes.GETSTATIC),
            require = 8, allow = 8)
    private static Map<UUID, Map<String, ?>> emfattachments$bodyHistory(
            Map<UUID, Map<String, ?>> ordinaryStates, @Local(argsOnly = true) UUID uuid) {
        return FirstPersonPoseStates.select(ordinaryStates, uuid);
    }

    @Inject(method = "retainOnly(Ljava/util/Collection;)V", at = @At("RETURN"), require = 1)
    private static void emfattachments$pruneBodyHistory(Collection<UUID> activeEntities, CallbackInfo ci) {
        FirstPersonPoseStates.retainOnly(activeEntities);
    }
}
