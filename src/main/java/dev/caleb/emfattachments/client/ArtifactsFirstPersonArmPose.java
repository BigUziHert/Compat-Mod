package dev.caleb.emfattachments.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.HumanoidArm;
import traben.entity_model_features.mod_compat.IrisShadowPassDetection;
import traben.entity_model_features.models.animation.state.EMFState;
import traben.entity_model_features.models.parts.EMFModelPart;

/** Uses the arm just drawn by EMF, without animating it again or retaining it across frames. */
public final class ArtifactsFirstPersonArmPose {
    private static final ThreadLocal<Pose> SOURCE = new ThreadLocal<>();

    private ArtifactsFirstPersonArmPose() {}

    public static void withSource(AbstractClientPlayer player, HumanoidArm side,
                                  EntityModel<?> suppliedModel, Runnable draw) {
        withPose(currentArm(player, side, suppliedModel), draw);
    }

    private static ModelPart currentArm(AbstractClientPlayer player, HumanoidArm side,
                                        EntityModel<?> suppliedModel) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread() || player == null || side == null
                || player != minecraft.player || minecraft.getCameraEntity() != player
                || !minecraft.options.getCameraType().isFirstPerson()
                || !(suppliedModel instanceof PlayerModel<?> model)) return null;
        var camera = minecraft.gameRenderer.getMainCamera();
        if (camera.isDetached() || camera.getEntity() != player
                || IrisShadowPassDetection.getInstance().inShadowPass()) return null;
        var state = EMFState.state();
        if (state == null || !state.isFirstPersonHand() || (Object) state.emfEntity() != player) return null;
        ModelPart arm = side == HumanoidArm.LEFT ? model.leftArm : model.rightArm;
        return arm instanceof EMFModelPart emf && emf.getRoot().hasAnimation() ? arm : null;
    }

    // An invalid nested invocation deliberately masks an outer source. The
    // snapshot is from the supplied render model, never the dispatcher cache.
    static void withPose(ModelPart source, Runnable draw) {
        Pose previous = SOURCE.get();
        if (source == null) SOURCE.remove();
        else SOURCE.set(Pose.capture(source));
        try {
            draw.run();
        } finally {
            if (previous == null) SOURCE.remove();
            else SOURCE.set(previous);
        }
    }

    /** Called after Artifacts resets its shared glove model, around base and glow draws together. */
    public static void render(ModelPart target, Runnable draw) {
        Pose source = SOURCE.get();
        if (source == null) {
            draw.run();
            return;
        }
        Pose original = Pose.capture(target);
        source.apply(target);
        try {
            draw.run();
        } finally {
            original.apply(target);
        }
    }

    // Do not copy visibility, skipDraw, children, or copyFrom's other integration
    // side effects. Artifacts still owns which glove/overlay is drawn.
    private record Pose(float x, float y, float z, float xRot, float yRot, float zRot,
                        float xScale, float yScale, float zScale) {
        static Pose capture(ModelPart part) {
            return new Pose(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                    part.xScale, part.yScale, part.zScale);
        }

        void apply(ModelPart part) {
            part.x = x; part.y = y; part.z = z;
            part.xRot = xRot; part.yRot = yRot; part.zRot = zRot;
            part.xScale = xScale; part.yScale = yScale; part.zScale = zScale;
        }
    }
}
