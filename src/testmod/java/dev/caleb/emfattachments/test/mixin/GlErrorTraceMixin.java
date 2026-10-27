package dev.caleb.emfattachments.test.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.platform.GlDebug;
import dev.caleb.emfattachments.test.GlErrorTraceProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observes the already-decoded message and returns the exact original reference. */
@Mixin(GlDebug.class)
public abstract class GlErrorTraceMixin {
    @ModifyExpressionValue(method = "printDebugLog(IIIIIJJ)V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GLDebugMessageCallback;getMessage(IJ)Ljava/lang/String;", remap = false), require = 1)
    private static String emfattachments$traceNativeError(String original, int source, int type, int id,
                                                          int severity, int length, long message, long user) {
        GlErrorTraceProbe.observe(source, type, id, severity, original);
        return original;
    }
}
