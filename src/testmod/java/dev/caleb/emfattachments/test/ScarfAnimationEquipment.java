package dev.caleb.emfattachments.test;

import artifacts.registry.ModDataComponents;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-side loadouts in a disposable world, with read-only verification on either side. */
public final class ScarfAnimationEquipment {
    public static final List<String> STAGES = List.of("base", "other-wearable", "scarf-inactive", "scarf-active",
            "scarf-removed", "vanilla-invisible", "recovered");
    private static final String SCARF="artifacts:scarf_of_invisibility", GLOVE="artifacts:power_glove", PACK="sophisticatedbackpacks:backpack";

    private ScarfAnimationEquipment() {}

    public static void configure(ServerPlayer actor, String stage) {
        if (!actor.serverLevel().getServer().isSameThread() || !STAGES.contains(stage)) throw new IllegalArgumentException(stage);
        actor.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.IRON_CHESTPLATE));
        actor.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.IRON_LEGGINGS));
        equip(actor,"back",item(PACK));
        equip(actor,"hand",stage.equals("base") ? ItemStack.EMPTY : item(GLOVE));
        var scarf=stage.startsWith("scarf-")&&!stage.equals("scarf-removed") ? item(SCARF) : ItemStack.EMPTY;
        if (!scarf.isEmpty()) scarf.set(ModDataComponents.DISABLED_BY_TOGGLE.get(),Unit.INSTANCE);
        equip(actor,"necklace",scarf);
        if (stage.equals("vanilla-invisible")) actor.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY,1200,0,false,true));
        if (stage.equals("base") || stage.equals("recovered")) actor.removeEffect(MobEffects.INVISIBILITY);
        actor.inventoryMenu.broadcastChanges();
    }

    private static ItemStack item(String id) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElseThrow().getDefaultInstance();
    }

    private static AccessoriesContainer slot(Player p,String name) {
        var cap=AccessoriesCapability.get(p);
        return cap==null?null:cap.getContainers().get(name);
    }

    private static void equip(ServerPlayer p,String name,ItemStack item) {
        var c=slot(p,name);
        if(c==null||c.getSize()==0 || !item.isEmpty() && AccessoriesAPI.getStackSlotTypes(p,item).stream().noneMatch(s->s.name().equals(name))) {
            throw new IllegalStateException("Unavailable functional slot "+name+" for "+item);
        }
        c.getAccessories().setItem(0,item);
        c.getCosmeticAccessories().setItem(0,ItemStack.EMPTY);
        c.renderOptions().set(0,true);
    }

    private static ItemStack get(Player p,String name) {
        var c=slot(p,name);
        return c==null||c.getSize()==0?ItemStack.EMPTY:c.getAccessories().getItem(0);
    }

    private static String id(ItemStack stack) {return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();}

    public static Map<String,Object> state(Player p) {
        var out=new LinkedHashMap<String,Object>();
        for(String name:List.of("necklace","hand","back"))out.put(name,id(get(p,name)));
        out.put("chest",id(p.getItemBySlot(EquipmentSlot.CHEST)));out.put("legs",id(p.getItemBySlot(EquipmentSlot.LEGS)));
        out.put("disabledByToggle",get(p,"necklace").has(ModDataComponents.DISABLED_BY_TOGGLE.get()));
        var effect=p.getEffect(MobEffects.INVISIBILITY);
        out.put("invisibilityDuration",effect==null?0:effect.getDuration());out.put("invisible",p.isInvisible());
        return out;
    }

    static boolean ready(Player p,String stage,boolean remote) {
        var state=state(p);
        boolean scarf=stage.equals("scarf-active")||stage.equals("scarf-inactive");
        boolean invisible=stage.equals("scarf-active")||stage.equals("vanilla-invisible");
        return state.get("chest").equals("minecraft:iron_chestplate") && state.get("legs").equals("minecraft:iron_leggings")
                && state.get("back").equals(PACK) && state.get("hand").equals(stage.equals("base")?"minecraft:air":GLOVE)
                && state.get("necklace").equals(scarf?SCARF:"minecraft:air") && state.get("disabledByToggle").equals(stage.equals("scarf-inactive"))
                && p.isInvisible()==invisible && (remote||p.hasEffect(MobEffects.INVISIBILITY)==invisible);
    }
}
