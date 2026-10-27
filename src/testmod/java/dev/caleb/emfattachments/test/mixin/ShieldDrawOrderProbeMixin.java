package dev.caleb.emfattachments.test.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import dev.caleb.emfattachments.test.ShieldDrawOrderProbe;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderType.class)
public abstract class ShieldDrawOrderProbeMixin {
    @Inject(method = "draw", at = @At("HEAD"))
    private void emfattachmentsTest$actualSubmission(MeshData mesh, CallbackInfo ci) {
        ShieldDrawOrderProbe.draw((RenderType) (Object) this, mesh.drawState().vertexCount());
    }
}
