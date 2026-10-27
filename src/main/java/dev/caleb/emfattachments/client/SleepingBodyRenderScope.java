package dev.caleb.emfattachments.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

/** Native sleeping-body world draw, independent of whether FirstPerson is installed. */
public final class SleepingBodyRenderScope {
    // Only touched by LevelRenderer on the render thread. Nested draws restore
    // their previous owner in finally; no per-frame collections are allocated.
    private static LivingEntity owner;

    private SleepingBodyRenderScope() {}

    public static LivingEntity enter(Entity entity) {
        LivingEntity previous = owner;
        owner = isSleepingCameraBody(entity) ? (LivingEntity) entity : null;
        return previous;
    }

    public static void restore(LivingEntity previous) {
        owner = previous;
    }

    public static boolean isActiveFor(LivingEntity entity) {
        return entity != null && entity == owner;
    }

    private static boolean isSleepingCameraBody(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !living.isSleeping()) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread() || entity != minecraft.player
                || minecraft.getCameraEntity() != entity
                || !minecraft.options.getCameraType().isFirstPerson()) return false;
        var camera = minecraft.gameRenderer.getMainCamera();
        if (camera.isDetached() || camera.getEntity() != entity) return false;
        if (ModList.get().isLoaded("iris") && IrisShadowPass.isActive()) return false;
        return !ModList.get().isLoaded("firstperson") || !FirstPersonExtraction.isActive();
    }

    private static final class IrisShadowPass {
        private static boolean isActive() {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();
        }
    }

    private static final class FirstPersonExtraction {
        private static boolean isActive() {
            return dev.tr7zw.firstperson.FirstPersonModelCore.cameraEntityExtract;
        }
    }
}
