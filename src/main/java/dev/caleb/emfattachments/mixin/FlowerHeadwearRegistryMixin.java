package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.caleb.emfattachments.client.FlowerHeadwearRenderer;
import io.wispforest.accessories.api.client.AccessoryRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Restores one missing native Curios visual, while preserving explicit renderers. */
@Mixin(targets = "io.wispforest.accessories.api.client.AccessoriesRendererRegistry", remap = false)
public abstract class FlowerHeadwearRegistryMixin {
    @ModifyReturnValue(
            method = "getRenderer(Lnet/minecraft/world/item/Item;)Lio/wispforest/accessories/api/client/AccessoryRenderer;",
            at = @At("RETURN"), require = 1)
    private static AccessoryRenderer emfattachments$flowerHeadwear(
            AccessoryRenderer original, @Local(argsOnly = true) Item item) {
        if (original != null && original.isEmpty() && BuiltInRegistries.ITEM.getKey(item).equals(
                ResourceLocation.fromNamespaceAndPath("the_bumblezone", "flower_headwear"))) {
            return FlowerHeadwearRenderer.INSTANCE;
        }
        return original;
    }
}
