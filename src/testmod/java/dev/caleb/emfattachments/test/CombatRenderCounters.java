package dev.caleb.emfattachments.test;

import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Counts actual renderer entry points in the live test, excluding shadow passes. */
public final class CombatRenderCounters {
    public static boolean active;
    public static int bodyPasses, bodyItems, vanillaItems, otherItems;
    private CombatRenderCounters() {}
    private static boolean localBody(LivingEntity entity) {
        return net.neoforged.fml.ModList.get().isLoaded("firstperson")
                && FirstPersonRenderPolicy.isLocalBody(entity);
    }
    public static void reset() { bodyPasses = bodyItems = vanillaItems = otherItems = 0; }
    public static void body(LivingEntity entity) {
        if (active && localBody(entity)) bodyPasses++;
    }
    public static void item(LivingEntity entity, ItemStack stack, ItemDisplayContext context) {
        if (!active || stack.isEmpty() || entity != Minecraft.getInstance().player) return;
        if (net.neoforged.fml.ModList.get().isLoaded("iris")
                && net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass()) return;
        if (localBody(entity)) bodyItems++;
        else if (context.firstPerson()) vanillaItems++;
        else otherItems++;
    }
    public static Map<String, Object> snapshot() {
        return Map.of("bodyPasses", bodyPasses, "bodyItems", bodyItems, "vanillaItems", vanillaItems, "otherItems", otherItems);
    }
}
