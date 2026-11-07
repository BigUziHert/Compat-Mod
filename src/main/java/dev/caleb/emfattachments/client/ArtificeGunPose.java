package dev.caleb.emfattachments.client;

import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;

/** A single native gun pose, retained only across one EMF animation evaluation. */
public final class ArtificeGunPose {
    private final ModelPart body;
    private final float bodyX, bodyY, bodyZ;
    private final Arm left, right;

    private ArtificeGunPose(ModelPart body, Arm left, Arm right) {
        this.body = body;
        bodyX = body.x;
        bodyY = body.y;
        bodyZ = body.z;
        this.left = left;
        this.right = right;
    }

    public static ArtificeGunPose capture(Map<String, ? extends ModelPart> parts,
                                         boolean leftArm, boolean rightArm) {
        ModelPart body = parts.get("body");
        if (body == null || (!leftArm && !rightArm)) return null;
        return new ArtificeGunPose(body,
                leftArm ? Arm.capture(parts.get("left_arm"), parts.get("left_sleeve"), "left_sleeve") : null,
                rightArm ? Arm.capture(parts.get("right_arm"), parts.get("right_sleeve"), "right_sleeve") : null);
    }

    public void restore() {
        // Fresh Player moves the torso while walking/running. Keep the native grip
        // and reload pose, but carry the shoulders with that torso translation.
        float dx = body.x - bodyX;
        float dy = body.y - bodyY;
        float dz = body.z - bodyZ;
        if (left != null) left.restore(dx, dy, dz);
        if (right != null) right.restore(dx, dy, dz);
    }

    /** Keep the opposite arm's existing item or movement pose during a six-shooter reload. */
    public static void reloadWithFreeArm(ModelPart freeArm, Runnable nativeReload) {
        // Iron's charge animation writes only pitch and yaw on its support arm.
        float pitch = freeArm.xRot, yaw = freeArm.yRot;
        try {
            nativeReload.run();
        } finally {
            freeArm.xRot = pitch;
            freeArm.yRot = yaw;
        }
    }

    /** Select the physical gun arms, including the support arm for two-handed poses. */
    public static int selectedArms(boolean leftGun, boolean rightGun, boolean twoHanded) {
        if (!leftGun && !rightGun) return 0;
        return twoHanded ? 3 : (leftGun ? 1 : 0) | (rightGun ? 2 : 0);
    }

    private static final class Arm {
        private final ModelPart part, sleeve;
        private final float x, y, z, xRot, yRot, zRot, xScale, yScale, zScale;

        private Arm(ModelPart part, ModelPart sleeve) {
            this.part = part;
            this.sleeve = sleeve;
            x = part.x;
            y = part.y;
            z = part.z;
            xRot = part.xRot;
            yRot = part.yRot;
            zRot = part.zRot;
            xScale = part.xScale;
            yScale = part.yScale;
            zScale = part.zScale;
        }

        static Arm capture(ModelPart part, ModelPart sleeve, String sleeveName) {
            if (part == null) return null;
            // Nested sleeves already inherit the arm transform. Copy only the
            // ordinary sibling sleeve, as vanilla PlayerModel.setupAnim does.
            return new Arm(part, part.hasChild(sleeveName) ? null : sleeve);
        }

        void restore(float dx, float dy, float dz) {
            apply(part, dx, dy, dz);
            if (sleeve != null) apply(sleeve, dx, dy, dz);
        }

        private void apply(ModelPart target, float dx, float dy, float dz) {
            target.setPos(x + dx, y + dy, z + dz);
            target.setRotation(xRot, yRot, zRot);
            target.xScale = xScale;
            target.yScale = yScale;
            target.zScale = zScale;
            // Keep EMF/FirstPerson's current visibility and custom child geometry.
        }
    }
}
