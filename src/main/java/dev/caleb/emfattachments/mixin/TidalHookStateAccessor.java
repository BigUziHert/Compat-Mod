package dev.caleb.emfattachments.mixin;

import com.github.L_Ender.cataclysm.entity.projectile.Tidal_Hook_Entity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Tidal_Hook_Entity.class, remap = false)
public interface TidalHookStateAccessor {
    @Accessor("isPulling") boolean emfattachments$isPulling();
    @Accessor("stack") ItemStack emfattachments$stack();
}
