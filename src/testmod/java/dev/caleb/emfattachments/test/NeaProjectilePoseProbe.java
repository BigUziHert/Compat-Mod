package dev.caleb.emfattachments.test;

import dev.tr7zw.notenoughanimations.api.BasicAnimation;
import dev.tr7zw.notenoughanimations.versionless.animations.BodyPart;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.IdentityHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import strm.emfcompat.core.PoseManager;
import strm.emfcompat.core.PoseSnapshot;
import strm.emfcompat.core.PoseInterpolator;
import net.neoforged.fml.ModList;
import strm.neaemfcompat.compat.EMFCompat;
import traben.entity_model_features.EMFAnimationApi.EMFAnimationHook.AnimationContext;
import traben.entity_model_features.models.animation.state.EMFState;

/** Records real selected NEA poses before FA, immediately after FA, and after bridge restoration. */
public final class NeaProjectilePoseProbe {
    private static final Map<UUID,Map<String,Object>> samples = new LinkedHashMap<>();
    private static final Map<UUID,List<Map<String,Object>>> passTimelines = new LinkedHashMap<>();
    private static final Map<Model,ArmorCopy> nativeArmorCopies = new IdentityHashMap<>();
    private static float frame = Float.NaN;
    private NeaProjectilePoseProbe() {}

    public static void selected(AbstractClientPlayer player, PlayerModel<?> model, BasicAnimation[] selected) {
        ArmorMeshProbe.skin(player,model);
        if (frame != EMFState.getFrameCounter()) { samples.clear();passTimelines.clear();nativeArmorCopies.clear(); frame = EMFState.getFrameCounter(); }
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("frame",frame); row.put("localPlayer",player==Minecraft.getInstance().player);
        row.put("cameraType",Minecraft.getInstance().options.getCameraType().name());
        row.put("usingItem",player.isUsingItem()); row.put("useElapsed",player.getTicksUsingItem());
        row.put("swinging",player.swinging);
        row.put("useAnimation",player.getUseItem().getUseAnimation().name());
        row.put("bowSetting",dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.bowAnimation.name());
        row.put("neaArms",arms(model.leftArm,model.rightArm));
        Map<String,Object> winners = new LinkedHashMap<>();
        for (BodyPart arm: List.of(BodyPart.LEFT_ARM,BodyPart.RIGHT_ARM)) {
            BasicAnimation animation=selected==null?null:selected[arm.ordinal()];
            winners.put(arm.name(),Map.of("animation",animation==null?"":animation.getClass().getName(),
                    "bridgeRecognizes",EMFCompat.shouldPauseForAnimation(animation)));
        }
        row.put("selected",winners); samples.put(player.getUUID(),row);
        var trace=pass(player.getUUID(),"after_nea");trace.put("modelArms",arms(model.leftArm,model.rightArm));
    }

    public static void emf(AnimationContext context, boolean afterBridge) {
        if(context.activeState()==null || !context.animatingModelRoot().isMainModel) return;
        Map<String,Object> row=samples.get(context.activeState().uuid());
        if(row==null || frame!=EMFState.getFrameCounter()) return;
        var parts=context.animatingModelRoot().getAllVanillaPartsByNameEMF();
        ModelPart left=parts.get("left_arm"), right=parts.get("right_arm");
        if(left==null||right==null) return;
        row.put(afterBridge?"afterBridgeArms":"afterFaArms",arms(left,right));
        var saved=PoseManager.getSavedPoses(context.activeState().uuid());
        row.put("bridgeSavedArms",Map.of("left",saved!=null&&saved.leftArm()!=null,"right",saved!=null&&saved.rightArm()!=null));
        var trace=pass(context.activeState().uuid(),afterBridge?"after_bridge":"before_bridge");
        trace.put("modelArms",arms(left,right));trace.put("interpolatorActive",PoseInterpolator.isActive(context.activeState().uuid()));
        trace.put("bridgeFrame",PoseManager.currentFrame);
        if(saved!=null) {
            var exact=new LinkedHashMap<String,Object>();
            if(saved.leftArm()!=null) exact.put("left",snapshot(saved.leftArm()));
            if(saved.rightArm()!=null) exact.put("right",snapshot(saved.rightArm()));
            if(saved.parts()!=null) for(String name:List.of("left_arm","right_arm"))
                if(saved.parts().containsKey(name)) exact.put(name,snapshot(saved.parts().get(name)));
            trace.put("savedArms",exact);
        }
    }

