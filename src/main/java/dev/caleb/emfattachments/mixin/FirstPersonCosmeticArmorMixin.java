package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import io.wispforest.accessories.menu.ArmorSlotTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents cosmetic helmet lookup from bypassing FirstPerson's empty-head result. */
@Mixin(value = ArmorSlotTypes.class, remap = false)
public abstract class FirstPersonCosmeticArmorMixin {
    @Inject(
            method = "getAlternativeStack(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void emfattachments$hideCosmeticHelmet(
            LivingEntity entity, EquipmentSlot slot, CallbackInfoReturnable<ItemStack> cir) {
        // Both Accessories and FirstPerson inject getItemBySlot at HEAD. The
        // cosmetic return can win before FirstPerson returns an empty helmet.
        // Change only the render-time cosmetic lookup, never equipped contents.
        if (slot == EquipmentSlot.HEAD && FirstPersonRenderPolicy.isLocalBody(entity)) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
