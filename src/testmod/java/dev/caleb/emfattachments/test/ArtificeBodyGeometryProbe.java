package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.*;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;

/** Actual arm-root vertices (including nested sleeve children), classified by native identities/copies. */
public final class ArtificeBodyGeometryProbe {
    private record Part(String side,String kind){}
    private static final IdentityHashMap<ModelPart,Part> parts=new IdentityHashMap<>();
    private static final Map<String,Map<String,Object>> draws=new LinkedHashMap<>();
    private static String accessoryPath;
    private static boolean drawing;
    private ArtificeBodyGeometryProbe(){}
    public static void reset(){parts.clear();draws.clear();accessoryPath=null;drawing=false;}
    private static void seed(ModelPart part,String side,String kind){part.getAllParts().forEach(p->parts.put(p,new Part(side,kind)));}
    public static void seed(HumanoidModel<?> model){
        seed(model.rightArm,"RIGHT","skin");seed(model.leftArm,"LEFT","skin");
        if(model instanceof PlayerModel<?> player){seed(player.rightSleeve,"RIGHT","sleeve");seed(player.leftSleeve,"LEFT","sleeve");}
    }
    public static void copied(ModelPart source,ModelPart target){
        Part role=parts.get(source);if(role==null)return;
        Part targetRole=parts.get(target);if(targetRole!=null&&!targetRole.kind.equals("copy"))return;
        target.getAllParts().forEach(p->parts.put(p,new Part(role.side,"copy")));
    }
    public static String accessory(String path){String old=accessoryPath;accessoryPath=path;return old;}
    public static Map<String,Object> snapshot(){return Map.of("draws",new LinkedHashMap<>(draws));}
    public static Draw draw(ModelPart part,VertexConsumer original){
        Part role=parts.get(part);if(drawing||role==null)return null;
        String category=accessoryPath==null?role.kind:"accessory/"+accessoryPath;
        String key=category+"/"+role.side;
        var row=draws.computeIfAbsent(key,k->{var out=new LinkedHashMap<String,Object>();out.put("side",role.side);out.put("category",category);out.put("calls",0);out.put("vertices",0);out.put("invalidVertices",0);return out;});
        row.put("calls",((Number)row.get("calls")).intValue()+1);drawing=true;return new Draw(original,row);
    }
    public static final class Draw implements AutoCloseable,VertexConsumer{
        private final VertexConsumer delegate;private final Map<String,Object> row;
        Draw(VertexConsumer delegate,Map<String,Object> row){this.delegate=delegate;this.row=row;}
        public void close(){drawing=false;}
        public VertexConsumer addVertex(float x,float y,float z){
            row.put("vertices",((Number)row.get("vertices")).intValue()+1);
            if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))row.put("invalidVertices",((Number)row.get("invalidVertices")).intValue()+1);
            else{float[] b=(float[])row.computeIfAbsent("bounds",k->new float[]{x,y,z,x,y,z});b[0]=Math.min(b[0],x);b[1]=Math.min(b[1],y);b[2]=Math.min(b[2],z);b[3]=Math.max(b[3],x);b[4]=Math.max(b[4],y);b[5]=Math.max(b[5],z);}
            delegate.addVertex(x,y,z);return this;
        }
        public VertexConsumer setColor(int r,int g,int b,int a){delegate.setColor(r,g,b,a);return this;}
        public VertexConsumer setUv(float u,float v){delegate.setUv(u,v);return this;}
        public VertexConsumer setUv1(int u,int v){delegate.setUv1(u,v);return this;}
        public VertexConsumer setUv2(int u,int v){delegate.setUv2(u,v);return this;}
        public VertexConsumer setNormal(float x,float y,float z){delegate.setNormal(x,y,z);return this;}
    }
}
