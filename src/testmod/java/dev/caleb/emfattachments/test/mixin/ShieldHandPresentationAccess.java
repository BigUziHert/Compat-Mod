package dev.caleb.emfattachments.test.mixin;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Developer-only read access; never changes native hand animation or item identity. */
@Mixin(ItemInHandRenderer.class)
public interface ShieldHandPresentationAccess {
    @Accessor("mainHandHeight") float emfattachments$mainHandHeight();
    @Accessor("oMainHandHeight") float emfattachments$previousMainHandHeight();
    @Accessor("mainHandItem") ItemStack emfattachments$renderedMainHandItem();
}
