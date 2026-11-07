package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.redspace.irons_artifice.gun.ArmPoseKind;
import io.redspace.irons_artifice.client.gun.FirstPersonArmsModel;
import io.redspace.irons_artifice.item.GunItem;
import io.redspace.irons_artifice.item.ReloadState;
import io.redspace.irons_artifice.registry.ItemRegistry;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.fml.ModList;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

/** Observes real draws independently of production compatibility helpers. */
public final class ArtificeRenderProbe {
    static final boolean ONE_HANDED_SIX_SHOOTER = !Boolean.getBoolean("emfattachments.disable")
            && ModList.get().getModContainerById("emfattachments")
            .map(mod -> mod.getModInfo().getVersion().compareTo(new DefaultArtifactVersion("1.2.10")) >= 0)
            .orElse(false);
    public static boolean active;
    private static final List<Map<String,Object>> hands=new ArrayList<>(),poses=new ArrayList<>();
    private ArtificeRenderProbe(){}
    public static void beginFrame(){hands.clear();poses.clear();}
    public static Map<String,Object> snapshot(){return Map.of("hands",List.copyOf(hands),"emfPoses",List.copyOf(poses));}
    public static Hand hand(ModelPart part,ItemDisplayContext perspective){return active?new Hand(part,perspective):null;}
    public static final class Hand implements AutoCloseable {
        final Map<String,Object> row=new LinkedHashMap<>();final Map<String,Counts> textures=new LinkedHashMap<>();
        Hand(ModelPart part,ItemDisplayContext perspective){
            row.put("nativeArmPose",ModelState.state(part));row.put("textures",textures);row.put("perspective",perspective.name());
            var player=Minecraft.getInstance().player;var arms=player==null?null:FirstPersonArmsModel.forSkin(player.getSkin().model());
            if(arms!=null&&(part==arms.leftArm||part==arms.rightArm)){
                boolean boneLeft=part==arms.leftArm,mirrored=perspective==ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
                row.put("nativeBone",boneLeft?"left_arm":"right_arm");row.put("physicalArm",boneLeft!=mirrored?"LEFT":"RIGHT");
            }
        }
        public MultiBufferSource buffers(MultiBufferSource original){return type->{String texture=type.toString();Counts count=textures.computeIfAbsent(texture,k->new Counts());return new Counter(original.getBuffer(type),count);};}
        @Override public void close(){if(hands.size()<8)hands.add(row);}
    }
    public static Pose pose(Map<String,? extends ModelPart> parts){
        if(!active||Minecraft.getInstance().player==null)return null;
        var player=Minecraft.getInstance().player;var stack=player.getMainHandItem();
        if(!(stack.getItem() instanceof GunItem gun)||!parts.containsKey("body"))return null;
        ReloadState reload=ReloadState.get(stack);
        boolean singleArmReload = ONE_HANDED_SIX_SHOOTER && stack.is(ItemRegistry.SIX_SHOOTER.get());
        boolean both = gun.getGun().armPoseKind() == ArmPoseKind.RIFLE
                || (reload != null && !reload.isFinished() && !singleArmReload);
        return new Pose(parts,both||player.getMainArm()==HumanoidArm.LEFT,both||player.getMainArm()==HumanoidArm.RIGHT);
    }
    public static final class Pose implements AutoCloseable {
        final Map<String,? extends ModelPart> parts;final Map<String,float[]> before=new LinkedHashMap<>();
        Pose(Map<String,? extends ModelPart> parts,boolean left,boolean right){this.parts=parts;before.put("body",ModelState.state(parts.get("body")));if(left&&parts.containsKey("left_arm"))before.put("left_arm",ModelState.state(parts.get("left_arm")));if(right&&parts.containsKey("right_arm"))before.put("right_arm",ModelState.state(parts.get("right_arm")));}
        @Override public void close(){
            Map<String,float[]> after=new LinkedHashMap<>();before.keySet().forEach(n->after.put(n,ModelState.state(parts.get(n))));
            float[] oldBody=before.get("body"),newBody=after.get("body");double rotationError=0,positionError=0;
            for(String name:before.keySet())if(!name.equals("body")){
                float[] old=before.get(name),now=after.get(name);
                for(int i=0;i<3;i++)positionError=Math.max(positionError,Math.abs(now[i]-(old[i]+newBody[i]-oldBody[i])));
                for(int i=3;i<9;i++)rotationError=Math.max(rotationError,Math.abs(now[i]-old[i]));
            }
            if(poses.size()<12)poses.add(Map.of("nativeBefore",before,"renderedAfter",after,"rotationScaleError",rotationError,"torsoAdjustedPositionError",positionError));
        }
    }
    private static final class Counts {int vertices,invalidVertices;}
    private record Counter(VertexConsumer delegate,Counts counts) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x,float y,float z){counts.vertices++;if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))counts.invalidVertices++;delegate.addVertex(x,y,z);return this;}
        @Override public VertexConsumer setColor(int r,int g,int b,int a){delegate.setColor(r,g,b,a);return this;}
        @Override public VertexConsumer setUv(float u,float v){delegate.setUv(u,v);return this;}
        @Override public VertexConsumer setUv1(int u,int v){delegate.setUv1(u,v);return this;}
        @Override public VertexConsumer setUv2(int u,int v){delegate.setUv2(u,v);return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){delegate.setNormal(x,y,z);return this;}
    }
}
