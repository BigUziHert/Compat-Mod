package dev.caleb.emfattachments.mixin;

import dev.tr7zw.firstperson.LogicHandler;
import io.redspace.irons_artifice.item.GunItem;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep Iron's animated hand renderer while FirstPerson still draws the player's body. */
@Mixin(value = LogicHandler.class, remap = false)
public abstract class ArtificeFirstPersonHandsMixin {
    @Inject(method = "reloadAutoVanillaHandsSettings", at = @At("TAIL"), require = 1)
    private void emfattachments$registerNativeGunHands(CallbackInfo ci) {
        LogicHandler logic = (LogicHandler) (Object) this;
        // This is FirstPerson's native runtime cache, rebuilt on its first client
        // tick and settings reload. Its API selects the hand pass, hides the
        // body arms/items and disables dynamic hand swapping for these items.
        // Do not add to persisted options or disable the body renderer.
        for (var item : BuiltInRegistries.ITEM) {
            if (item instanceof GunItem) logic.addAutoVanillaHandsItem(item);
        }
    }
}
