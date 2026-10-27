package dev.caleb.emfattachments.client;

import dev.caleb.emfattachments.config.CompatConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/** Render-time decision only: equipped stacks and persistent visibility stay untouched. */
public final class FirstPersonRenderPolicy {
    // This class is first used by the loaded client's render layers; discovered
    // mods cannot change during the process. Avoid a registry lookup per item.
    private static final boolean FIRST_PERSON_PRESENT = ModList.get().isLoaded("firstperson");
    private FirstPersonRenderPolicy() {}

    public static boolean isLocalBody(LivingEntity entity) {
        return BodyRenderScope.isLocalBody(entity);
    }

    public static boolean isLocalFirstPersonCamera(LivingEntity entity) {
        return BodyRenderScope.isLocalFirstPersonCamera(entity);
    }

    public static boolean shouldHide(LivingEntity entity, String slotName, ItemStack stack) {
        boolean localBody = SleepingBodyRenderScope.isActiveFor(entity)
                || (FIRST_PERSON_PRESENT && isLocalBody(entity));
        return localBody && AccessoryVisibilityPolicy.shouldHide(slotName,
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                CompatConfig.hideHeadAccessories(), CompatConfig.hideScarves(),
                CompatConfig.hiddenSlots(), CompatConfig.hiddenItems(), CompatConfig.visibleItems());
    }
}
