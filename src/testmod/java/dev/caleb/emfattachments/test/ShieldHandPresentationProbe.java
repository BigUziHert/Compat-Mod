package dev.caleb.emfattachments.test;

import dev.caleb.emfattachments.test.mixin.ShieldHandPresentationAccess;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Read the actual native cached item and equip interpolation, without resetting either. */
public final class ShieldHandPresentationProbe {
    private ShieldHandPresentationProbe() {}

    public static Map<String,Object> snapshot(boolean includeResolvedModel) {
        Minecraft mc = Minecraft.getInstance();
        ShieldHandPresentationAccess access = (ShieldHandPresentationAccess) mc.gameRenderer.itemInHandRenderer;
        ItemStack held = mc.player.getMainHandItem();
        ItemStack rendered = access.emfattachments$renderedMainHandItem();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        float height = access.emfattachments$mainHandHeight();
        float previous = access.emfattachments$previousMainHandHeight();
        float equipProgress = 1 - Mth.lerp(partial, previous, height);
        var result = new LinkedHashMap<String,Object>();
        result.put("clientTick", mc.player.tickCount);
        result.put("useElapsed", mc.player.getTicksUsingItem());
        result.put("usingItem", mc.player.isUsingItem());
        result.put("mainHandHeight", height); result.put("previousMainHandHeight", previous);
        result.put("renderPartialTick", partial); result.put("nativeEquipProgress", equipProgress);
        result.put("nativeEquipTranslationY", -0.52F - equipProgress * 0.6F);
        result.put("heldItem", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        result.put("renderedItem", BuiltInRegistries.ITEM.getKey(rendered.getItem()).toString());
        result.put("heldDamage", held.getDamageValue()); result.put("renderedDamage", rendered.getDamageValue());
        result.put("sameStackObject", rendered == held);
        result.put("sameItemComponents", ItemStack.isSameItemSameComponents(rendered, held));
        result.put("useStackIsRenderedStack", mc.player.getUseItem() == rendered);
        result.put("useStackIsHeldStack", mc.player.getUseItem() == held);
        if (includeResolvedModel) {
            result.put("cachedItemModel", model(mc, rendered));
            result.put("currentHeldItemModel", model(mc, held));
        }
        return result;
    }

    private static Map<String,Object> model(Minecraft mc, ItemStack stack) {
        var model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
        var transform = model.getTransforms().getTransform(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        return Map.of("customRenderer", model.isCustomRenderer(),
                "rotation", List.of(transform.rotation.x(), transform.rotation.y(), transform.rotation.z()),
                "translation", List.of(transform.translation.x(), transform.translation.y(), transform.translation.z()),
                "scale", List.of(transform.scale.x(), transform.scale.y(), transform.scale.z()));
    }
}
