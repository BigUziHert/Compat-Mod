package dev.caleb.emfattachments.test.mixin;

import dev.caleb.emfattachments.client.ArtificeGunPose;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only ablation leaves native gun, accessory and FirstPerson handlers intact. */
@Mixin(value = ArtificeGunPose.class, remap = false)
public abstract class OverlapArtificePoseControlMixin {
    @Inject(method = "capture", at = @At("HEAD"), cancellable = true, require = 1)
    private static void emfattachments$overlapGunBypass(Map<String, ? extends ModelPart> parts, boolean left,
            boolean right, CallbackInfoReturnable<ArtificeGunPose> ci) {
        Minecraft mc = Minecraft.getInstance();
        if (Boolean.getBoolean("emfattachments.overlapGunBypass") && mc.player != null && mc.hasSingleplayerServer()
                && mc.getUser().getName().equals("AttachmentTest")
                && mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))) ci.setReturnValue(null);
    }
}
