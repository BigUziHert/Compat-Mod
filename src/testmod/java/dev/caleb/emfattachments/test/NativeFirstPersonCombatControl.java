package dev.caleb.emfattachments.test;

import dev.caleb.emfattachments.client.FirstPersonCombat;
import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import dev.caleb.emfattachments.compat.BetterCombatAttackAccess;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import dev.kosmx.playerAnim.api.TransformType;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Matrix3f;
import java.util.ArrayList;

/** Test-only aim controls; native keyframe channels, modifier fades and renderer ownership stay intact. */
public final class NativeFirstPersonCombatControl {
    private static boolean enabled;
    private static boolean separateAim,quaternionAim;
    private static int quaternionCalls,quaternionModifications;
    private static boolean zeroAimExact=true;
    private static final Map<String,Object> quaternionTransforms=new LinkedHashMap<>();
    private static final List<String> quaternionFailures=new ArrayList<>();
    private static int calls;
    private static boolean restored = true;
    private static final Map<String,Object> modifiers = new LinkedHashMap<>();
    private static final Map<String,Object> originalModifiers = new LinkedHashMap<>();
    private NativeFirstPersonCombatControl() {}

    public static void begin(boolean active,boolean separate) {begin(active,separate,false);}
    public static void begin(boolean active,boolean separate,boolean quaternion) {enabled=active;separateAim=separate;quaternionAim=quaternion;resetFrame();}
    public static void resetFrame() { calls=0;restored=true;modifiers.clear();originalModifiers.clear();quaternionCalls=quaternionModifications=0;zeroAimExact=true;quaternionTransforms.clear();quaternionFailures.clear(); }

    public static Object nativeAdjustment(Object modifier, String part, Supplier<Object> original) {
        var player=Minecraft.getInstance().player;
        if (!enabled || !FirstPersonRenderPolicy.isLocalBody(player)
                || !(player instanceof BetterCombatAttackAccess access)
                || access.emfattachments$attackStack().adjustmentModifier!=modifier
                || !FirstPersonCombat.ownsCombatRendering()) return original.get();
        boolean previous=FirstPersonMode.isFirstPersonPass();
        Object result;
        try {
            if(!separateAim) FirstPersonMode.setFirstPersonPass(true);
            // Evaluate the installed BC selector and installed EMF wrapper first.
            // Only the native-FP control changes the thread-local during this pure call.
            result=original.get();
            if(separateAim) {
                if(result instanceof Optional<?> optional && optional.orElse(null) instanceof AdjustmentModifier.PartModifier adjustment) {
                    originalModifiers.put(part,Map.of("rotation",List.of(adjustment.rotation().getX(),adjustment.rotation().getY(),adjustment.rotation().getZ()),
                            "offset",List.of(adjustment.offset().getX(),adjustment.offset().getY(),adjustment.offset().getZ())));
                    Float replacement=switch(part) {
                        case "body","leftLeg","rightLeg" -> 0F;
                        case "leftArm","rightArm" -> (float)Math.toRadians(player.getXRot());
                        default -> null;
                    };
                    if(replacement!=null) result=Optional.of(new AdjustmentModifier.PartModifier(
                            new Vec3f(replacement,adjustment.rotation().getY(),adjustment.rotation().getZ()),
                            adjustment.scale(),adjustment.offset()));
                }
            }
        } finally {
            FirstPersonMode.setFirstPersonPass(previous);
            restored &= FirstPersonMode.isFirstPersonPass()==previous;
        }
        calls++;
        if (result instanceof Optional<?> optional && optional.orElse(null) instanceof AdjustmentModifier.PartModifier adjustment) {
            modifiers.put(part,Map.of("present",true,
                    "rotation",List.of(adjustment.rotation().getX(),adjustment.rotation().getY(),adjustment.rotation().getZ()),
                    "offset",List.of(adjustment.offset().getX(),adjustment.offset().getY(),adjustment.offset().getZ()),
                    "scale",List.of(adjustment.scale().getX(),adjustment.scale().getY(),adjustment.scale().getZ())));
        } else modifiers.put(part,Map.of("present",false));
        return result;
    }

    /** Composition occurs at the native adjustment boundary, with its actual fade already computed. */
    public static Vec3f composeRotation(Object modifier,String part,Vec3f vector,TransformType type,
            AdjustmentModifier.PartModifier adjustment,float fade,Supplier<Vec3f> original) {
        var player=Minecraft.getInstance().player;
        if(!enabled||!quaternionAim||type!=TransformType.ROTATION||!part.endsWith("Arm")
                ||!FirstPersonRenderPolicy.isLocalBody(player)||!(player instanceof BetterCombatAttackAccess access)
                ||access.emfattachments$attackStack().adjustmentModifier!=modifier||!FirstPersonCombat.ownsCombatRendering()) return original.get();
        quaternionCalls++;
        if(adjustment.rotation().getY()!=0||adjustment.rotation().getZ()!=0||!Float.isFinite(fade)||fade<0||fade>1.000001F) {
            if(quaternionFailures.size()<8)quaternionFailures.add("Unexpected native arm adjustment/fade: "+part+"/"+fade);
            return original.get();
        }
        float aim=adjustment.rotation().getX()*fade;
        Vec3f result;
        if(aim==0) {
            result=original.get();
            zeroAimExact &= result.getX().floatValue()==vector.getX().floatValue()
                    &&result.getY().floatValue()==vector.getY().floatValue()
                    &&result.getZ().floatValue()==vector.getZ().floatValue();
        } else {
            Quaternionf authored=new Quaternionf().rotationZYX(vector.getZ(),vector.getY(),vector.getX());
            Quaternionf target=new Quaternionf().rotationX(aim).mul(authored);
            Vector3f euler=modelPartEuler(target);
            result=new Vec3f(euler.x,euler.y,euler.z);quaternionModifications++;
            Quaternionf recovered=new Quaternionf().rotationZYX(euler.z,euler.y,euler.x);
            if(!euler.isFinite()||Math.abs(recovered.dot(target))<.999999F) {
                if(quaternionFailures.size()<8)quaternionFailures.add("Quaternion/ZYX reconstruction failed for "+part);
                return original.get();
            }
        }
        quaternionTransforms.put(part,Map.of("authoredEuler",List.of(vector.getX(),vector.getY(),vector.getZ()),
                "nativeAim",adjustment.rotation().getX(),"nativeFade",fade,"appliedAim",aim,
                "resultEuler",List.of(result.getX(),result.getY(),result.getZ())));
        return result;
    }

