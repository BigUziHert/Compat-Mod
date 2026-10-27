package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.RagdollAccessoryDrawScope;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import dev.leo.sableplayerragdoll.entity.RagdollDollEntity;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollPartBlockEntityRenderer;
import io.wispforest.accessories.api.AccessoriesCapability;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Controlled calls to the installed native helpers, separate from real Revival lifecycle screenshots. */
public final class RevivalFaceHelperProbe {
    private RevivalFaceHelperProbe() {}
    public static Map<String,Object> run(boolean baseline) {
        var report=new LinkedHashMap<String,Object>(); var rows=new ArrayList<Map<String,Object>>(); var failures=new ArrayList<String>();
        report.put("cases",rows);report.put("failures",failures);
        report.put("scope","Installed live/stored helper calls over all six physical parts, actual emitted texture vertices. Same live owner; no fake downed state. Curios-only cosmetic behavior is diagnostic because native Curios snapshots store the functional item.");
        var mc=Minecraft.getInstance();var cap=AccessoriesCapability.get(mc.player);
        var saved=new LinkedHashMap<String,List<ItemStack>>();var cosmetics=new LinkedHashMap<String,List<ItemStack>>();var flags=new LinkedHashMap<String,List<Boolean>>();
        Field activeModel=null,activePart=null;Object priorModel=null,priorPart=null;
        try {
            cap.getContainers().forEach((name,c)->{var a=new ArrayList<ItemStack>();var b=new ArrayList<ItemStack>();for(int i=0;i<c.getSize();i++){a.add(c.getAccessories().getItem(i).copy());b.add(c.getCosmeticAccessories().getItem(i).copy());}saved.put(name,a);cosmetics.put(name,b);flags.put(name,new ArrayList<>(c.renderOptions()));});
            activeModel=RagdollPartBlockEntityRenderer.class.getDeclaredField("activeModel");activeModel.setAccessible(true);priorModel=activeModel.get(null);
            activePart=RagdollPartBlockEntityRenderer.class.getDeclaredField("activeBodyPart");activePart.setAccessible(true);priorPart=activePart.get(null);
            var model=new PlayerModel<RagdollDollEntity>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER),false);
            RenderLayerParent<RagdollDollEntity,PlayerModel<RagdollDollEntity>> parent=new RenderLayerParent<>(){
                public PlayerModel<RagdollDollEntity> getModel(){return model;}
                public ResourceLocation getTextureLocation(RagdollDollEntity ignored){return mc.player.getSkin().texture();}
            };
            activeModel.set(null,model);
            Method storedA=helper("AccessoriesRenderHelper",true),storedC=helper("CuriosRenderHelper",true),liveA=helper("AccessoriesRenderHelper",false),liveC=helper("CuriosRenderHelper",false);
            for(String scenario:List.of("face_visible","face_hidden","cosmetic_whoopee_visible","cosmetic_whoopee_hidden","distinct_cosmetic_visible","hat_visible","hand_control","feet_control")) {
                cap.getContainers().values().forEach(c->{c.getAccessories().clearContent();c.getCosmeticAccessories().clearContent();});
                String slot=scenario.startsWith("hat")?"hat":scenario.startsWith("hand")?"hand":scenario.startsWith("feet")?"shoes":"face";
                String curioSlot=slot.equals("hat")?"head":slot.equals("hand")?"hands":slot.equals("shoes")?"feet":slot;
                var container=cap.getContainers().get(slot);if(container==null||container.getSize()==0)throw new IllegalStateException("Missing actual slot "+slot);
                ItemStack normal=item(scenario.startsWith("hat")?"aeronautics:aviators_goggles":scenario.startsWith("hand")?"artifacts:digging_claws":scenario.startsWith("feet")?"artifacts:running_shoes":scenario.startsWith("cosmetic")?"artifacts:snorkel":"artifacts:whoopee_cushion");
                ItemStack cosmetic=scenario.startsWith("cosmetic")?item("artifacts:whoopee_cushion"):scenario.startsWith("distinct")?item("artifacts:snorkel"):ItemStack.EMPTY;
                boolean visible=!scenario.endsWith("hidden");container.getAccessories().setItem(0,normal);container.getCosmeticAccessories().setItem(0,cosmetic);container.renderOptions().set(0,visible);
                ItemStack effective=cosmetic.isEmpty()?normal:cosmetic;
                Map<String,List<ItemStack>> snapshotA=Map.of(slot,List.of(effective.copy())),snapshotC=Map.of(curioSlot,List.of(normal.copy()));
                String texture=BuiltInRegistries.ITEM.getKey(effective.getItem()).getPath();
                for(boolean stored:new boolean[]{true,false})for(boolean withA:new boolean[]{true,false}) {
                    var row=new LinkedHashMap<String,Object>();rows.add(row);row.put("scenario",scenario);row.put("source",stored?"stored":"live");row.put("path",withA?"accessories_then_curios":"curios_only");row.put("visible",visible);
                    row.put("normal",normal.toString());row.put("cosmetic",cosmetic.toString());row.put("storedAccessories",snapshotA.toString());row.put("storedCurios",snapshotC.toString());
                    var partCounts=new LinkedHashMap<String,Integer>();RevivalFaceRenderProbe.reset();RevivalFaceRenderProbe.active=true;
                    for(BodyPart part:BodyPart.values()) {
                        model.setAllVisible(true);activePart.set(null,part);Counts counts=new Counts();
                        try(var ledger=RagdollAccessoryDrawScope.open()) {
                            if(stored){if(withA)storedA.invoke(null,part,snapshotA,mc.player,parent,new PoseStack(),counts,15728880,.5f);storedC.invoke(null,part,snapshotC,mc.player,parent,new PoseStack(),counts,15728880,.5f);}
                            else {if(withA)liveA.invoke(null,part,mc.player,parent,new PoseStack(),counts,15728880,.5f);liveC.invoke(null,part,mc.player,parent,new PoseStack(),counts,15728880,.5f);}
                        }
                        partCounts.put(part.name(),counts.forTexture(texture));
                    }
                    row.put("effectiveTextureVerticesByPart",partCounts);row.put("geometry",RevivalFaceRenderProbe.snapshot());
                    boolean headCase=slot.equals("face")||slot.equals("hat"),cosmeticFallback=!withA&&!cosmetic.isEmpty();
                    boolean passed=true;
                    if(headCase&&!cosmeticFallback){int head=partCounts.getOrDefault("HEAD",0),other=partCounts.entrySet().stream().filter(e->!e.getKey().equals("HEAD")).mapToInt(Map.Entry::getValue).sum();passed=visible?head>0&&other==0:head==0&&other==0;
                        // Both native paths must not emit the same head accessory in a combined invocation.
                        if(withA&&visible)passed&=RevivalFaceRenderProbe.count("curios/",texture)==0;
                        if(withA&&!cosmetic.isEmpty())passed&=RevivalFaceRenderProbe.count("curios/",BuiltInRegistries.ITEM.getKey(normal.getItem()).getPath())==0;
                        if(!passed)failures.add(scenario+" "+row.get("source")+" "+row.get("path"));
                    }
                    row.put("assertion",!headCase?"native arm/feet distribution recorded; compare baseline against candidate":cosmeticFallback?"native Curios-only cosmetic limitation recorded":"effective head item once on HEAD only, hidden zero across all parts");row.put("expectedBehaviorPassed",passed);
                    RevivalFaceRenderProbe.active=false;
                }
            }
            report.put("status",baseline?"BASELINE_RECORDED":failures.isEmpty()?"PASS":"FAIL");report.put("observedRegressionCount",failures.size());
        }catch(Throwable t){report.put("status","FAIL");report.put("detail",t.toString());if(t.getCause()!=null)report.put("cause",t.getCause().toString());}
        finally {
            RevivalFaceRenderProbe.active=false;
            try {saved.forEach((name,list)->{var c=cap.getContainers().get(name);for(int i=0;i<list.size();i++){c.getAccessories().setItem(i,list.get(i));c.getCosmeticAccessories().setItem(i,cosmetics.get(name).get(i));c.renderOptions().set(i,flags.get(name).get(i));}});
                if(activeModel!=null)activeModel.set(null,priorModel);if(activePart!=null)activePart.set(null,priorPart);report.put("clientEquipmentAndRenderGlobalsRestored",true);
            }catch(Throwable t){report.put("status","FAIL");report.put("restoreError",t.toString());}
        }
        return report;
    }
    static ItemStack item(String id){var s=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();if(s.isEmpty())throw new IllegalStateException("Missing "+id);return s;}
    private static Method helper(String name,boolean stored)throws Exception {
        Class<?> c=Class.forName("dev.leo.sableplayerragdoll.neoforge.client."+name);
        Method m=stored?c.getDeclaredMethod("renderFromStored",BodyPart.class,Map.class,LivingEntity.class,RenderLayerParent.class,PoseStack.class,MultiBufferSource.class,int.class,float.class):c.getDeclaredMethod("render",BodyPart.class,LivingEntity.class,RenderLayerParent.class,PoseStack.class,MultiBufferSource.class,int.class,float.class);m.setAccessible(true);return m;
    }
    private static final class Counts implements MultiBufferSource {
        final Map<String,Integer> byType=new LinkedHashMap<>();
        public VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type){return new Sink(this,type.toString());}
        int forTexture(String text){return byType.entrySet().stream().filter(e->e.getKey().contains(text)).mapToInt(Map.Entry::getValue).sum();}
    }
    private record Sink(Counts counts,String type) implements VertexConsumer {
        public VertexConsumer addVertex(float x,float y,float z){counts.byType.merge(type,1,Integer::sum);return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}public VertexConsumer setUv(float u,float v){return this;}public VertexConsumer setUv1(int u,int v){return this;}public VertexConsumer setUv2(int u,int v){return this;}public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
}
