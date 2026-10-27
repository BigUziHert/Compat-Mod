package dev.caleb.emfattachments.client;

import dev.tr7zw.firstperson.api.FirstPersonAPI;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

/** The local player's main-camera body pass, never another entity or a shadow. */
public final class BodyRenderScope {
    private BodyRenderScope() {}

    public static boolean isLocalBody(LivingEntity entity) {
        return isLocalFirstPersonCamera(entity) && FirstPersonAPI.isRenderingPlayer()
                && FirstPersonModelCore.instance.isRenderingPlayerRaw()
                && !FirstPersonModelCore.cameraEntityExtract;
    }

    /** Camera scope for integrations that choose a mode before the body pass. */
    public static boolean isLocalFirstPersonCamera(LivingEntity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread() || entity == null
                || entity != minecraft.player || minecraft.getCameraEntity() != entity
                || !minecraft.options.getCameraType().isFirstPerson()
                || !FirstPersonAPI.isEnabled()) return false;
        var camera = minecraft.gameRenderer.getMainCamera();
        if (camera.isDetached() || camera.getEntity() != entity) return false;
        return !ModList.get().isLoaded("iris") || !IrisShadowPass.isActive();
    }

    // Isolate this optional API reference so a client without Iris never links it.
    private static final class IrisShadowPass {
        private static boolean isActive() {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();
        }
    }
}
