package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.caleb.emfattachments.test.NativeWorldReadiness;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Records an actual native invocation and normal return; no replacement render or cancellation. */
@Mixin(GameRenderer.class)
public abstract class NativeWorldReadinessMixin {
    @WrapOperation(method="renderLevel", at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"), require=1)
    private void emfattachments$completedNativeWorld(LevelRenderer renderer, DeltaTracker delta, boolean outline,
            Camera camera, GameRenderer gameRenderer, LightTexture light, Matrix4f frustum, Matrix4f projection,
            Operation<Void> original) {
        NativeWorldReadiness.Invocation token = NativeWorldReadiness.enter(camera);
        try {
            original.call(renderer, delta, outline, camera, gameRenderer, light, frustum, projection);
            token.returnedNormally();
        } finally {
            token.close();
        }
    }
}
