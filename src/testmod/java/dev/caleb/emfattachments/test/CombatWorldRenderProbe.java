package dev.caleb.emfattachments.test;

import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Read-only diagnostic for world-player omission after a native dash/ragdoll. */
public final class CombatWorldRenderProbe {
    private static volatile boolean enabled;
    private static volatile Map<String,Object> server=Map.of();
    private static boolean registered;
    private static int entries,returns,pre,canceled,post,hands,canceledHands,frames,missing;
    private static final Deque<Map<String,Object>> recent=new ArrayDeque<>();
    private static final List<Map<String,Object>> firstMissing=new ArrayList<>();
    private CombatWorldRenderProbe() {}
    public static void register() {
        if(registered)return;registered=true;
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,true,RenderPlayerEvent.Pre.class,e->{
            if(relevant(e.getEntity())){pre++;if(e.isCanceled())canceled++;}
        });
        NeoForge.EVENT_BUS.addListener((RenderPlayerEvent.Post e)->{if(relevant(e.getEntity()))post++;});
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,true,RenderHandEvent.class,e->{
            if(relevant(Minecraft.getInstance().player)){hands++;if(e.isCanceled())canceledHands++;}
        });
    }
    public static void beginCase(String id) {
        enabled=id.equals("simplyswords:storms_edge")||id.equals("simplyswords:stormscale");
        frames=missing=0;server=Map.of();recent.clear();firstMissing.clear();resetFrame();
    }
    public static void resetFrame(){entries=returns=pre=canceled=post=hands=canceledHands=0;}
    private static boolean relevant(Entity e) {
        return enabled&&e!=null&&e==Minecraft.getInstance().player
            &&(!net.neoforged.fml.ModList.get().isLoaded("iris")||!net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass());
    }
    public static void levelEntry(Entity e){if(relevant(e))entries++;}
    public static void levelReturn(Entity e){if(relevant(e))returns++;}
    public static void serverState(ServerPlayer p) {
        if(!enabled)return;
        var m=new LinkedHashMap<String,Object>();
        m.put("gameTime",p.serverLevel().getGameTime());m.put("position",p.position().toString());
        m.put("velocity",p.getDeltaMovement().toString());m.put("vehicle",type(p.getVehicle()));
        m.put("invisible",p.isInvisible());m.put("nativeSession",ExpandedRagdollRecovery.active(p));
        m.put("nativePosePending",ExpandedRagdollRecovery.pending(p));
        try {
            Field f=Class.forName("dev.leo.ragdollreactions.physics.ReactionSuppressions").getDeclaredField("SUPPRESS_UNTIL_TICK");f.setAccessible(true);
            Object until=((Map<?,?>)f.get(null)).get(p.getUUID());m.put("nativeReactionSuppressionUntil",until==null?-1L:until);
        }catch(ReflectiveOperationException ex){m.put("nativeReactionStateUnavailable",ex.toString());}
        server=Collections.unmodifiableMap(m);
    }
    public static void frame(int tick,boolean attackActive) {
        if(!enabled)return;frames++;
        var m=snapshot();m.put("caseTick",tick);m.put("attackActive",attackActive);
        if(recent.size()==16)recent.removeFirst();recent.addLast(m);
        int items=CombatRenderCounters.bodyItems+CombatRenderCounters.vanillaItems+CombatRenderCounters.otherItems;
        if(attackActive&&items==0){missing++;if(firstMissing.size()<16)firstMissing.add(m);}
    }
    public static Map<String,Object> history() {
        if(!enabled)return Map.of();
        return Map.of("frames",frames,"activeMissingItemFrames",missing,"firstMissingFrames",new ArrayList<>(firstMissing),"recentFrames",new ArrayList<>(recent),
                "scope","Read-only actual main render pass. Final event state is sampled at LOWEST priority; other same-priority listeners could run later.");
    }
    public static LinkedHashMap<String,Object> snapshot() {
        var m=new LinkedHashMap<String,Object>();if(!enabled)return m;
        var mc=Minecraft.getInstance();var p=mc.player;var camera=mc.gameRenderer.getMainCamera();
        m.put("levelPlayerEntries",entries);m.put("levelPlayerReturns",returns);
        m.put("renderPlayerPre",pre);m.put("renderPlayerPreCanceled",canceled);m.put("renderPlayerPost",post);
        m.put("renderHandEvents",hands);m.put("renderHandCanceled",canceledHands);m.put("itemDraws",CombatRenderCounters.snapshot());
        m.put("serverState",server);if(p==null)return m;
        m.put("clientTick",p.tickCount);m.put("cameraType",mc.options.getCameraType().name());
        m.put("mainCameraEntityIsLocal",camera.getEntity()==p);m.put("minecraftCameraEntityIsLocal",mc.getCameraEntity()==p);
        m.put("cameraDetached",camera.isDetached());m.put("cameraPosition",camera.getPosition().toString());
        m.put("position",p.position().toString());m.put("previousRenderPosition",List.of(p.xOld,p.yOld,p.zOld));
        m.put("boundingBox",p.getBoundingBox().toString());m.put("cullingBox",p.getBoundingBoxForCulling().toString());
        m.put("noCulling",p.noCulling);m.put("cameraInsideBox",p.getBoundingBox().contains(camera.getPosition()));
        m.put("invisible",p.isInvisible());m.put("removed",p.isRemoved());m.put("pose",p.getPose().name());m.put("vehicle",type(p.getVehicle()));
        m.put("levelEntityIdentity",mc.level!=null&&mc.level.getEntity(p.getId())==p);
        boolean listed=false;if(mc.level!=null)for(Entity e:mc.level.entitiesForRendering())if(e==p){listed=true;break;}m.put("inLevelRenderEntityIterable",listed);
        if(p instanceof dev.kosmx.playerAnim.impl.IAnimatedPlayer animated){
            var animation=animated.playerAnimator_getAnimation();m.put("playerAnimatorMode",animation.getFirstPersonMode().name());
            var conf=animation.getFirstPersonConfiguration();m.put("showRightItem",conf.isShowRightItem());m.put("showLeftItem",conf.isShowLeftItem());
        }
        m.put("playerAnimatorFirstPersonPass",dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode.isFirstPersonPass());
        try {
            Class<?> c=Class.forName("dev.leo.sableplayerragdoll.neoforge.client.RagdollCameraHelper");var state=new LinkedHashMap<String,Object>();
            for(String name:List.of("suppressLocalPlayerRender","ridingRagdollSeat","pendingCameraTicks","cameraSwitched","firstPersonAligned")){
                Field f=c.getDeclaredField(name);f.setAccessible(true);state.put(name,f.get(null));
            }m.put("nativeCameraState",state);
        }catch(ReflectiveOperationException ex){m.put("nativeCameraStateUnavailable",ex.toString());}
        try {
            Object helper=Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);var state=new LinkedHashMap<String,Object>();
            for(String name:List.of("getVehicleSubLevel","getTrackingSubLevel","getTrackingOrVehicleSubLevel")){
                try{Object level=helper.getClass().getMethod(name,Entity.class).invoke(helper,p);state.put(name,level==null?"":level.toString());}
                catch(ReflectiveOperationException ex){state.put(name,ex.toString());}
            }m.put("nativeSableAssociation",state);
        }catch(ReflectiveOperationException ex){m.put("nativeSableAssociationUnavailable",ex.toString());}
        return m;
    }
    private static String type(Entity e){return e==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();}
}
