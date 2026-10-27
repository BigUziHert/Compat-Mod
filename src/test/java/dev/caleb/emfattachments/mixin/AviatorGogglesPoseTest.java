package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
import static org.junit.jupiter.api.Assertions.assertSame;

/** Exercises the actual wrapper against a setupAnim that overwrites the copied pose. */
class AviatorGogglesPoseTest {
    @Test
    void restoresAnimatedTranslationRotationAndScaleAfterVanillaAnimation() throws Exception {
        HumanoidModel<LivingEntity> player = model();
        HumanoidModel<LivingEntity> armor = model();
        setPose(player.head, new float[]{1.25f, 6.5f, -3.75f, 0.4f, -0.7f, 0.2f, 1.1f, 0.9f, 1.2f});
        // Emulate the upstream renderer's copy-before-setupAnim order.
        armor.head.copyFrom(player.head);
        armor.hat.copyFrom(player.head);
        AtomicInteger originalCalls = new AtomicInteger();
        Operation<Void> setupAnim = arguments -> {
            assertSame(armor, arguments[0]);
            assertEquals(2.0f, (float) arguments[2]);
            assertEquals(3.0f, (float) arguments[3]);
            assertEquals(4.0f, (float) arguments[4]);
            assertEquals(5.0f, (float) arguments[5]);
            assertEquals(6.0f, (float) arguments[6]);
            originalCalls.incrementAndGet();
            setPose(armor.head, new float[]{0, 0, 0, 0, 0, 0, 1, 1, 1});
            armor.hat.copyFrom(armor.head);
            armor.body.xRot = 0.5f;
            return null;
        };
        RenderLayerParent<LivingEntity, HumanoidModel<LivingEntity>> parent = new RenderLayerParent<>() {
            @Override public HumanoidModel<LivingEntity> getModel() { return player; }
            @Override public ResourceLocation getTextureLocation(LivingEntity entity) { return null; }
        };

        Method wrapper = AviatorGogglesMixin.class.getDeclaredMethod(
                "emfattachments$restoreAnimatedHead", HumanoidModel.class, LivingEntity.class,
                float.class, float.class, float.class, float.class, float.class,
                Operation.class, RenderLayerParent.class);
        wrapper.setAccessible(true);
        wrapper.invoke(new AviatorGogglesMixin() {}, armor, null, 2f, 3f, 4f, 5f, 6f, setupAnim, parent);

        assertEquals(1, originalCalls.get(), "Item-specific setup must still execute exactly once");
        assertArrayEquals(pose(player.head), pose(armor.head), "Goggles must follow the final animated head");
        assertArrayEquals(pose(player.head), pose(armor.hat), "The helmet overlay must follow the same head");
        assertEquals(0.5f, armor.body.xRot, "The patch must not overwrite unrelated item model animation");
    }

    private static HumanoidModel<LivingEntity> model() {
        return new HumanoidModel<>(new ModelPart(List.of(), Map.of(
                "head", part(), "hat", part(), "body", part(), "left_arm", part(),
                "right_arm", part(), "left_leg", part(), "right_leg", part())));
    }

    private static ModelPart part() { return new ModelPart(List.of(), Map.of()); }

    private static float[] pose(ModelPart p) {
        return new float[]{p.x, p.y, p.z, p.xRot, p.yRot, p.zRot, p.xScale, p.yScale, p.zScale};
    }

    private static void setPose(ModelPart p, float[] value) {
        p.x = value[0]; p.y = value[1]; p.z = value[2];
        p.xRot = value[3]; p.yRot = value[4]; p.zRot = value[5];
        p.xScale = value[6]; p.yScale = value[7]; p.zScale = value[8];
    }
}
