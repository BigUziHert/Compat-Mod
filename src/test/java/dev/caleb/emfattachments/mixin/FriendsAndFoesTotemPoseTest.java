package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FriendsAndFoesTotemPoseTest {
    @Test
    void followsCompleteAnimatedTorsoWithoutDoubleApplyingCrouch() throws Exception {
        HumanoidModel<LivingEntity> player = new HumanoidModel<>(new ModelPart(List.of(), Map.of(
                "head", part(), "hat", part(), "body", part(), "left_arm", part(),
                "right_arm", part(), "left_leg", part(), "right_leg", part())));
        player.crouching = true;
        player.body.setPos(2, 6, -4);
        player.body.setRotation(0.5f, -0.3f, 0.25f);
        player.body.xScale = 0.9f;
        player.body.yScale = 1.1f;
        player.body.zScale = 1.2f;
        RenderLayerParent<LivingEntity, HumanoidModel<LivingEntity>> parent = new RenderLayerParent<>() {
            @Override public HumanoidModel<LivingEntity> getModel() { return player; }
            @Override public ResourceLocation getTextureLocation(LivingEntity entity) { return null; }
        };
        AtomicInteger obsoleteCrouchCalls = new AtomicInteger();
        Operation<Void> oldCrouch = arguments -> {
            obsoleteCrouchCalls.incrementAndGet();
            ((PoseStack) arguments[0]).translate(0, 0.2, 0);
            return null;
        };
        PoseStack actual = new PoseStack();
        actual.translate(3, -2, 1);
        FriendsAndFoesTotemMixin patch = new FriendsAndFoesTotemMixin() {};
        invoke(patch, "emfattachments$followTorso", actual, oldCrouch, parent);
        invoke(patch, "emfattachments$avoidSecondCrouchRotation", actual, oldCrouch, parent);

        PoseStack expected = new PoseStack();
        expected.translate(3, -2, 1);
        player.body.translateAndRotate(expected);
        assertArrayEquals(expected.last().pose().get(new float[16]), actual.last().pose().get(new float[16]), 1.0e-6f);
        assertArrayEquals(expected.last().normal().get(new float[9]), actual.last().normal().get(new float[9]), 1.0e-6f);
        assertEquals(0, obsoleteCrouchCalls.get(), "The complete torso transform already contains crouch");
    }

    private static void invoke(FriendsAndFoesTotemMixin patch, String name, PoseStack matrices,
                               Operation<Void> operation, RenderLayerParent<?, ?> parent) throws Exception {
        Method method = FriendsAndFoesTotemMixin.class.getDeclaredMethod(name, PoseStack.class,
                LivingEntity.class, Operation.class, RenderLayerParent.class);
        method.setAccessible(true);
        method.invoke(patch, matrices, null, operation, parent);
    }

    private static ModelPart part() { return new ModelPart(List.of(), Map.of()); }
}
