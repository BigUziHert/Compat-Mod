package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.caleb.emfattachments.test.SableOcclusionSequence;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientLevel.class)
public abstract class SableOcclusionTickProbeMixin {
    @WrapMethod(method = "tickNonPassenger")
    private void emfattachments$forceOwnedStaleTickResult(Entity entity, Operation<Void> original) {
        // Outside the method body: cancellable HEAD injections cannot skip this control.
        SableOcclusionSequence.beforeTick(entity);
        original.call(entity);
        SableOcclusionSequence.afterTick(entity);
    }
}
