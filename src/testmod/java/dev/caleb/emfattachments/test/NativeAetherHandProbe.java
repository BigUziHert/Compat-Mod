package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.wispforest.accessories.api.slot.SlotReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import traben.entity_model_features.models.animation.state.EMFState;

/** Native skin/glove matrices and geometry; never reads or calls a compatibility helper. */
public final class NativeAetherHandProbe {
    public static boolean active;
    private static Hand hand;
    private static Hand body;
    private static Source source;
    private static final List<Map<String,Object>> rows = new ArrayList<>();
    private static final List<Map<String,Object>> skinRows = new ArrayList<>();
    private NativeAetherHandProbe() {}
    public static void beginFrame() { rows.clear();skinRows.clear(); }
    public static List<Map<String,Object>> snapshot() { return List.copyOf(rows); }
    public static List<Map<String,Object>> skinSnapshot() { return List.copyOf(skinRows); }
    public static Map<String,Object> nativeVisibility() {
        var player=Minecraft.getInstance().player;
        if(player==null)return Map.of();
        var config=((dev.kosmx.playerAnim.impl.IAnimatedPlayer)player).playerAnimator_getAnimation().getFirstPersonConfiguration();
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("firstPersonPass",dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode.isFirstPersonPass());
        row.put("showLeftArm",config.isShowLeftArm());row.put("showRightArm",config.isShowRightArm());
        row.put("showLeftItem",config.isShowLeftItem());row.put("showRightItem",config.isShowRightItem());
        row.put("betterCombatShowArms",net.bettercombat.client.BetterCombatClientMod.config.isShowingArmsInFirstPerson);
        row.put("betterCombatShowOtherHand",net.bettercombat.client.BetterCombatClientMod.config.isShowingOtherHandFirstPerson);
        row.put("dominantArm",player.getMainArm().name());
        return row;
    }
    public interface Scope extends AutoCloseable { @Override void close(); }

    public static Scope hand(AbstractClientPlayer player, ModelPart arm, ModelPart sleeve, PoseStack poses) {
        Hand previous = hand;
        if (!active || player != Minecraft.getInstance().player) return () -> {};
        Hand next = new Hand(arm, sleeve); hand = next;
        next.nativeVisibility=nativeVisibility();
        next.before = poses.last().pose().get(new float[16]);
        return () -> { saveSkin(next,"native-hand");hand = previous; };
    }

    public static Scope body(AbstractClientPlayer player, HumanoidModel<?> model, PoseStack poses) {
        Hand previous = body;
        if (!active || player != Minecraft.getInstance().player) return () -> {};
        Hand next = new Hand(model.rightArm, model.leftArm); body = next;
        next.nativeVisibility=nativeVisibility();
        next.before = poses.last().pose().get(new float[16]);
        return () -> { saveSkin(next,"native-body");body = previous; };
    }

    private static void saveSkin(Hand skin,String path) {
        if(skinRows.size()>=16)return;
        Map<String,Object> row=new LinkedHashMap<>();row.put("renderPath",path);
        row.put("irisShadow",net.neoforged.fml.ModList.get().isLoaded("iris") && net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass());
        row.put("entryMatrix",skin.before);row.put("skinDraws",List.copyOf(skin.skin));row.put("vertices",skin.vertices);row.put("vertexBounds",skin.bounds.clone());
        row.put("nativeVisibility",skin.nativeVisibility);
        skinRows.add(row);
    }

    public static Scope bodySource(ItemStack stack, SlotReference reference, EntityModel<?> model, PoseStack poses) {
        return source(reference.entity().getMainArm(), stack, reference, model, poses, true);
    }

    public static Scope source(HumanoidArm side, ItemStack stack, SlotReference reference, EntityModel<?> model, PoseStack poses) {
        return source(side,stack,reference,model,poses,false);
    }
    private static Scope source(HumanoidArm side, ItemStack stack, SlotReference reference, EntityModel<?> model, PoseStack poses, boolean bodyPath) {
        Source previous = source;
        if (!active || reference.entity() != Minecraft.getInstance().player || !(model instanceof HumanoidModel<?> humanoid)) return () -> {};
        ModelPart arm = side == HumanoidArm.LEFT ? humanoid.leftArm : humanoid.rightArm;
        Source next = new Source(arm); source = next;
        var state = EMFState.state();
        Hand skin = bodyPath ? body : hand;
        next.row.put("renderPath", bodyPath ? "body-accessory" : "native-hand-accessory");
        next.row.put("irisShadow", net.neoforged.fml.ModList.get().isLoaded("iris") && net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass());
        next.row.put("skinModel", ((AbstractClientPlayer)reference.entity()).getSkin().model().name());
        next.row.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        next.row.put("side", side.name()); next.row.put("slot", reference.slotName());
        next.row.put("entityScale", reference.entity().getScale());
        next.row.put("sourcePose", next.before); next.row.put("sourcePartClass", arm.getClass().getName());
        next.row.put("sourcePartIdentity",System.identityHashCode(arm));
        next.row.put("sourceEntryMatrix", poses.last().pose().get(new float[16]));
        next.row.put("nativeFirstPersonHandState", state != null && state.isFirstPersonHand());
        next.row.put("nativeVisibility",nativeVisibility());
        next.row.put("leftSourceVisible",humanoid.leftArm.visible);next.row.put("rightSourceVisible",humanoid.rightArm.visible);
        next.row.put("sourceVariableMapIdentity", state == null ? 0 : System.identityHashCode(state.variableMap()));
        next.row.put("nativeHandScopeObserved", hand != null);
        next.row.put("skinDraws", skin == null ? List.of() : List.copyOf(skin.skin));
        next.row.put("skinVertices",skin==null?0:skin.vertices);
        next.row.put("skinVertexBounds",skin==null?new float[0]:skin.bounds.clone());
        if (skin != null) next.row.put("handEntryMatrix", skin.before);
        if(bodyPath) {
            next.row.put("leftSourcePose",ModelState.state(humanoid.leftArm)); next.row.put("rightSourcePose",ModelState.state(humanoid.rightArm));
            next.row.put("leftSourceIdentity",System.identityHashCode(humanoid.leftArm));next.row.put("rightSourceIdentity",System.identityHashCode(humanoid.rightArm));
            next.row.put("leftSourceClass",humanoid.leftArm.getClass().getName());next.row.put("rightSourceClass",humanoid.rightArm.getClass().getName());
        }
        next.row.put("gloveDraws", next.draws);
        return () -> {
            next.row.put("sourcePoseAfter", ModelState.state(arm));
            next.row.put("sourcePoseUnchanged", ModelState.same(next.before, ModelState.state(arm)));
            next.row.put("vertices", next.vertices); next.row.put("invalidVertices", next.invalid);
            next.row.put("vertexBounds", List.of(next.minX,next.minY,next.minZ,next.maxX,next.maxY,next.maxZ));
            if (rows.size() < 16) rows.add(next.row);
            source = previous;
        };
    }

