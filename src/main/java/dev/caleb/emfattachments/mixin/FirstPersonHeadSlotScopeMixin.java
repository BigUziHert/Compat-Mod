package dev.caleb.emfattachments.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.caleb.emfattachments.client.BodyRenderScope;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Restricts FirstPerson's native empty-head lookup to its actual local body pass. */
@Mixin(value = Player.class, priority = 1500)
public abstract class FirstPersonHeadSlotScopeMixin {
    // FirstPerson adds a renamed getItemBySlot callback to Player at priority1000.
    // MixinSquared selects that exact handler across its generated name. Wrapping the query
    // preserves every other mod's lookup result and does not mutate FPM globals.
    @TargetHandler(mixin = "dev.tr7zw.firstperson.mixins.PlayerMixin", name = "getItemBySlot")
    @ModifyExpressionValue(method = "@MixinSquared:Handler", at = @At(value = "INVOKE",
            target = "Ldev/tr7zw/firstperson/FirstPersonModelCore;isRenderingPlayer()Z", remap = false),
            require = 1, expect = 1, allow = 1)
    private boolean emfattachments$scopeNativeHeadLookup(boolean rendering,
                                                          @Local(argsOnly = true) EquipmentSlot slot) {
        return rendering && (slot != EquipmentSlot.HEAD || BodyRenderScope.isLocalBody((Player) (Object) this));
    }
}
