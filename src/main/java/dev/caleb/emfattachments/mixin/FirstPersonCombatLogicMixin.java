package dev.caleb.emfattachments.mixin;

import dev.caleb.emfattachments.client.FirstPersonCombat;
import dev.tr7zw.firstperson.LogicHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chooses one arm/item renderer during a real attack; never rewrites user settings. */
@Mixin(value = LogicHandler.class, remap = false)
public abstract class FirstPersonCombatLogicMixin {
    @Inject(method = "showVanillaHands(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void emfattachments$useAnimatedBodyHands(ItemStack mainHand, ItemStack offHand,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (FirstPersonCombat.ownsCombatRendering()) cir.setReturnValue(false);
    }

    @Inject(method = {
            "hideArmsAndItems(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            "dynamicHandsEnabled(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"},
            at = @At("HEAD"), cancellable = true, require = 1)
    private void emfattachments$keepAnimatedBodyHands(LivingEntity entity, ItemStack mainHand, ItemStack offHand,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (entity == Minecraft.getInstance().player && FirstPersonCombat.ownsCombatRendering()) cir.setReturnValue(false);
    }
}
