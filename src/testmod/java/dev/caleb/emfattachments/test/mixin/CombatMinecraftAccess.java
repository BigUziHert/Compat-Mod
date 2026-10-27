package dev.caleb.emfattachments.test.mixin;

import net.bettercombat.api.WeaponAttributes;
import net.bettercombat.logic.WeaponSwing;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface CombatMinecraftAccess {
    @Invoker(value = "startUpswing", remap = false) void emfattachments$startUpswing(WeaponAttributes attributes);
    @Accessor(value = "ongoingSwing", remap = false) WeaponSwing emfattachments$ongoingSwing();
}
