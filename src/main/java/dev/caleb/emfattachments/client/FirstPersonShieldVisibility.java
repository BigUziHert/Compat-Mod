package dev.caleb.emfattachments.client;

import dev.tr7zw.firstperson.versionless.mixinbase.ModelPartBase;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;

/** Keeps shield shells on the same visible body parts as their first-person owner. */
public final class FirstPersonShieldVisibility {
    private FirstPersonShieldVisibility() {}

    public static void render(HumanoidModel<?> shield, EntityModel<?> parent, LivingEntity entity, Runnable draw) {
        if (parent instanceof HumanoidModel<?> humanoid && FirstPersonRenderPolicy.isLocalBody(entity)) {
            renderVisibleParts(shield, humanoid, draw);
        } else draw.run();
    }

    static void renderVisibleParts(HumanoidModel<?> shield, HumanoidModel<?> parent, Runnable draw) {
        ModelPart[] targets = parts(shield), sources = parts(parent);
        boolean[] visibility = new boolean[targets.length], addedHidden = new boolean[targets.length];
        float[] depth = new float[targets.length];
        for (int i = 0; i < targets.length; i++) {
            ModelPart target = targets[i];
            visibility[i] = target.visible;
            depth[i] = target.z;
        }
        try {
            for (int i = 0; i < targets.length; i++) {
                ModelPart source = sources[i], target = targets[i];
                boolean sourceHidden = !source.visible || (Object) source instanceof ModelPartBase hidden && hidden.isHidden();
                if (!sourceHidden) continue;
                // EMF may rewrite visible during animation. Propagate FPM's
                // independent hidden marker as well, then restore both states.
                if ((Object) target instanceof ModelPartBase hidden && !hidden.isHidden()) {
                    addedHidden[i] = true;
                    hidden.setHidden();
                }
                target.visible = false;
            }
            draw.run();
        } finally {
            for (int i = 0; i < targets.length; i++) {
                if (addedHidden[i]) ((ModelPartBase) (Object) targets[i]).showAgain();
                targets[i].z = depth[i];
                targets[i].visible = visibility[i];
            }
        }
    }

    private static ModelPart[] parts(HumanoidModel<?> model) {
        return new ModelPart[]{model.head, model.hat, model.body, model.leftArm, model.rightArm, model.leftLeg, model.rightLeg};
    }
}
