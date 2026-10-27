package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;

/** Observes actual native helper buffers, including vertices emitted before a swallowed exception. */
public final class RevivalFaceRenderProbe {
    public static boolean active;
    private static final Map<String,Integer> calls = new LinkedHashMap<>(), vertices = new LinkedHashMap<>();
    private static final Map<String,Object> stored = new LinkedHashMap<>();
    private static int invalid;
    private RevivalFaceRenderProbe() {}
    public static void reset() { calls.clear(); vertices.clear(); stored.clear(); invalid=0; }
    public static void stored(String path,Map<String,java.util.List<net.minecraft.world.item.ItemStack>> items,net.minecraft.world.entity.LivingEntity owner) {
        if(!active||!RagdollRenderCounters.mainPass())return;
        var row=new LinkedHashMap<String,Object>();var stacks=new LinkedHashMap<String,Object>();
        items.forEach((slot,list)->stacks.put(slot,list.stream().map(s->s.isEmpty()?"empty":net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem())+" count="+s.getCount()+" components="+s.getComponents()).toList()));
        row.put("storedStacks",stacks);row.put("ownerClass",owner.getClass().getName());row.put("ownerId",owner.getUUID().toString());
        var cap=io.wispforest.accessories.api.AccessoriesCapability.get(owner);var face=cap==null?null:cap.getContainers().get("face");
        if(face!=null&&face.getSize()>0)row.put("liveFace",Map.of("renderFlag",face.shouldRender(0),"normal",face.getAccessories().getItem(0).toString(),"cosmetic",face.getCosmeticAccessories().getItem(0).toString()));
        stored.put(path,row);
    }
    public static MultiBufferSource buffers(String path, MultiBufferSource original) {
        if (!active || !RagdollRenderCounters.mainPass()) return original;
        calls.merge(path,1,Integer::sum);
        return type -> new Counter(original.getBuffer(type),path+"/"+type);
    }
    public static Map<String,Object> snapshot() {
        return Map.of("calls",new LinkedHashMap<>(calls),"vertices",new LinkedHashMap<>(vertices),"invalidVertices",invalid,"storedAndLiveInputs",new LinkedHashMap<>(stored));
    }
    public static int count(String path,String texture) {
        return vertices.entrySet().stream().filter(e->e.getKey().startsWith(path)&&e.getKey().contains(texture)).mapToInt(Map.Entry::getValue).sum();
    }
    private record Counter(VertexConsumer delegate,String key) implements VertexConsumer {
        public VertexConsumer addVertex(float x,float y,float z) { vertices.merge(key,1,Integer::sum); if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))invalid++; delegate.addVertex(x,y,z);return this; }
        public VertexConsumer setColor(int r,int g,int b,int a){delegate.setColor(r,g,b,a);return this;}
        public VertexConsumer setUv(float u,float v){delegate.setUv(u,v);return this;}
        public VertexConsumer setUv1(int u,int v){delegate.setUv1(u,v);return this;}
        public VertexConsumer setUv2(int u,int v){delegate.setUv2(u,v);return this;}
        public VertexConsumer setNormal(float x,float y,float z){delegate.setNormal(x,y,z);return this;}
    }
}