    /** Extract ModelPart's Rz*Ry*Rx convention from the matrix, including its singular case.
     * JOML 1.10.5 Quaternionf.getEulerAnglesZYX has an incorrect X denominator for nonzero Y. */
    public static Vector3f modelPartEuler(Quaternionf rotation) {
        Matrix3f m=new Matrix3f().rotation(rotation);
        double cosineY=Math.hypot(m.m00(),m.m01());
        float y=(float)Math.atan2(-m.m02(),cosineY);
        float x,z;
        if(cosineY>1e-6) {x=(float)Math.atan2(m.m12(),m.m22());z=(float)Math.atan2(m.m01(),m.m00());}
        else {x=(float)Math.atan2(-m.m21(),m.m11());z=0;}
        return new Vector3f(x,y,z);
    }

    public static Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();result.put("calls",calls);result.put("threadLocalRestored",restored);
        result.put("firstPersonPassAtCapture",FirstPersonMode.isFirstPersonPass());result.put("modifiers",new LinkedHashMap<>(modifiers));
        result.put("originalModifiers",new LinkedHashMap<>(originalModifiers));
        result.put("profile",quaternionAim?"PREMULTIPLIED_ARM_AIM":separateAim?"KEYFRAME_AIM_SEPARATED":"NATIVE_FIRST_PERSON");
        result.put("quaternionCalls",quaternionCalls);result.put("quaternionModifications",quaternionModifications);
        result.put("zeroAimExact",zeroAimExact);result.put("quaternionTransforms",new LinkedHashMap<>(quaternionTransforms));
        result.put("quaternionFailures",List.copyOf(quaternionFailures));return result;
    }
    public static void assertQuaternionFrame(boolean bodyActive,float pitch) {
        if(!quaternionFailures.isEmpty()||!zeroAimExact)throw new AssertionError("Quaternion diagnostic failed: "+snapshot());
        if(bodyActive) {
            if(quaternionCalls<2||(!quaternionTransforms.containsKey("leftArm"))||!quaternionTransforms.containsKey("rightArm"))
                throw new AssertionError("Both native arm rotation adjustments were not observed: "+snapshot());
            if(pitch==0&&quaternionModifications!=0)throw new AssertionError("Zero aim changed native arm rotations");
            if(pitch!=0&&quaternionModifications<2)throw new AssertionError("Nonzero native aim was not composed");
        } else if(quaternionCalls!=0||quaternionModifications!=0)throw new AssertionError("Quaternion correction leaked outside local owned body");
    }

    /** Source-level native formula is an assertion only; it never supplies the transform. */
    public static void assertExactNativeAim(float pitchDegrees) {
        if (!(modifiers.get("body") instanceof Map<?,?> body) || !Boolean.TRUE.equals(body.get("present")))
            throw new AssertionError("Native FP body adjustment absent: "+modifiers);
        var rotation=(List<?>)body.get("rotation");var offset=(List<?>)body.get("offset");
        float pitch=(float)Math.toRadians(pitchDegrees);
        float lookup=!separateAim&&pitch<0?(float)Math.abs(Math.sin(pitch)):0;
        near(rotation.get(0),separateAim?0:-pitch,"body pitch");near(rotation.get(1),0,"body yaw");near(rotation.get(2),0,"body roll");
        near(offset.get(0),0,"body X");near(offset.get(1),lookup*.5F,"body Y");near(offset.get(2),-lookup,"body Z");
        for(String part:List.of("leftArm","rightArm","leftLeg","rightLeg","torso")) {
            if(modifiers.get(part) instanceof Map<?,?> adjustment && Boolean.TRUE.equals(adjustment.get("present"))) {
                if(!separateAim||part.equals("torso")) throw new AssertionError("Unexpected aim adjustment "+part+": "+adjustment);
                var partRotation=(List<?>)adjustment.get("rotation");
                near(partRotation.get(0),part.endsWith("Arm")?pitch:0,part+" separated pitch");
                near(partRotation.get(1),0,part+" yaw");near(partRotation.get(2),0,part+" roll");
            } else if(separateAim&&!part.equals("torso")) throw new AssertionError("Separated native part adjustment absent: "+part);
        }
    }

    private static void near(Object value,float expected,String label) {
        float actual=((Number)value).floatValue();
        if(!Float.isFinite(actual)||Math.abs(actual-expected)>.000001F)
            throw new AssertionError("Unexpected installed native FP "+label+": "+actual+" != "+expected);
    }
}
