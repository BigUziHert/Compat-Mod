package dev.caleb.emfattachments.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.wispforest.accessories.impl.ExpandedSimpleContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Avoid splitting unknown-size iterators just to inspect a single cape slot. */
@Mixin(targets = "com.aetherteam.aether.mixin.AetherMixinHooks", remap = false)
public abstract class AetherCapeLookupMixin {
    @ModifyArg(
            method = "isCapeVisible(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Ljava/util/stream/StreamSupport;stream(Ljava/util/Spliterator;Z)Ljava/util/stream/Stream;"),
            index = 1, require = 2, expect = 2, allow = 2)
    private static boolean emfattachments$avoidSingleSlotParallelLookup(boolean parallel,
            @Local(ordinal = 0) ExpandedSimpleContainer accessories,
            @Local(ordinal = 1) ExpandedSimpleContainer cosmetics) {
        // Reuse the inventories already fetched by Aether. Container.getSize()
        // would call update() again. Keep native behavior for expanded slots:
        // Iterable's default spliterator does not declare an encounter order.
        return emfattachments$useParallelLookup(parallel,
                accessories.getContainerSize(), cosmetics.getContainerSize());
    }

    @Unique
    private static boolean emfattachments$useParallelLookup(boolean parallel, int accessorySlots, int cosmeticSlots) {
        return parallel && (accessorySlots > 1 || cosmeticSlots > 1);
    }
}
