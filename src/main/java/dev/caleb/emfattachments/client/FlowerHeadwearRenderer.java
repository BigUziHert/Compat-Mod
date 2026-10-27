package dev.caleb.emfattachments.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/** Supplies the missing accessory visual using Bumblezone's own flower geometry. */
public final class FlowerHeadwearRenderer implements AccessoryRenderer {
    public static final FlowerHeadwearRenderer INSTANCE = new FlowerHeadwearRenderer();
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "the_bumblezone", "textures/models/armor/flower_headwear_layer.png");
    private HumanoidModel<LivingEntity> defaultArmor;

    private FlowerHeadwearRenderer() {}

    @Override
    public <M extends LivingEntity> void render(ItemStack stack, SlotReference reference,
            PoseStack matrices, EntityModel<M> model, MultiBufferSource buffers, int light,
            float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
            float netHeadYaw, float headPitch) {
        if (!(model instanceof HumanoidModel<?> parent)) return;
        if (defaultArmor == null) {
            defaultArmor = new HumanoidModel<>(Minecraft.getInstance().getEntityModels()
                    .bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        }
        // Bumblezone overrides the generic armor hook; the humanoid-only hook
        // returns the vanilla fallback and would draw a cube instead of petals.
        Model nativeModel = IClientItemExtensions.of(stack).getGenericArmorModel(
                reference.entity(), stack, EquipmentSlot.HEAD, defaultArmor);
        if (!(nativeModel instanceof HumanoidModel<?> flower)) return;
        flower.head.copyFrom(parent.head);

        // The native model's renderToBuffer looks up the real armor HEAD stack.
        // Drawing its head directly preserves the color of this accessory stack
        // even when the armor slot is empty or contains a differently dyed flower.
        int color = 0xFF000000 | (DyedItemColor.getOrDefault(stack, -1682375) & 0xFFFFFF);
        VertexConsumer vertices = ItemRenderer.getArmorFoilBuffer(buffers,
                RenderType.armorCutoutNoCull(TEXTURE), stack.hasFoil());
        boolean visible = flower.head.visible;
        try {
            flower.head.visible = true;
            flower.head.render(matrices, vertices, light, OverlayTexture.NO_OVERLAY, color);
        } finally {
            flower.head.visible = visible;
        }
    }
}
