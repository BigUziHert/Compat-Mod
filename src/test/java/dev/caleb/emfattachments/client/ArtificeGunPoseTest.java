package dev.caleb.emfattachments.client;

import io.redspace.irons_artifice.client.gun.GunArmPoses;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class ArtificeGunPoseTest {
    @Test
    void sixShooterReloadKeepsNativeGunHandAndExistingOppositePoseOnEitherSide() {
        for (boolean rightHanded : List.of(false, true)) {
            for (float progress : new float[]{0f, .25f, .7f, 1f}) {
                var model = parts();
                var reference = parts();
                String gunSide = rightHanded ? "right_arm" : "left_arm";
                String freeSide = rightHanded ? "left_arm" : "right_arm";
                var free = model.get(freeSide);
                free.setPos(4, 2, -1);
                free.setRotation(.43f, -.11f, .22f); // A preexisting walk or held-item pose.
                free.zScale = .9f;
                float[] initialFree = transform(free);
                GunArmPoses.animateCrossbowCharge(reference.get("right_arm"), reference.get("left_arm"),
                        1f, progress, rightHanded);

                ArtificeGunPose.reloadWithFreeArm(free, () -> GunArmPoses.animateCrossbowCharge(
                        model.get("right_arm"), model.get("left_arm"), 1f, progress, rightHanded));

                assertArrayEquals(transform(reference.get(gunSide)), transform(model.get(gunSide)));
                assertArrayEquals(initialFree, transform(free));
                assertNotEquals(reference.get(freeSide).xRot, free.xRot);
            }
        }
    }

    @Test
    void installedNativeReloadWritesOnlyTheSupportAnglesWeRestore() throws Exception {
        var nativeClass = new ClassNode();
        try (var bytes = GunArmPoses.class.getResourceAsStream("GunArmPoses.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(nativeClass, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        var charge = nativeClass.methods.stream().filter(m -> m.name.equals("animateCrossbowCharge"))
                .findFirst().orElseThrow();
        var writtenFields = new HashSet<String>();
        for (var instruction : charge.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals("net/minecraft/client/model/geom/ModelPart")) {
                writtenFields.add(field.name);
            }
        }
        assertEquals(Set.of("xRot", "yRot"), writtenFields,
                "A new native support-arm transform needs corresponding preservation during the reload");
    }

    @Test
    void rifleGripFollowsRunningTorsoWithoutReplacingBodyOrLegAnimation() {
        var parts = parts();
        ModelPart body = parts.get("body");
        body.setPos(2, 3, -4);
        ModelPart left = parts.get("left_arm"), right = parts.get("right_arm");
        left.setPos(5, 3, -2);
        left.setRotation(-1.5f, .8f, .1f);
        right.setPos(-5, 4, 2);
        right.setRotation(-1.47f, .15f, -.1f);
        right.xScale = .9f;
        float[] nativeLeft = transform(left), nativeRight = transform(right);
        var pose = ArtificeGunPose.capture(parts, true, true);

        body.setPos(1, 5, -7);
        body.setRotation(.4f, -.2f, .1f);
        parts.get("left_leg").xRot = .9f;
        parts.get("right_leg").xRot = -.9f;
        left.setPos(12, 7, 3);
        right.setPos(12, 7, 3);
        left.setRotation(.6f, .2f, .3f);
        right.setRotation(.6f, .2f, .3f);
        right.xScale = 1.4f;
        pose.restore();

        nativeLeft[0] -= 1; nativeLeft[1] += 2; nativeLeft[2] -= 3;
        nativeRight[0] -= 1; nativeRight[1] += 2; nativeRight[2] -= 3;
        assertArrayEquals(nativeLeft, transform(left));
        assertArrayEquals(nativeRight, transform(right));
        assertArrayEquals(nativeLeft, transform(parts.get("left_sleeve")));
        assertArrayEquals(nativeRight, transform(parts.get("right_sleeve")));
        assertEquals(.4f, body.xRot);
        assertEquals(.9f, parts.get("left_leg").xRot);
        assertEquals(-.9f, parts.get("right_leg").xRot);
    }

    @Test
    void pistolLeavesOtherArmAndVisibilityAlone() {
        var parts = parts();
        ModelPart right = parts.get("right_arm");
        right.xRot = -1.5f;
        var pose = ArtificeGunPose.capture(parts, false, true);
        parts.get("left_arm").xRot = 1.2f;
        parts.get("left_sleeve").xRot = 1.2f;
        right.xRot = .2f;
        right.visible = false;
        right.skipDraw = true;
        parts.get("right_sleeve").visible = false;
        pose.restore();
        assertEquals(-1.5f, right.xRot);
        assertEquals(1.2f, parts.get("left_arm").xRot);
        assertEquals(1.2f, parts.get("left_sleeve").xRot);
        assertFalse(right.visible);
        assertTrue(right.skipDraw);
        assertFalse(parts.get("right_sleeve").visible);
    }

    @Test
    void nestedSleeveKeepsItsAuthoredLocalTransform() {
        var parts = parts();
        ModelPart sleeve = parts.get("right_sleeve");
        sleeve.setPos(.25f, -.1f, .1f);
        parts.put("right_arm", new ModelPart(List.of(), Map.of("right_sleeve", sleeve)));
        parts.get("right_arm").xRot = -1.3f;
        var pose = ArtificeGunPose.capture(parts, false, true);
        parts.get("right_arm").xRot = .8f;
        pose.restore();
        assertEquals(-1.3f, parts.get("right_arm").xRot);
        assertEquals(.25f, sleeve.x);
        assertEquals(0, sleeve.xRot);
    }

    @Test
    void separateEvaluationsKeepNativeReloadProgressAndCannotLeakIntoNextPlayer() {
        var first = parts();
        var second = parts();
        first.get("right_arm").xRot = -.97f;
        first.get("left_arm").xRot = -1.1f;
        var firstPose = ArtificeGunPose.capture(first, true, true);
        second.get("right_arm").xRot = -.6f;
        var secondPose = ArtificeGunPose.capture(second, false, true);
        first.get("left_arm").xRot = 1;
        second.get("right_arm").xRot = 2;
        secondPose.restore();
        firstPose.restore();
        assertEquals(-.6f, second.get("right_arm").xRot);
        assertEquals(-1.1f, first.get("left_arm").xRot);
        first.get("left_arm").xRot = -1.55f;
        var later = ArtificeGunPose.capture(first, true, true);
        first.get("left_arm").xRot = 0;
        later.restore();
        assertEquals(-1.55f, first.get("left_arm").xRot);
    }

    @Test
    void armOwnershipIncludesMirroredPistolsAndBothReloadHands() {
        assertEquals(0, ArtificeGunPose.selectedArms(false, false, false));
        assertEquals(0, ArtificeGunPose.selectedArms(false, false, true));
        assertEquals(1, ArtificeGunPose.selectedArms(true, false, false));
        assertEquals(2, ArtificeGunPose.selectedArms(false, true, false));
        assertEquals(3, ArtificeGunPose.selectedArms(true, false, true));
        assertEquals(3, ArtificeGunPose.selectedArms(false, true, true));
        assertEquals(3, ArtificeGunPose.selectedArms(true, true, false));
        assertNull(ArtificeGunPose.capture(parts(), false, false));
        assertNull(ArtificeGunPose.capture(Map.of(), true, true));
    }

    private static Map<String, ModelPart> parts() {
        var parts = new HashMap<String, ModelPart>();
        for (String name : List.of("body", "left_arm", "right_arm", "left_sleeve", "right_sleeve",
                "left_leg", "right_leg")) parts.put(name, new ModelPart(List.of(), Map.of()));
        return parts;
    }

    private static float[] transform(ModelPart part) {
        return new float[]{part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                part.xScale, part.yScale, part.zScale};
    }
}
