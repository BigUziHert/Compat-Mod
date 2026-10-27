package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.caleb.emfattachments.test.BowVisibilityControls;
import dev.tr7zw.firstperson.LogicHandler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=LogicHandler.class,remap=false,priority=500)
public abstract class BowVisibilityOffsetControlMixin {
    @ModifyExpressionValue(method="updatePositionOffset",at=@At(value="NEW",target="(DDD)Lnet/minecraft/world/phys/Vec3;"),require=1)
    private Vec3 emfattachments$diagnosticOffset(Vec3 original) { return BowVisibilityControls.originalOffset(original); }
}
