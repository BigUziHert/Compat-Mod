package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import dev.caleb.emfattachments.client.FirstPersonArmGeometry;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Read-only live compile/attempt witnesses. Never wraps vertex consumers or changes geometry. */
public final class ArmVisibilityMatrixProbe {
    private static boolean enabled;
    private static PlayerSkin skin;
    private static String requestedSkin="";
    private static final IdentityHashMap<ModelPart,String> labels=new IdentityHashMap<>();
    private static final Map<String,Map<String,Integer>> counts=new LinkedHashMap<>();
    private static final List<Map<String,Object>> models=new ArrayList<>();
    private static Context context;
    private static String accessory;
    private static final Field SLIM=field(PlayerModel.class,"slim");
    private ArmVisibilityMatrixProbe() {}
    private record Context(boolean local,boolean shadow) {}

    public static void register() {NeoForge.EVENT_BUS.addListener(ArmVisibilityMatrixProbe::trigger);}
    private static void trigger(RenderFrameEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(enabled||BowVisibilityDiagnosticSequence.isRunning()||CombatRenderCounters.active||mc.player==null||mc.screen!=null||!NativeWorldReadiness.ready())return;
        Path file=mc.gameDirectory.toPath().resolve("emfattachments-combat-arm-probe.txt");
        if(!Files.isRegularFile(file))return;
        try {
            if(!mc.hasSingleplayerServer()||!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client")))
                throw new IllegalStateException("Disposable integrated client required");
            String label=Files.readString(file).trim().replaceAll("[^a-zA-Z0-9._-]","_");Files.delete(file);
            Path out=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(out);
            Files.writeString(out.resolve("combat-arm-geometry-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(CombatArmGeometryProbe.run()));
        }catch(Exception error){throw new IllegalStateException("Explicit arm geometry probe",error);}
    }
    public static void begin(boolean slim) {
        enabled=true;requestedSkin=slim?"slim":"default";
        skin=null;
        for(long i=0;i<100;i++) {
            PlayerSkin candidate=DefaultPlayerSkin.get(new com.mojang.authlib.GameProfile(new UUID(0,i),"ArmMatrix"));
            if(candidate.model().id().equals(requestedSkin)){skin=candidate;break;}
        }
        if(skin==null)throw new IllegalStateException("No installed default skin for "+requestedSkin);
        resetFrame();
    }
    public static void end() {enabled=false;skin=null;requestedSkin="";resetFrame();}
    public static void resetFrame() {labels.clear();counts.clear();models.clear();context=null;accessory=null;}
    public static PlayerSkin skin(AbstractClientPlayer entity,PlayerSkin original) {
        if(!enabled||entity!=Minecraft.getInstance().player)return original;
        return new PlayerSkin(skin.texture(),skin.textureUrl(),original.capeTexture(),original.elytraTexture(),skin.model(),skin.secure());
    }
    public static Scope entity(LivingEntity entity,EntityModel<?> model) {
        Context previous=context;
        context=new Context(enabled&&entity==Minecraft.getInstance().player,shadow());
        if(context.local&&model instanceof HumanoidModel<?> human) {
            track(human.leftArm,"skin:left");track(human.rightArm,"skin:right");
            if(model instanceof PlayerModel<?> player) {
                track(player.leftSleeve,"sleeve:left");track(player.rightSleeve,"sleeve:right");
                if(!context.shadow)try{models.add(Map.of("class",model.getClass().getName(),"actualSlim",SLIM.getBoolean(player),
                        "requestedSkin",requestedSkin,"reportedSkin",((AbstractClientPlayer)entity).getSkin().model().id()));}
                catch(IllegalAccessException error){throw new IllegalStateException(error);}
            }
        }
        return ()->context=previous;
    }
    public static Scope accessory(String id) {String previous=accessory;accessory=id;return ()->accessory=previous;}
    public static void copied(HumanoidModel<?> source,HumanoidModel<?> target) {
        if(!recording()||!labels.containsKey(source.rightArm))return;
        String category=accessory!=null?"accessory:"+accessory:target instanceof HumanoidArmorModel<?>?"armor":"other_copy";
        track(target.leftArm,category+":left");track(target.rightArm,category+":right");
    }
    private static void track(ModelPart part,String label){part.getAllParts().forEach(p->labels.put(p,label));}
    public static void attempted(ModelPart part) {
        if(!recording())return;String label=labels.get(part);if(label==null)return;
        increment(label,"renderAttempts");
        if(FirstPersonArmGeometry.hides(part))increment(label,"maskedAttempts");
    }
    public static void compiled(ModelPart part) {
        if(!recording())return;String label=labels.get(part);if(label==null)return;
        increment(label,"compileCalls");
        if(FirstPersonArmGeometry.hides(part))increment(label,"compiledWhileMasked");
    }
    private static void increment(String label,String key){counts.computeIfAbsent(label,k->new LinkedHashMap<>()).merge(key,1,Integer::sum);}
    private static boolean recording(){return enabled&&context!=null&&context.local&&!context.shadow;}
    private static boolean shadow(){return net.neoforged.fml.ModList.get().isLoaded("iris")&&net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();}
    public static Map<String,Object> snapshot() {
        var copy=new LinkedHashMap<String,Map<String,Integer>>();counts.forEach((k,v)->copy.put(k,new LinkedHashMap<>(v)));
        return Map.of("enabled",enabled,"requestedSkin",requestedSkin,"rendererModels",List.copyOf(models),"counts",copy,
                "evidenceKind","Actual render-attempt and compile-entry counts; no final emitted-vertex or GPU pixel claim.");
    }
    public static void assertFrame(boolean ownedAttack,boolean requireVisible,boolean requestedArms,String wearable) {
        if(!enabled)return;
        if(models.isEmpty())throw new AssertionError("No actual local PlayerRenderer model witnessed");
        for(var model:models)if(!Boolean.valueOf(requestedSkin.equals("slim")).equals(model.get("actualSlim"))
                ||!requestedSkin.equals(model.get("reportedSkin")))throw new AssertionError("Native skin/renderer width mismatch: "+model);
        if(total("","compiledWhileMasked")!=0)throw new AssertionError("Hidden arm reached actual compile: "+snapshot());
        if(ownedAttack&&!requestedArms) {
            if(total("","compileCalls")!=0||total("skin:","maskedAttempts")==0||total("armor:","maskedAttempts")==0)
                throw new AssertionError("Native arms-off geometry not suppressed: "+snapshot());
            if(!wearable.isEmpty()&&total("accessory:"+wearable+":","maskedAttempts")==0)
                throw new AssertionError("Expected real glove path was not observed: "+snapshot());
        } else {
            if(total("","maskedAttempts")!=0)throw new AssertionError("Mask leaked outside native arms-off attack: "+snapshot());
            if(requireVisible&&(total("skin:","compileCalls")==0||total("armor:","compileCalls")==0))
                throw new AssertionError("Visible skin/armor compile missing: "+snapshot());
            if(requireVisible&&!wearable.isEmpty()&&total("accessory:"+wearable+":","compileCalls")==0)
                throw new AssertionError("Visible glove compile missing: "+snapshot());
        }
    }
    private static int total(String prefix,String key){return counts.entrySet().stream().filter(e->e.getKey().startsWith(prefix)).mapToInt(e->e.getValue().getOrDefault(key,0)).sum();}
    private static Field field(Class<?> owner,String name){try{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @FunctionalInterface public interface Scope extends AutoCloseable {@Override void close();}
}
