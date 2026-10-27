package dev.caleb.emfattachments.test;

import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import traben.entity_model_features.EMFAnimationApi;
import traben.entity_model_features.EMFAnimationApi.EMFAnimationHook.AnimationContext;
import traben.entity_model_features.models.animation.state.EMFState;

/** Opt-in, disposable-client A/B controls. Never enabled by normal gameplay. */
public final class BowVisibilityControls {
    public enum Mode {
        BASELINE, BODY_BACK_15, BODY_BACK_30, BODY_BACK_45, NATIVE_PIVOTS, NATIVE_BOW, VANILLA_MODEL, ZERO_BODY_OFFSET,
        NATIVE_BOW_ZERO_OFFSET, NATIVE_COMBAT_TRANSFORMS, VANILLA_MODEL_NATIVE_COMBAT, NATIVE_FIRST_PERSON_COMBAT, KEYFRAME_AIM_SEPARATED, HEAD_ANCHORED_KEYFRAMES, EYE_ANCHORED_KEYFRAMES, QUATERNION_EYE_ANCHORED, NATIVE_PA;
        public boolean vanillaBow() { return this==NATIVE_BOW || this==NATIVE_BOW_ZERO_OFFSET; }
        public boolean vanillaModel() { return this==VANILLA_MODEL || this==VANILLA_MODEL_NATIVE_COMBAT; }
        public boolean zeroOffset() { return this==ZERO_BODY_OFFSET || this==NATIVE_BOW_ZERO_OFFSET; }
        public boolean nativeCombat() { return this==NATIVE_COMBAT_TRANSFORMS || this==VANILLA_MODEL_NATIVE_COMBAT || this==NATIVE_FIRST_PERSON_COMBAT || this==KEYFRAME_AIM_SEPARATED || this==HEAD_ANCHORED_KEYFRAMES || this==EYE_ANCHORED_KEYFRAMES || this==QUATERNION_EYE_ANCHORED; }
    }
    private static boolean enabled;
    private static Mode mode=Mode.BASELINE;
    private static int pivotApplications, nativeArmApplications, vanillaModelDecisions, offsetApplications, nativeCombatDecisions;
    private static Map<String,Object> armSnapshot=Map.of();
    private static Vec3 inputOffset=Vec3.ZERO;
    private BowVisibilityControls() {}

