package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.caleb.emfattachments.client.FirstPersonCombat;
import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import dev.kosmx.playerAnim.impl.Helper;
import dev.kosmx.playerAnim.impl.IMutableModel;
import dev.kosmx.playerAnim.impl.IUpperPartHelper;
import dev.kosmx.playerAnim.impl.animation.IBendHelper;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import traben.entity_model_features.models.IEMFModel;
import traben.entity_model_features.models.parts.EMFModelPartRoot;

/** Disposable A/B control. Every measured point is in the existing entity pose-stack coordinates. */
public final class BowHeadAnchorControl {
    private static boolean enabled;
    private static boolean eyeAnchor;
    private static Scope scope;
    private static int applications, rootPairs, layerChecks, negativeRenders;
    private static boolean restored=true;
    private static Map<String,Object> trace=Map.of();
    private static final List<String> failures=new ArrayList<>();
    private BowHeadAnchorControl() {}

    public static void begin(boolean active) { begin(active,false); }
    public static void begin(boolean active,boolean eye) { enabled=active;eyeAnchor=eye;resetFrame(); }
    public static void resetFrame() { applications=rootPairs=layerChecks=negativeRenders=0;restored=true;trace=Map.of();failures.clear(); }
    public static void render(AbstractClientPlayer entity,PoseStack pose,Runnable original) {
        if(!enabled) { original.run();return; }
        if(!FirstPersonRenderPolicy.isLocalBody(entity)||!FirstPersonCombat.ownsCombatRendering()) {
            negativeRenders++;original.run();return;
        }
        Scope previous=scope;scope=new Scope(entity,pose,new Matrix4f(pose.last().pose()));
        try { original.run(); }
        finally {
            restored &= matrixError(pose.last().pose(),scope.entry)<.00001F;
            scope=previous;
        }
    }
    public static void beforeRoot(AbstractClientPlayer entity,PoseStack pose) {
        if(scope!=null&&scope.entity==entity&&scope.pose==pose) scope.before=new Matrix4f(pose.last().pose());
    }
    public static void afterRoot(AbstractClientPlayer entity,PoseStack pose) {
        if(scope!=null&&scope.entity==entity&&scope.pose==pose) {scope.after=new Matrix4f(pose.last().pose());rootPairs++;}
    }
    public static void model(LivingEntity entity,EntityModel<?> model,PoseStack pose,Runnable original) {
        Scope s=scope;
        if(s==null||s.entity!=entity||s.pose!=pose) {original.run();return;}
        if(!(model instanceof HumanoidModel<?> human)||!(model instanceof IEMFModel emf)||!emf.emf$isEMFModel()) {
            failure("Head-anchor control requires actual EMF humanoid model");original.run();return;
        }
        s.human=human;s.root=emf.emf$getEMFRootModel();s.outer=pose.last();
        s.normal=new Matrix3f(s.outer.normal());s.model=new Matrix4f(s.outer.pose());
        try {original.run();}
        finally {
            if(s.applied&&matrixError(s.outer.pose(),s.corrected)>.00001F)
                failure("Main model changed anchored outer pose");
        }
    }
    public static void afterAnimation(EMFModelPartRoot root,PoseStack pose) {
        Scope s=scope;
        if(s==null||s.applied||s.root!=root||s.pose!=pose) return;
        if(s.before==null||s.after==null||pose.last()!=s.outer) {
            failure("Head anchor missing native root pair or initial outer stack identity");return;
        }
        var head=s.human.head;
        // FPM uses z=5000 as a hiding sentinel. Never turn that into a physical anchor.
        if(!Float.isFinite(head.z)||Math.abs(head.z)>100) {
            failure("Head anchor contains hidden/sentinel pivot: "+head.z);return;
        }
        PoseStack bend=new PoseStack();
        boolean nativeBend=Helper.isBendEnabled() && s.human instanceof IMutableModel mutable
                && mutable.getEmoteSupplier()!=null && mutable.getEmoteSupplier().get()!=null && mutable.getEmoteSupplier().get().isActive();
        if(nativeBend) IBendHelper.rotateMatrixStack(bend,((IMutableModel)s.human).getEmoteSupplier().get().getBend("body"));
        s.upperBend=new Matrix4f(bend.last().pose());
        PoseStack headLocal=new PoseStack();
        if(nativeBend && ((IUpperPartHelper)(Object)head).isUpperPart()) headLocal.last().pose().mul(s.upperBend);
        head.translateAndRotate(headLocal);
        Vector3f local=headLocal.last().pose().transformPosition(new Vector3f());
        Vector3f current=s.model.transformPosition(new Vector3f(local));
        Matrix4f rootRemoval=new Matrix4f(s.before).mul(new Matrix4f(s.after).invert());
        Vector3f reference=rootRemoval.transformPosition(new Vector3f(current));
        Vector3f headPivot=new Vector3f(local),headBefore=new Vector3f(current),headReference=new Vector3f(reference);
        float nativeEyeHeight=s.entity.getEyeHeight();
        if(eyeAnchor) {
            // Entry is captured BEFORE LivingEntityRenderer multiplies entity scale.
            // getEyeHeight() is already the current scaled EntityDimensions value.
            // No second multiplication by getScale(), and no guessed head pixels.
            reference=s.entry.transformPosition(new Vector3f(0,nativeEyeHeight,0));
            Matrix4f neutralModel=new Matrix4f(rootRemoval).mul(s.model);
            local=new Matrix4f(neutralModel).invert().transformPosition(new Vector3f(reference));
            current=s.model.transformPosition(new Vector3f(local));
        }
        Vector3f delta=new Vector3f(reference).sub(current);
        if(!delta.isFinite()) {failure("Nonfinite native head-anchor displacement");return;}
        // Premultiply one translation: every child/layer inherits it; all native rotations remain.
        s.outer.pose().translateLocal(delta.x,delta.y,delta.z);
        s.corrected=new Matrix4f(s.outer.pose());
        Vector3f corrected=s.corrected.transformPosition(new Vector3f(local));
        float anchorError=corrected.distance(reference);
        float linearError=linearError(s.model,s.corrected);
        float normalError=normalError(s.normal,s.outer.normal());
        if(!Float.isFinite(anchorError)||anchorError>.00002F||linearError!=0||normalError!=0) {
            s.outer.pose().set(s.model);failure("Head anchor changed rotations/normals or failed equality: "+anchorError+"/"+linearError+"/"+normalError);return;
        }
        s.applied=true;applications++;
        var row=new LinkedHashMap<String,Object>();row.put("anchorKind",eyeAnchor?"NATIVE_ENTITY_EYE":"EMF_HEAD_PIVOT");row.put("entryBeforeEntityScale",matrix(s.entry));row.put("nativeEyeHeight",nativeEyeHeight);row.put("nativePose",s.entity.getPose().name());row.put("rootBefore",matrix(s.before));row.put("rootAfter",matrix(s.after));
        row.put("modelBefore",matrix(s.model));row.put("modelAfter",matrix(s.corrected));
        row.put("nativeUpperBend",nativeBend);row.put("upperBendMatrix",matrix(s.upperBend));row.put("headLocalMatrix",matrix(headLocal.last().pose()));row.put("headPivot",vec(headPivot));
        row.put("headBefore",vec(headBefore));row.put("headNeutralReference",vec(headReference));row.put("headAfter",vec(s.corrected.transformPosition(new Vector3f(headPivot))));
        row.put("anchorLocal",vec(local));row.put("anchorBefore",vec(current));row.put("anchorNeutralReference",vec(reference));row.put("anchorAfter",vec(corrected));
        row.put("translation",vec(delta));row.put("anchorError",anchorError);row.put("linearError",linearError);row.put("normalError",normalError);
        row.put("sameOuterPose",pose.last()==s.outer);row.put("headClass",head.getClass().getName());
        row.put("scale",s.entity.getScale());row.put("pitch",s.entity.getXRot());trace=row;
    }
    public static void beforeLayer(LivingEntity entity,PoseStack pose) {
        Scope s=scope;
        if(s==null||s.entity!=entity||!s.applied) return;
        if(matrixError(s.outer.pose(),s.corrected)>.00001F) {failure("Layer outer base lost anchored matrix");return;}
        float ordinaryError=matrixError(pose.last().pose(),s.corrected);
        float upperError=matrixError(pose.last().pose(),new Matrix4f(s.corrected).mul(s.upperBend));
        if(Math.min(ordinaryError,upperError)>.00002F) {failure("Layer matrix differs from anchored base and its native upper-body bend: "+ordinaryError+"/"+upperError);return;}
        layerChecks++;
    }
    public static Map<String,Object> snapshot() {
        return Map.of("applications",applications,"rootPairs",rootPairs,"layerChecks",layerChecks,
                "negativeRenders",negativeRenders,"outerPoseRestored",restored,"trace",trace,"failures",List.copyOf(failures));
    }
    public static void assertFrame(boolean bodyActive) {
        if(!failures.isEmpty())throw new AssertionError("Head-anchor diagnostic failed: "+failures);
        if(!restored||scope!=null)throw new AssertionError("Head anchor scope/pop was not restored");
        if(bodyActive) {
            if(applications!=1||rootPairs!=1||layerChecks==0)throw new AssertionError("Expected one measured head anchor shared by layers: "+snapshot());
        } else if(applications!=0||rootPairs!=0||layerChecks!=0)throw new AssertionError("Head anchor leaked outside active local FP body: "+snapshot());
    }
    private static void failure(String message) {if(failures.size()<8&&!failures.contains(message))failures.add(message);}
    private static List<Float> vec(Vector3f p){return List.of(p.x,p.y,p.z);}
    private static List<Float> matrix(Matrix4f m){float[] f=new float[16];m.get(f);List<Float>r=new ArrayList<>();for(float v:f)r.add(v);return r;}
    private static float matrixError(Matrix4f a,Matrix4f b){float e=0;for(int c=0;c<4;c++)for(int r=0;r<4;r++)e=Math.max(e,Math.abs(a.get(c,r)-b.get(c,r)));return e;}
    private static float linearError(Matrix4f a,Matrix4f b){float e=0;for(int c=0;c<3;c++)for(int r=0;r<3;r++)e=Math.max(e,Math.abs(a.get(c,r)-b.get(c,r)));return e;}
    private static float normalError(Matrix3f a,Matrix3f b){float e=0;for(int c=0;c<3;c++)for(int r=0;r<3;r++)e=Math.max(e,Math.abs(a.get(c,r)-b.get(c,r)));return e;}
    private static final class Scope {
        final AbstractClientPlayer entity;final PoseStack pose;final Matrix4f entry;
        Matrix4f before,after,model,corrected,upperBend;Matrix3f normal;PoseStack.Pose outer;
        HumanoidModel<?> human;EMFModelPartRoot root;boolean applied;
        Scope(AbstractClientPlayer e,PoseStack p,Matrix4f m){entity=e;pose=p;entry=m;}
    }
}
