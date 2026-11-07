package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/** Current Sable 0.7.5 physical-part witnesses, separated from the historical helper probes. */
public final class ArtificeRagdollRenderProbe {
    public static boolean active;
    private static final Map<String,Integer> calls=new LinkedHashMap<>(),vertices=new LinkedHashMap<>();
    private static final Map<String,float[]> bounds=new LinkedHashMap<>();
    private static final Map<String,Object> inputs=new LinkedHashMap<>();
    private static final Map<String,Map<String,Object>> bodyArmDecisions=new LinkedHashMap<>();
    private static int droppedBodyArmDecisions;
    private static int invalid;
    private ArtificeRagdollRenderProbe(){}
    public static void reset(){calls.clear();vertices.clear();bounds.clear();inputs.clear();bodyArmDecisions.clear();droppedBodyArmDecisions=0;invalid=0;ArtificeBodyGeometryProbe.reset();}
    public static void bodyArmDecision(String event,Map<String,Object> data){
        String key=event+data;var existing=bodyArmDecisions.get(key);
        if(existing!=null){existing.put("count",((Number)existing.get("count")).intValue()+1);return;}
        if(bodyArmDecisions.size()>=512){droppedBodyArmDecisions++;return;}
        var row=new LinkedHashMap<String,Object>(data);row.put("event",event);row.put("count",1);bodyArmDecisions.put(key,row);
    }
    public static boolean local(UUID owner){return active&&Minecraft.getInstance().player!=null&&Minecraft.getInstance().player.getUUID().equals(owner)
            &&(!ModList.get().isLoaded("iris")||!net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass());}
    public static void stored(String path,Map<String,List<ItemStack>> normal,Map<String,List<ItemStack>> cosmetic,Map<String,List<Boolean>> flags){
        if(!active)return;inputs.put(path,Map.of("normal",stacks(normal),"cosmetic",stacks(cosmetic),"flags",new LinkedHashMap<>(flags)));
    }
    private static Map<String,Object> stacks(Map<String,List<ItemStack>> items){var result=new LinkedHashMap<String,Object>();items.forEach((name,list)->result.put(name,list.stream().map(s->Map.of("item",BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),"count",s.getCount())).toList()));return result;}
    public static MultiBufferSource buffers(String path,MultiBufferSource original){
        calls.merge(path,1,Integer::sum);return type->new Counter(original.getBuffer(type),path+"/"+type);
    }
    public static Map<String,Object> snapshot(){return Map.of("calls",new LinkedHashMap<>(calls),"vertices",new LinkedHashMap<>(vertices),"bounds",new LinkedHashMap<>(bounds),"storedInputs",new LinkedHashMap<>(inputs),"invalidVertices",invalid,"bodyArmDecisions",new ArrayList<>(bodyArmDecisions.values()),"droppedBodyArmDecisions",droppedBodyArmDecisions,"bodyArmGeometry",ArtificeBodyGeometryProbe.snapshot());}
    private record Counter(VertexConsumer delegate,String path)implements VertexConsumer{
        public VertexConsumer addVertex(float x,float y,float z){vertices.merge(path,1,Integer::sum);if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))invalid++;else{
            float[] b=bounds.computeIfAbsent(path,k->new float[]{x,y,z,x,y,z});b[0]=Math.min(b[0],x);b[1]=Math.min(b[1],y);b[2]=Math.min(b[2],z);b[3]=Math.max(b[3],x);b[4]=Math.max(b[4],y);b[5]=Math.max(b[5],z);
        }delegate.addVertex(x,y,z);return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){delegate.setColor(r,g,b,a);return this;}
        public VertexConsumer setUv(float u,float v){delegate.setUv(u,v);return this;}
        public VertexConsumer setUv1(int u,int v){delegate.setUv1(u,v);return this;}
        public VertexConsumer setUv2(int u,int v){delegate.setUv2(u,v);return this;}
        public VertexConsumer setNormal(float x,float y,float z){delegate.setNormal(x,y,z);return this;}
    }
}