    public static void register() {
        try {
            // Official API restores each entity's ordinary model automatically when
            // the condition is false. Shadows/F5/remote models keep their own choice.
            EMFAnimationApi.registerVanillaModelCondition(entity -> {
                if (!localBody() || entity!=(Object)Minecraft.getInstance().player || !mode.vanillaModel()) return false;
                vanillaModelDecisions++; return true;
            });
            EMFAnimationApi.registerPauseCondition(entity -> enabled && mode.vanillaModel()
                    && entity==(Object)Minecraft.getInstance().player && localBody());
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    public static void begin(Mode next) { mode=next;enabled=true;NativeFirstPersonCombatControl.begin(next==Mode.NATIVE_FIRST_PERSON_COMBAT || next==Mode.KEYFRAME_AIM_SEPARATED || next==Mode.HEAD_ANCHORED_KEYFRAMES || next==Mode.EYE_ANCHORED_KEYFRAMES || next==Mode.QUATERNION_EYE_ANCHORED,next==Mode.KEYFRAME_AIM_SEPARATED || next==Mode.HEAD_ANCHORED_KEYFRAMES || next==Mode.EYE_ANCHORED_KEYFRAMES || next==Mode.QUATERNION_EYE_ANCHORED,next==Mode.QUATERNION_EYE_ANCHORED);BowHeadAnchorControl.begin(next==Mode.HEAD_ANCHORED_KEYFRAMES || next==Mode.EYE_ANCHORED_KEYFRAMES || next==Mode.QUATERNION_EYE_ANCHORED,next==Mode.EYE_ANCHORED_KEYFRAMES || next==Mode.QUATERNION_EYE_ANCHORED);resetFrame(); }
    public static void end() { enabled=false;mode=Mode.BASELINE;NativeFirstPersonCombatControl.begin(false,false);BowHeadAnchorControl.begin(false);resetFrame(); }
    public static void resetFrame() {
        pivotApplications=nativeArmApplications=vanillaModelDecisions=offsetApplications=nativeCombatDecisions=0;
        inputOffset=Vec3.ZERO;armSnapshot=Map.of();NativeFirstPersonCombatControl.resetFrame();BowHeadAnchorControl.resetFrame();
    }
    private static boolean localBody() {
        return enabled && FirstPersonRenderPolicy.isLocalBody(Minecraft.getInstance().player);
    }
    public static boolean useNativeCombatTransforms() {
        if(!localBody() || !mode.nativeCombat()) return false;
        nativeCombatDecisions++; return true;
    }
    public static Vec3 originalOffset(Vec3 original) {
        if(!enabled || !FirstPersonRenderPolicy.isLocalFirstPersonCamera(Minecraft.getInstance().player)) return original;
        inputOffset=original;
        double clearance=switch(mode){case BODY_BACK_15->.15;case BODY_BACK_30->.30;case BODY_BACK_45->.45;default->0;};
        if(clearance!=0){var p=Minecraft.getInstance().player;double yaw=Math.toRadians(p.getYRot());
            offsetApplications++;return original.add(Math.sin(yaw)*clearance*p.getScale(),0,-Math.cos(yaw)*clearance*p.getScale());}
        if(!mode.zeroOffset()) return original;
        offsetApplications++;return Vec3.ZERO;
    }
    public static void afterBridge(AnimationContext context) {
        if(!localBody() || context.activeState()==null || !context.animatingModelRoot().isMainModel
                || !context.activeState().uuid().equals(Minecraft.getInstance().player.getUUID())) return;
        if(mode!=Mode.NATIVE_PIVOTS && !mode.vanillaBow()) return;
        var sample=NeaProjectilePoseProbe.snapshot(Minecraft.getInstance().player.getUUID());
        if(!(sample.get("frame") instanceof Number frame) || frame.floatValue()!=EMFState.getFrameCounter()
                || !(sample.get("neaArms") instanceof Map<?,?> nativeArms)) return;
        var parts=context.animatingModelRoot().getAllVanillaPartsByNameEMF();
        ModelPart left=parts.get("left_arm"),right=parts.get("right_arm");
        if(left==null || right==null) return;
        Map<String,Object> observed=new LinkedHashMap<>();
        observed.put("before",Map.of("left",ModelState.state(left),"right",ModelState.state(right)));
        apply(left,(List<?>)nativeArms.get("left"),mode.vanillaBow());
        apply(right,(List<?>)nativeArms.get("right"),mode.vanillaBow());
        // Outer skin layers follow the same shoulder transform, retaining visibility.
        ModelPart sleeve=parts.get("left_sleeve");if(sleeve!=null) sleeve.copyFrom(left);
        sleeve=parts.get("right_sleeve");if(sleeve!=null) sleeve.copyFrom(right);
        observed.put("after",Map.of("left",ModelState.state(left),"right",ModelState.state(right)));
        observed.put("native",nativeArms);armSnapshot=observed;
        if(mode.vanillaBow()) nativeArmApplications++;else pivotApplications++;
    }
    private static void apply(ModelPart part,List<?> values,boolean rotations) {
        part.x=value(values,0);part.y=value(values,1);part.z=value(values,2);
        part.xScale=value(values,6);part.yScale=value(values,7);part.zScale=value(values,8);
        if(rotations) {part.xRot=value(values,3);part.yRot=value(values,4);part.zRot=value(values,5);}
    }
    private static float value(List<?> values,int i) {return ((Number)values.get(i)).floatValue();}
    public static Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();result.put("mode",mode.name());
        result.put("pivotApplications",pivotApplications);result.put("nativeArmApplications",nativeArmApplications);
        result.put("vanillaModelDecisions",vanillaModelDecisions);result.put("offsetApplications",offsetApplications);
        result.put("nativeCombatDecisions",nativeCombatDecisions);result.put("offsetInput",List.of(inputOffset.x,inputOffset.y,inputOffset.z));
        result.put("armOverride",armSnapshot);result.put("nativeFirstPersonAim",NativeFirstPersonCombatControl.snapshot());result.put("headAnchor",BowHeadAnchorControl.snapshot());return result;
    }
}
