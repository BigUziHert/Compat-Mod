package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.razordevs.deep_aether.client.model.ScarfModel;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class DeepAetherScarfPoseTest {
    @Test
    void anchorsActualScarfGeometryThatHumanoidPoseCopyDoesNotReach() throws Exception {
        HumanoidModel<LivingEntity> player = new HumanoidModel<>(root());
        ScarfModel scarf = new ScarfModel(root());
        player.body.setPos(2, 6, -4);
        player.body.setRotation(0.5f, -0.3f, 0.25f);
        player.body.yScale = 1.1f;
        scarf.head.setPos(6, -2, -2);
        scarf.body[0].setPos(7, -3, 0);
        // These are different fields in the installed mod: ordinary humanoid
        // copying cannot position the geometry actually rendered by the scarf.
        assertNotSame(((HumanoidModel<?>) scarf).head, scarf.head);
        assertNotSame(((HumanoidModel<?>) scarf).body, scarf.body[0]);
        AtomicInteger originalCalls = new AtomicInteger();
        Operation<Void> copy = arguments -> {
            originalCalls.incrementAndGet();
            player.copyPropertiesTo(scarf);
            return null;
        };
        PoseStack actual = new PoseStack();
        DeepAetherScarfMixin patch = new DeepAetherScarfMixin() {};
        Method anchor = DeepAetherScarfMixin.class.getDeclaredMethod("emfattachments$anchorScarf",
                LivingEntity.class, HumanoidModel.class, Operation.class, PoseStack.class, EntityModel.class);
        anchor.setAccessible(true);
        anchor.invoke(patch, null, scarf, copy, actual, player);

        Method crouch = DeepAetherScarfMixin.class.getDeclaredMethod("emfattachments$avoidSecondCrouchOffset",
                PoseStack.class, double.class, double.class, double.class, Operation.class, EntityModel.class);
        crouch.setAccessible(true);
        Operation<Void> legacyTranslate = arguments -> {
            ((PoseStack) arguments[0]).translate((double) arguments[1], (double) arguments[2], (double) arguments[3]);
            return null;
        };
        crouch.invoke(patch, actual, 0d, 0.23d, 0d, legacyTranslate, player);

        PoseStack expected = new PoseStack();
        player.body.translateAndRotate(expected);
        assertArrayEquals(expected.last().pose().get(new float[16]), actual.last().pose().get(new float[16]), 1.0e-6f);
        assertEquals(1, originalCalls.get());
        assertArrayEquals(new float[]{6, -2, -2}, new float[]{scarf.head.x, scarf.head.y, scarf.head.z});
        assertArrayEquals(new float[]{7, -3, 0}, new float[]{scarf.body[0].x, scarf.body[0].y, scarf.body[0].z});
    }

    private static ModelPart root() {
        Map<String, ModelPart> parts = new HashMap<>();
        for (String name : List.of("head", "hat", "body", "left_arm", "right_arm", "left_leg", "right_leg",
                "head2", "body_0", "body_1", "body_2", "body_rot_3")) {
            parts.put(name, new ModelPart(List.of(), Map.of()));
        }
        return new ModelPart(List.of(), parts);
    }
}
