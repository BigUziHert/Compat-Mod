package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionHandler;
import dev.caleb.emfattachments.test.SableElevatorPhaseProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ContraptionHandler.class, remap = false)
public abstract class SableElevatorPhaseColliderMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/ContraptionCollider;collideEntities(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;)V"), require = 1)
    private static void emfattachments$observeCollider(AbstractContraptionEntity cabin, Operation<Void> original) {
        SableElevatorPhaseProbe.collider("beforeCreate", cabin);
        original.call(cabin);
        SableElevatorPhaseProbe.collider("afterCreate", cabin);
    }
}
