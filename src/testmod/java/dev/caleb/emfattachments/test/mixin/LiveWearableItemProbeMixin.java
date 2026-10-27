package dev.caleb.emfattachments.test.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.test.LiveWearableRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class LiveWearableItemProbeMixin {
    @Inject(method="renderItem",at=@At("HEAD"))
    private void emfattachments$liveWearableItem(LivingEntity entity,ItemStack stack,ItemDisplayContext context,boolean left,PoseStack poses,MultiBufferSource buffers,int light,CallbackInfo ci){LiveWearableRenderState.item(entity,stack,context);}
}
