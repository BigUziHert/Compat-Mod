package dev.caleb.emfattachments.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.redspace.irons_artifice.client.gun.FirstPersonArmsModel;
import io.wispforest.accessories.Accessories;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.client.AccessoriesRendererRegistry;
import io.wispforest.accessories.api.slot.SlotReference;
import io.wispforest.accessories.client.AccessoryRendererErrorCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;

/** Iron draws its own hands at animated gun bones, bypassing Accessories' vanilla hand hook. */
public final class ArtificeFirstPersonAccessories {
    private static final ThreadLocal<ArmPose> SOURCE = new ThreadLocal<>();
    private static PlayerModel<LivingEntity> wideModel, slimModel;

    private ArtificeFirstPersonAccessories() {}

    /** Called within Iron's native bone/skin matrix, after that arm's skin has rendered. */
    public static void render(ModelPart source, ItemDisplayContext perspective,
            PoseStack poses, MultiBufferSource buffers, int light) {
        if (perspective != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                && perspective != ItemDisplayContext.FIRST_PERSON_LEFT_HAND) return;
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || !minecraft.isSameThread() || minecraft.getCameraEntity() != player
                || !minecraft.options.getCameraType().isFirstPerson()
                || minecraft.gameRenderer.getMainCamera().isDetached()
                || !source.visible || source.skipDraw) return;
        var arms = FirstPersonArmsModel.forSkin(player.getSkin().model());
        if (arms == null || source != arms.leftArm && source != arms.rightArm) return;
        var capability = AccessoriesCapability.get(player);
        if (capability == null) return;

        boolean mirrored = perspective == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        HumanoidArm side = physicalArm(source == arms.leftArm, mirrored);
        PlayerModel<LivingEntity> model = model(player.getSkin().model() == PlayerSkin.Model.SLIM);
        ModelPart arm = side == HumanoidArm.LEFT ? model.leftArm : model.rightArm;
        withSource(source, mirrored, () -> renderModelArm(arm, () -> {
            poses.pushPose();
            try {
                // Iron mirrors its right-hand mesh for a left-hand perspective.
                // First-person accessory renderers select the physical arm's mesh instead.
                if (mirrored) poses.scale(-1, 1, 1);
                for (var container : capability.getContainers().values()) {
                    for (int index = 0; index < container.getSize(); index++) {
                        var stack = container.getAccessories().getItem(index);
                        var cosmetic = container.getCosmeticAccessories().getItem(index);
                        if (!cosmetic.isEmpty() && Accessories.config().clientOptions.showCosmeticAccessories()) {
                            stack = cosmetic;
                        }
                        if (stack.isEmpty()) continue;
                        var renderer = AccessoriesRendererRegistry.getRenderer(stack);
                        if (renderer == null || renderer.isEmpty()
                                || !renderer.shouldRender(container.shouldRender(index))) continue;
                        poses.pushPose();
                        try {
                            // Match Accessories' hand hook, including renderers that override
                            // renderOnFirstPerson without overriding shouldRenderInFirstPerson.
                            renderer.renderOnFirstPerson(side, stack,
                                    SlotReference.of(player, container.getSlotName(), index),
                                    poses, model, buffers, light);
                        } catch (Throwable error) {
                            AccessoryRendererErrorCache.logIfTimeAllotted(player, stack, renderer, error);
                        } finally {
                            poses.popPose();
                        }
                    }
                }
            } finally {
                poses.popPose();
            }
        }));
    }

    static HumanoidArm physicalArm(boolean leftBone, boolean mirrored) {
        return leftBone != mirrored ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
    }

    private static PlayerModel<LivingEntity> model(boolean slim) {
        PlayerModel<LivingEntity> model = slim ? slimModel : wideModel;
        if (model == null) {
            // A private vanilla model supplies geometry for default wrist/ring anchors.
            // Never animate, reset, or borrow the world's shared EMF player model here.
            model = new PlayerModel<>(LayerDefinition.create(
                    PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64).bakeRoot(), slim);
            model.setAllVisible(false);
            if (slim) slimModel = model;
            else wideModel = model;
        }
        return model;
    }

    static void withSource(ModelPart source, boolean mirrored, Runnable draw) {
        ArmPose previous = SOURCE.get();
        if (source == null) SOURCE.remove();
        else SOURCE.set(ArmPose.capture(source).mirrored(mirrored));
        try {
            draw.run();
        } finally {
            if (previous == null) SOURCE.remove();
            else SOURCE.set(previous);
        }
    }

    private static void renderModelArm(ModelPart target, Runnable draw) {
        boolean visible = target.visible, skipDraw = target.skipDraw;
        target.visible = true;
        target.skipDraw = false;
        try {
            renderArm(target, draw);
        } finally {
            target.visible = visible;
            target.skipDraw = skipDraw;
        }
    }

    /** Also used after Artifacts resets its glove model, preserving native base/glow/glint draws. */
    public static void renderArm(ModelPart target, Runnable draw) {
        ArmPose source = SOURCE.get();
        if (source == null) {
            draw.run();
            return;
        }
        ArmPose original = ArmPose.capture(target);
        source.apply(target);
        try {
            draw.run();
        } finally {
            original.apply(target);
        }
    }

    private record ArmPose(float x, float y, float z, float xRot, float yRot, float zRot,
                           float xScale, float yScale, float zScale) {
        static ArmPose capture(ModelPart part) {
            return new ArmPose(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                    part.xScale, part.yScale, part.zScale);
        }

        ArmPose mirrored(boolean mirror) {
            return mirror ? new ArmPose(-x, y, z, xRot, -yRot, -zRot,
                    xScale, yScale, zScale) : this;
        }

        void apply(ModelPart part) {
            part.x = x; part.y = y; part.z = z;
            part.xRot = xRot; part.yRot = yRot; part.zRot = zRot;
            part.xScale = xScale; part.yScale = yScale; part.zScale = zScale;
            // The native accessory renderer owns its visibility and child geometry.
        }
    }
}
