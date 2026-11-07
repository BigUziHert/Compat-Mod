package dev.caleb.emfattachments.test;

import dev.caleb.emfattachments.client.BodyRenderScope;
import dev.caleb.emfattachments.client.FirstPersonArmGeometry;
import dev.caleb.emfattachments.client.ScopedGeometryMask;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import dev.tr7zw.firstperson.InventoryUtil;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import dev.tr7zw.firstperson.versionless.mixinbase.ModelPartBase;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.redspace.irons_artifice.item.GunItem;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Passive live diagnostics: no render flags, scope state or model fields are changed. */
public final class ArtificeBodyMaskProbe {
    private static boolean recording;
    private ArtificeBodyMaskProbe() {}

    private static Object field(Class<?> owner,Object instance,String name)throws ReflectiveOperationException{
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(instance);
    }
    private static Object current()throws ReflectiveOperationException{
        return ((ThreadLocal<?>)field(FirstPersonArmGeometry.class,null,"CURRENT")).get();
    }
    @SuppressWarnings("unchecked") private static boolean masked(ModelPart part)throws ReflectiveOperationException{
        return ((ScopedGeometryMask<ModelPart>)field(FirstPersonArmGeometry.class,null,"MASK")).contains(part);
    }
    private static String identity(Object object){return object==null?"null":object.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(object));}
    private static boolean enabled(){var p=Minecraft.getInstance().player;return !recording&&p!=null&&ArtificeRagdollRenderProbe.local(p.getUUID());}
    private static void state(Map<String,Object> row,LivingEntity entity)throws ReflectiveOperationException{
        var mc=Minecraft.getInstance();var core=FirstPersonModelCore.instance;Object scope=current();
        LivingEntity owner=scope==null?null:(LivingEntity)field(scope.getClass(),scope,"entity");
        row.put("entity",identity(entity));row.put("entityIsLocalPlayer",entity==mc.player);
        row.put("localBody",BodyRenderScope.isLocalBody(entity));row.put("localFirstPersonCamera",BodyRenderScope.isLocalFirstPersonCamera(entity));
        row.put("nativeEnabled",FirstPersonAPI.isEnabled());row.put("nativeRenderingPlayer",FirstPersonAPI.isRenderingPlayer());
        row.put("nativeRenderingPlayerRaw",core.isRenderingPlayerRaw());row.put("cameraEntityExtract",FirstPersonModelCore.cameraEntityExtract);
        row.put("cameraType",mc.options.getCameraType().name());row.put("cameraDetached",mc.gameRenderer.getMainCamera().isDetached());
        row.put("currentScope",identity(scope));row.put("currentOwned",scope!=null&&(boolean)field(scope.getClass(),scope,"owned"));
        row.put("currentEntity",identity(owner));row.put("currentLocalBody",BodyRenderScope.isLocalBody(owner));
        row.put("mainGun",entity!=null&&entity.getMainHandItem().getItem() instanceof GunItem);
        row.put("offhandGun",entity!=null&&entity.getOffhandItem().getItem() instanceof GunItem);
        if(entity instanceof Player player){
            var inventory=InventoryUtil.getInventory(player);var main=InventoryUtil.getSelected(inventory);var offhand=InventoryUtil.getOffhand(inventory);
            row.put("inventoryMainGun",main.getItem() instanceof GunItem);row.put("inventoryOffhandGun",offhand.getItem() instanceof GunItem);
            row.put("inventoryMainItem",BuiltInRegistries.ITEM.getKey(main.getItem()).toString());row.put("inventoryOffhandItem",BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString());
            row.put("maskedMainItem",BuiltInRegistries.ITEM.getKey(entity.getMainHandItem().getItem()).toString());row.put("maskedOffhandItem",BuiltInRegistries.ITEM.getKey(entity.getOffhandItem().getItem()).toString());
        }
        if(entity!=null){row.put("nativeShowVanillaHands",core.getLogicHandler().showVanillaHands(entity));row.put("nativeHideArmsAndItems",core.getLogicHandler().hideArmsAndItems(entity));}
        row.put("irisShadow",false); // enabled() excludes the real Iris shadow pass.
    }
    private static void record(String event,Recorder recorder){
        if(!enabled())return;recording=true;var row=new LinkedHashMap<String,Object>();
        try{recorder.fill(row);}catch(Throwable e){row.put("diagnosticError",e.toString());}
        finally{recording=false;}
        ArtificeRagdollRenderProbe.bodyArmDecision(event,row);
    }
    public static void open(String boundary,LivingEntity entity,EntityModel<?> model){
        if(entity!=Minecraft.getInstance().player)return;
        if(enabled()&&boundary.equals("before")&&BodyRenderScope.isLocalBody(entity)&&model instanceof HumanoidModel<?> humanoid)ArtificeBodyGeometryProbe.seed(humanoid);
        record("open_"+boundary,row->{state(row,entity);row.put("model",identity(model));
            if(model instanceof HumanoidModel<?> humanoid){row.put("rightArm",identity(humanoid.rightArm));row.put("leftArm",identity(humanoid.leftArm));row.put("rightMasked",masked(humanoid.rightArm));row.put("leftMasked",masked(humanoid.leftArm));row.put("rightVisible",humanoid.rightArm.visible);row.put("leftVisible",humanoid.leftArm.visible);row.put("rightNativeHidden",((ModelPartBase)(Object)humanoid.rightArm).isHidden());row.put("leftNativeHidden",((ModelPartBase)(Object)humanoid.leftArm).isHidden());}
        });
    }
    public static void copied(String boundary,ModelPart source,ModelPart target){
        if(enabled()&&boundary.equals("after")&&BodyRenderScope.isLocalBody(Minecraft.getInstance().player))ArtificeBodyGeometryProbe.copied(source,target);
        record("copy_"+boundary,row->{state(row,Minecraft.getInstance().player);row.put("source",identity(source));row.put("target",identity(target));row.put("sourceMasked",masked(source));row.put("targetMasked",masked(target));});
    }
    public static ArtificeBodyGeometryProbe.Draw draw(ModelPart part,VertexConsumer vertices){
        return enabled()&&BodyRenderScope.isLocalBody(Minecraft.getInstance().player)?ArtificeBodyGeometryProbe.draw(part,vertices):null;
    }
    public static void hides(ModelPart part,boolean result){
        record("hides",row->{state(row,Minecraft.getInstance().player);row.put("part",identity(part));row.put("membership",masked(part));row.put("result",result);row.put("nativeHidden",((ModelPartBase)(Object)part).isHidden());row.put("visible",part.visible);row.put("partZ",part.z);});
    }
    @FunctionalInterface private interface Recorder{void fill(Map<String,Object> row)throws ReflectiveOperationException;}
}
