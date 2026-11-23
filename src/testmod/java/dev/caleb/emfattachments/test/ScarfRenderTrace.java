package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import traben.entity_model_features.models.IEMFModel;
import traben.entity_model_features.models.animation.state.EMFState;
import traben.entity_model_features.models.parts.EMFModelPartRoot;
import traben.entity_model_features.utils.EMFEntity;
import java.util.*;

public final class ScarfRenderTrace {
    private static final List<String> VARIABLES=List.of("var.Created_by_FreshLX_for_Fresh_Animations","var.frame_counter_prev",
            "var.mvmnt_bodyry","var.mvmnt_bodyrz","var.mvmnt_bodyty","var.walk","var.ls","var.pre_posy","var.t_land");
    static Player target;
    static List<Map<String,Object>> rows;
    private static Map<String,Object> frame;
    private static Map<String,Object> poses;
    private static EMFModelPartRoot playerRoot;
    private static AttachmentTestMod.TestResult geometry;
    private static int rootCalls;
    private static int baseDraws;

    private ScarfRenderTrace() {}

    public static void begin(LivingEntity entity,Object model) {
        if (target!=entity || rows==null || rows.size()>=220 || frame!=null) return;
        if (net.neoforged.fml.ModList.get().isLoaded("iris") && net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass()) return;
        frame=new LinkedHashMap<>(); poses=new LinkedHashMap<>();geometry=new AttachmentTestMod.TestResult();
        baseDraws=rootCalls=0;
        playerRoot=model instanceof IEMFModel emf && emf.emf$isEMFModel()?emf.emf$getEMFRootModel():null;
        frame.put("tick",entity.tickCount); frame.put("nano",System.nanoTime());frame.put("joints",poses);
        frame.put("uuid",entity.getUUID());frame.put("actorName",target.getGameProfile().getName());
        frame.put("actualRemote", entity instanceof net.minecraft.client.player.RemotePlayer);
        frame.put("camera",Minecraft.getInstance().options.getCameraType().name());
        frame.put("invisible",entity.isInvisible());frame.put("invisibleToViewer",entity.isInvisibleTo(Minecraft.getInstance().player));
        frame.put("crouching",entity.isCrouching());frame.put("walkingSpeed",entity.walkAnimation.speed());
        frame.put("walkPosition",entity.walkAnimation.position());frame.put("position",List.of(entity.getX(),entity.getY(),entity.getZ()));
        frame.put("varsBeforeSetup",variables());frame.put("isEMFParent",playerRoot!=null);
    }

    public static void checkpoint(String name,Object model) {
        if(frame==null || poses.containsKey(name) || !(model instanceof HumanoidModel<?> h))return;
        poses.put(name,Map.of("body",ModelState.state(h.body),"head",ModelState.state(h.head),
                "rightArm", ModelState.state(h.rightArm),"leftArm",ModelState.state(h.leftArm),
                "rightLeg",ModelState.state(h.rightLeg),"leftLeg",ModelState.state(h.leftLeg)));
        if(name.equals("afterSetup")||name.startsWith("armorDraw")||name.contains("backpack"))
            frame.put("varsAt_"+name,variables());
    }

    public static void renderType(RenderType type) {if(frame!=null)frame.put("nullBaseRenderType",type==null);}
    public static VertexConsumer base(Model model, VertexConsumer sink) {
        if(frame==null)return sink;
        baseDraws++;checkpoint("beforeBodyDraw",model);
        return new CountingVertexConsumer(sink,geometry);
    }

    public static void root(EMFModelPartRoot model) {
        if(frame!=null && model==playerRoot && EMFState.isMainPhase && EMFState.state()!=null
                && EMFState.state().emfEntity()==target)rootCalls++;
    }

    private static Map<String,Float> variables() {
        var vm=((EMFEntity)target).emf$getVariableMap();
        var result=new LinkedHashMap<String,Float>();
        for(var key:VARIABLES){Float value=vm.get(key); if(value!=null&&Float.isFinite(value))result.put(key,value);}
        return result;
    }

    public static void end(LivingEntity entity) {
        if(frame==null || entity!=target)return;
        frame.put("bodyDraws",baseDraws);frame.put("bodyVertices",geometry.vertices);
        frame.put("mainPlayerRootAnimateCalls",rootCalls);frame.put("varsAfterLayers",variables());
        rows.add(frame);frame=null;poses=null;geometry=null;playerRoot=null;
    }
}
