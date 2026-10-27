package dev.caleb.emfattachments.test;

import com.mojang.authlib.GameProfile;
import dev.caleb.emfattachments.client.RagdollAccessoryDrawScope;
import io.wispforest.accessories.api.slot.SlotReference;
import io.wispforest.accessories_compat.curios.wrapper.CuriosConversionUtils;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import top.theillusivec4.curios.api.SlotContext;

/** Actual transformed bridge/ledger semantics; not a substitute for physical ragdoll rendering. */
public final class RagdollDrawScopeProbe {
    private RagdollDrawScopeProbe() {}
    public static Map<String,Object> run(){
        var result=new LinkedHashMap<String,Object>();var checks=new ArrayList<Map<String,Object>>();result.put("scope","Actual installed bridge canonical slot conversion and successful-draw ledger negative controls. No entity equipment is modified; native layer emission is checked separately in the ragdoll camera sequence.");result.put("checks",checks);
        var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null){result.put("status","FAIL");result.put("error","Requires an active client world");return result;}
        ListTag inventory=mc.player.getInventory().save(new ListTag());
        try{
            var player=mc.player;var remote=new RemotePlayer(mc.level,new GameProfile(UUID.randomUUID(),"DrawScopeControl"));
            ItemStack base=BuiltInRegistries.ITEM.get(ResourceLocation.parse("the_bumblezone:flower_headwear")).getDefaultInstance();if(base.isEmpty())throw new IllegalStateException("Native flower item missing");
            var head=new SlotContext("head",player,0,false,true);
            check(checks,"outside scope never hides",!RagdollAccessoryDrawScope.isDuplicate(head,base));
            RagdollAccessoryDrawScope.completed(SlotReference.of(player,"hat",0),base);
            check(checks,"completion outside scope ignored",!RagdollAccessoryDrawScope.isDuplicate(head,base));
            for(String alias:List.of("head","body","bracelet","hands","feet","curio","necklace")){
                var context=new SlotContext(alias,player,0,false,true);String canonical=CuriosConversionUtils.slotConvertToA(alias);
                try(var scope=RagdollAccessoryDrawScope.open()){
                    check(checks,alias+" unmatched native draw retained",!RagdollAccessoryDrawScope.isDuplicate(context,base));
                    RagdollAccessoryDrawScope.completed(SlotReference.of(player,canonical,0),base);
                    check(checks,alias+" maps to "+canonical,RagdollAccessoryDrawScope.isDuplicate(context,base));
                    check(checks,alias+" stack copy matches",RagdollAccessoryDrawScope.isDuplicate(context,base.copy()));
                    check(checks,alias+" distinct index retained",!RagdollAccessoryDrawScope.isDuplicate(new SlotContext(alias,player,1,false,true),base));
                    check(checks,alias+" distinct entity retained",!RagdollAccessoryDrawScope.isDuplicate(new SlotContext(alias,remote,0,false,true),base));
                }
                check(checks,alias+" closed scope discarded",!RagdollAccessoryDrawScope.isDuplicate(context,base));
            }
            try(var outer=RagdollAccessoryDrawScope.open()){
                RagdollAccessoryDrawScope.completed(SlotReference.of(player,"hat",0),base);
                check(checks,"distinct mapped slot retained",!RagdollAccessoryDrawScope.isDuplicate(new SlotContext("bracelet",player,0,false,true),base));
                check(checks,"distinct item type retained",!RagdollAccessoryDrawScope.isDuplicate(head,net.minecraft.world.item.Items.STICK.getDefaultInstance()));
                var dyed=base.copy();dyed.set(DataComponents.DYED_COLOR,new DyedItemColor(0x33AAFF,true));
                check(checks,"distinct component retained",!RagdollAccessoryDrawScope.isDuplicate(head,dyed));
                var doubled=base.copy();doubled.setCount(2);check(checks,"distinct count retained",!RagdollAccessoryDrawScope.isDuplicate(head,doubled));
                check(checks,"empty stack never duplicate",!RagdollAccessoryDrawScope.isDuplicate(head,ItemStack.EMPTY));
                check(checks,"null context never duplicate",!RagdollAccessoryDrawScope.isDuplicate(null,base));
                var source=base.copy();RagdollAccessoryDrawScope.completed(SlotReference.of(player,"hat",2),source);source.set(DataComponents.CUSTOM_NAME,Component.literal("changed after successful draw"));
                check(checks,"recorded stack snapshot preserved",RagdollAccessoryDrawScope.isDuplicate(new SlotContext("head",player,2,false,true),base));
                check(checks,"later source mutation not swallowed",!RagdollAccessoryDrawScope.isDuplicate(new SlotContext("head",player,2,false,true),source));
                var wrist=new SlotContext("bracelet",player,0,false,true);
                var inner=RagdollAccessoryDrawScope.open();
                try{
                    check(checks,"inner scope does not inherit outer successful draw",!RagdollAccessoryDrawScope.isDuplicate(head,base));
                    RagdollAccessoryDrawScope.completed(SlotReference.of(player,"wrist",0),dyed);
                    check(checks,"inner success matched",RagdollAccessoryDrawScope.isDuplicate(wrist,dyed));
                }finally{inner.close();}
                inner.close();
                check(checks,"outer successful draw restored after inner close",RagdollAccessoryDrawScope.isDuplicate(head,base));
                check(checks,"inner success does not leak outward",!RagdollAccessoryDrawScope.isDuplicate(wrist,dyed));
                try(var exceptionScope=RagdollAccessoryDrawScope.open()){
                    RagdollAccessoryDrawScope.completed(SlotReference.of(player,"wrist",0),dyed);throw new ExpectedFailure();
                }catch(ExpectedFailure expected){check(checks,"exception restores previous scope",RagdollAccessoryDrawScope.isDuplicate(head,base));}
                check(checks,"exception discards inner draws",!RagdollAccessoryDrawScope.isDuplicate(wrist,dyed));
                var roundTrip=CuriosConversionUtils.objectsConvertToC(SlotReference.of(player,"hat",0));
                check(checks,"bridge embedded slot-reference roundtrip",RagdollAccessoryDrawScope.isDuplicate(roundTrip,base));
            }
            check(checks,"all scopes removed after completion",!RagdollAccessoryDrawScope.isDuplicate(head,base));
            check(checks,"inventory unchanged",inventory.equals(player.getInventory().save(new ListTag())));
            result.put("status","PASS");result.put("checkCount",checks.size());result.put("inventoryUnchanged",true);
        }catch(Throwable e){result.put("status","FAIL");result.put("error",e.toString());result.put("checkCount",checks.size());}
        return result;
    }
    private static void check(List<Map<String,Object>> checks,String label,boolean passed){checks.add(Map.of("check",label,"passed",passed));if(!passed)throw new AssertionError(label);}
    private static final class ExpectedFailure extends RuntimeException {}
}