    public static void compiled(ModelPart part, PoseStack.Pose pose) {
        if (!active || hand == null && body == null && source == null) return;
        var row = new LinkedHashMap<String,Object>();
        row.put("partClass", part.getClass().getName()); row.put("partIdentity", System.identityHashCode(part));
        row.put("pose", ModelState.state(part)); row.put("matrix", pose.pose().get(new float[16]));
        row.put("cubeCount", ModelState.cubeCount(part)); row.put("visible", part.visible); row.put("skipDraw", part.skipDraw);
        if (source != null) { if (source.draws.size() < 32) source.draws.add(row); }
        else {
            Hand skin = hand != null ? hand : body;
            if(skin.skin.size() < 256) {
                row.put("role", part == skin.arm ? (hand != null ? "arm-root" : "right-arm-root") : part == skin.sleeve ? (hand != null ? "sleeve-root" : "left-arm-root") : "child");
                skin.skin.add(row);
            }
        }
    }

    public static MultiBufferSource buffers(MultiBufferSource original) {
        if (!active || source == null) return original;
        Source captured = source; return type -> new Counter(original.getBuffer(type), captured);
    }
    public static MultiBufferSource skinBuffers(MultiBufferSource original) {
        Hand captured=hand!=null?hand:body;
        if(!active || captured==null)return original;
        return type->new SkinCounter(original.getBuffer(type),captured);
    }
    private static final class Hand {
        final ModelPart arm,sleeve; float[] before; final List<Map<String,Object>> skin = new ArrayList<>();
        Map<String,Object> nativeVisibility=Map.of();
        int vertices; final float[] bounds={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
        Hand(ModelPart arm,ModelPart sleeve) { this.arm=arm;this.sleeve=sleeve; }
    }
    private record SkinCounter(VertexConsumer delegate,Hand hand) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x,float y,float z){if(source==null){hand.vertices++;hand.bounds[0]=Math.min(hand.bounds[0],x);hand.bounds[1]=Math.min(hand.bounds[1],y);hand.bounds[2]=Math.min(hand.bounds[2],z);hand.bounds[3]=Math.max(hand.bounds[3],x);hand.bounds[4]=Math.max(hand.bounds[4],y);hand.bounds[5]=Math.max(hand.bounds[5],z);}delegate.addVertex(x,y,z);return this;}
        @Override public VertexConsumer setColor(int r,int g,int b,int a){delegate.setColor(r,g,b,a);return this;}
        @Override public VertexConsumer setUv(float u,float v){delegate.setUv(u,v);return this;}
        @Override public VertexConsumer setUv1(int u,int v){delegate.setUv1(u,v);return this;}
        @Override public VertexConsumer setUv2(int u,int v){delegate.setUv2(u,v);return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){delegate.setNormal(x,y,z);return this;}
    }
    private static final class Source {
        final ModelPart arm; final float[] before; final Map<String,Object> row = new LinkedHashMap<>();
        final List<Map<String,Object>> draws = new ArrayList<>(); int vertices,invalid;
        float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,minZ=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
        Source(ModelPart arm) { this.arm=arm;before=ModelState.state(arm); }
    }
    private record Counter(VertexConsumer delegate,Source source) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x,float y,float z) { source.vertices++; if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))source.invalid++;source.minX=Math.min(source.minX,x);source.minY=Math.min(source.minY,y);source.minZ=Math.min(source.minZ,z);source.maxX=Math.max(source.maxX,x);source.maxY=Math.max(source.maxY,y);source.maxZ=Math.max(source.maxZ,z);delegate.addVertex(x,y,z);return this; }
        @Override public VertexConsumer setColor(int r,int g,int b,int a){delegate.setColor(r,g,b,a);return this;}
        @Override public VertexConsumer setUv(float u,float v){delegate.setUv(u,v);return this;}
        @Override public VertexConsumer setUv1(int u,int v){delegate.setUv1(u,v);return this;}
        @Override public VertexConsumer setUv2(int u,int v){delegate.setUv2(u,v);return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){delegate.setNormal(x,y,z);return this;}
    }
}