    public static Map<String,Object> snapshot(UUID uuid) {
        Map<String,Object> row=samples.get(uuid);
        if(row==null)return Map.of("observed",false);
        var result=new LinkedHashMap<>(row);result.put("passTimeline",List.copyOf(passTimelines.getOrDefault(uuid,List.of())));result.put("armorMesh",ArmorMeshProbe.snapshot(uuid));return result;
    }
    public static void armor(HumanoidModel<?> model,boolean after) {
        var state=EMFState.state();
        if(state==null||state.uuid()==null||frame!=EMFState.getFrameCounter()||!samples.containsKey(state.uuid())) return;
        var trace=pass(state.uuid(),after?"after_armor_copy":"before_armor_copy");
        trace.put("modelClass",model.getClass().getName());trace.put("modelArms",arms(model.leftArm,model.rightArm));
        trace.put("leftVisible",model.leftArm.visible);trace.put("rightVisible",model.rightArm.visible);
        var saved=PoseManager.getSavedPoses(state.uuid());
        if(saved!=null) {
            var exact=new LinkedHashMap<String,Object>();
            if(saved.leftArm()!=null) exact.put("left",snapshot(saved.leftArm()));
            if(saved.rightArm()!=null) exact.put("right",snapshot(saved.rightArm()));trace.put("savedArms",exact);
        }
    }

    /** Witness the actual 1.21.1 armor path; EMFBipedPose's later-version hook is unused here. */
    public static void nativeArmorCopy(LivingEntity entity,EquipmentSlot slot,HumanoidModel<?> parent,HumanoidModel<?> model) {
        UUID uuid=entity.getUUID();
        if(frame!=EMFState.getFrameCounter()||!samples.containsKey(uuid)) return;
        ArmorMeshProbe.armor(entity,slot,model);
        var copy=new ArmorCopy(uuid,slot.getName(),arms(parent.leftArm,parent.rightArm));
        nativeArmorCopies.put(model,copy);
        var trace=pass(uuid,"native_armor_copy");nativeArmorData(trace,copy,model);
    }

    public static void nativeArmorDraw(Model model,boolean after) {
        if(frame!=EMFState.getFrameCounter()||!(model instanceof HumanoidModel<?> humanoid)) return;
        var copy=nativeArmorCopies.get(model);if(copy==null) return;
        var trace=pass(copy.uuid(),after?"after_native_armor_draw":"before_native_armor_draw");
        nativeArmorData(trace,copy,humanoid);
    }

    private static void nativeArmorData(Map<String,Object> trace,ArmorCopy copy,HumanoidModel<?> model) {
        trace.put("slot",copy.slot());trace.put("modelClass",model.getClass().getName());
        trace.put("parentArmsAtCopy",copy.arms());trace.put("modelArms",arms(model.leftArm,model.rightArm));
        trace.put("leftVisible",model.leftArm.visible);trace.put("rightVisible",model.rightArm.visible);
    }

    private record ArmorCopy(UUID uuid,String slot,Map<String,List<Float>> arms) {}

    private static Map<String,Object> pass(UUID uuid,String stage) {
        var mc=Minecraft.getInstance();var trace=new LinkedHashMap<String,Object>();
        trace.put("stage",stage);trace.put("emfFrame",EMFState.getFrameCounter());
        trace.put("camera",mc.options.getCameraType().name());trace.put("local",mc.player!=null&&uuid.equals(mc.player.getUUID()));
        trace.put("localBody",ModList.get().isLoaded("firstperson")&&mc.player!=null&&uuid.equals(mc.player.getUUID())
                &&dev.caleb.emfattachments.client.FirstPersonRenderPolicy.isLocalBody(mc.player));
        trace.put("shadow",ModList.get().isLoaded("iris")&&Shadow.active());
        passTimelines.computeIfAbsent(uuid,key->new ArrayList<>()).add(trace);return trace;
    }
    private static Map<String,Object> snapshot(PoseSnapshot pose) {
        return Map.of("transform",List.of(pose.x,pose.y,pose.z,pose.xRot,pose.yRot,pose.zRot,pose.xScale,pose.yScale,pose.zScale),
                "rotationOnly",pose.rotationOnly,"selfBlended",pose.selfBlended,"weight",pose.weight);
    }
    private static final class Shadow {
        private static boolean active() {return net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();}
    }

    private static Map<String,List<Float>> arms(ModelPart left,ModelPart right) {
        return Map.of("left",part(left),"right",part(right));
    }
    private static List<Float> part(ModelPart part) {
        return List.of(part.x,part.y,part.z,part.xRot,part.yRot,part.zRot,part.xScale,part.yScale,part.zScale);
    }
}
