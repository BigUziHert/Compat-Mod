package dev.caleb.emfattachments.client;

import com.aetherteam.aether.client.renderer.accessory.GlovesRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import io.wispforest.accessories.Accessories;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.client.AccessoriesRendererRegistry;
import io.wispforest.accessories.api.slot.SlotReference;
import io.wispforest.accessories.client.AccessoryRendererErrorCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import traben.entity_model_features.mod_compat.IrisShadowPassDetection;

/** PlayerAnimator filters out the Accessories layer during its native first-person attacks. */
public final class AetherNativeAttackGloves {
    private static final ThreadLocal<Visibility> VISIBILITY = new ThreadLocal<>();

    private AetherNativeAttackGloves() {}

    public static void render(LivingEntity entity, EntityModel<?> suppliedModel, PoseStack poses,
            MultiBufferSource buffers, int light, float partial) {
        if (!FirstPersonMode.isFirstPersonPass()
                || !(entity instanceof AbstractClientPlayer player)
                || !(suppliedModel instanceof PlayerModel<?> model)) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread() || player != minecraft.player
                || minecraft.getCameraEntity() != player
                || !minecraft.options.getCameraType().isFirstPerson()
                || IrisShadowPassDetection.getInstance().inShadowPass()) return;

        // Read the same mirrored native configuration as PlayerAnimator's
        // hideBonesInFirstPerson hook. A model/EMF visibility reset must never
        // turn a deliberately hidden native arm's glove back on.
        var nativeVisibility = ((IAnimatedPlayer) player).playerAnimator_getAnimation().getFirstPersonConfiguration();
        boolean left = model.leftArm.visible && nativeVisibility.isShowLeftArm();
        boolean right = model.rightArm.visible && nativeVisibility.isShowRightArm();
        if (!left && !right) return;
        var capability = AccessoriesCapability.get(player);
        if (capability == null) return;
        withVisibility(left, right, () -> {
            for (var container : capability.getContainers().values()) {
                for (int index = 0; index < container.getSize(); index++) {
                    var stack = container.getAccessories().getItem(index);
                    var cosmetic = container.getCosmeticAccessories().getItem(index);
                    if (!cosmetic.isEmpty() && Accessories.config().clientOptions.showCosmeticAccessories()) {
                        stack = cosmetic;
                    }
                    if (stack.isEmpty()) continue;
                    var renderer = AccessoriesRendererRegistry.getRenderer(stack);
                    // Only the native Aether renderer is verified here. Custom overrides,
                    // nested bundles, and other accessory families keep their own paths.
                    if (renderer == null || renderer.getClass() != GlovesRenderer.class
                            || !renderer.shouldRender(container.shouldRender(index))) continue;
                    var reference = SlotReference.of(player, container.getSlotName(), index);
                    if (!(left && renderer.shouldRenderInFirstPerson(HumanoidArm.LEFT, stack, reference))
                            && !(right && renderer.shouldRenderInFirstPerson(HumanoidArm.RIGHT, stack, reference))) continue;
                    poses.pushPose();
                    try {
                        renderer.render(stack, reference, poses, model, buffers, light, 0, 0, partial, 0, 0, 0);
                    } catch (Throwable error) {
                        AccessoryRendererErrorCache.logIfTimeAllotted(player, stack, renderer, error);
                    } finally {
                        poses.popPose();
                    }
                }
            }
        });
    }

    static void withVisibility(boolean left, boolean right, Runnable draw) {
        Visibility previous = VISIBILITY.get();
        VISIBILITY.set(new Visibility(left, right));
        try {
            draw.run();
        } finally {
            if (previous == null) VISIBILITY.remove();
            else VISIBILITY.set(previous);
        }
    }

    public static boolean hasVisibilityOverride() {
        return VISIBILITY.get() != null;
    }

    /** Native glove geometry/trim/glint uses exactly the arm visibility of this player pass. */
    public static void renderVisibleArms(HumanoidModel<?> gloves, Runnable draw) {
        Visibility source = VISIBILITY.get();
        if (source == null) {
            draw.run();
            return;
        }
        boolean left = gloves.leftArm.visible, right = gloves.rightArm.visible;
        gloves.leftArm.visible = source.left;
        gloves.rightArm.visible = source.right;
        try {
            draw.run();
        } finally {
            gloves.leftArm.visible = left;
            gloves.rightArm.visible = right;
        }
    }

    private record Visibility(boolean left, boolean right) {}
}
