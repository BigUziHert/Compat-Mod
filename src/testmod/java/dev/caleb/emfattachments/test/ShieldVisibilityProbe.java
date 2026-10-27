package dev.caleb.emfattachments.test;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.FirstPersonRenderPolicy;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import dev.tr7zw.firstperson.versionless.mixinbase.ModelPartBase;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.client.AccessoriesRendererRegistry;
import io.wispforest.accessories.api.slot.SlotReference;
import java.util.*;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;

/** Actual registered native shield renderers, including transformed model draw boundaries. */
public final class ShieldVisibilityProbe {
    private ShieldVisibilityProbe() {}
    private record Mode(String name, boolean local, boolean body, boolean extract, boolean enabled,
                        CameraType camera, boolean foreign, boolean shadow, boolean expectedScope) {}
    private static final List<Mode> MODES=List.of(
        new Mode("local-body",true,true,false,true,CameraType.FIRST_PERSON,false,false,true),
        new Mode("local-normal",true,false,false,true,CameraType.FIRST_PERSON,false,false,false),
        new Mode("remote-during-body",false,true,false,true,CameraType.FIRST_PERSON,false,false,false),
        new Mode("extraction",true,true,true,true,CameraType.FIRST_PERSON,false,false,false),
        new Mode("third-person",true,true,false,true,CameraType.THIRD_PERSON_BACK,false,false,false),
        new Mode("foreign-camera",true,true,false,true,CameraType.FIRST_PERSON,true,false,false),
        new Mode("disabled",true,true,false,false,CameraType.FIRST_PERSON,false,false,false),
        new Mode("shadow",true,true,false,true,CameraType.FIRST_PERSON,false,true,false));

    public static Map<String,Object> run() {
        var report=new LinkedHashMap<String,Object>(); var rows=new ArrayList<Map<String,Object>>(); var failures=new ArrayList<String>();
        report.put("scope","Direct actual registered Aether/Deep Aether shield renderer calls, native geometry counts, strict first-person scope controls and exception restoration. Pixel transparency/occlusion is verified separately by actual camera images.");
        report.put("rows",rows); report.put("failures",failures);
        var mc=Minecraft.getInstance(); if(mc.player==null||mc.level==null){report.put("status","FAIL");report.put("error","Requires active client world");return report;}
        var core=FirstPersonModelCore.instance; boolean raw=core.isRenderingPlayerRaw(),extract=FirstPersonModelCore.cameraEntityExtract,enabled=FirstPersonAPI.isEnabled();
        var camera=mc.options.getCameraType();var cameraEntity=mc.getCameraEntity();var current=AttachmentTestMod.current;
        boolean iris=net.neoforged.fml.ModList.get().isLoaded("iris"),shadow=iris&&net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE;
        var inventory=mc.player.getInventory().save(new ListTag());
        var remote=new RemotePlayer(mc.level,new GameProfile(UUID.randomUUID(),"ShieldScopeControl"));remote.setPos(mc.player.position());
        try {
            require(mc.isSameThread(),"Client thread required");AttachmentTestMod.current=null;
            for(String id:List.of("aether:shield_of_repulsion","deep_aether:wind_shield")) {
                var stack=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();require(!stack.isEmpty(),"Missing shield "+id);
                var renderer=AccessoriesRendererRegistry.getRenderer(stack);require(renderer!=null,"Missing registered renderer "+id);
                var field=renderer.getClass().getDeclaredField("shieldModel");field.setAccessible(true);
                var nativeModel=(HumanoidModel<?>)field.get(renderer);var original=snapshot(nativeModel);
                var parent=new PlayerModel<AbstractClientPlayer>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER),false);
                String slot=AccessoriesAPI.getStackSlotTypes(mc.player,stack).stream().map(s->s.name()).filter(s->!io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(s)).sorted().findFirst().orElseThrow();
                try {
                    for(Mode mode:MODES) {
                        if(mode.shadow&&!iris)continue;
                        for(String hidden:List.of("head-marker","head-visible","right-arm-marker","all-marker")) {
                            var row=new LinkedHashMap<String,Object>();rows.add(row);row.put("item",id);row.put("mode",mode.name);row.put("sourceHidden",hidden);
                            try {
                                var entity=mode.local?mc.player:remote;
                                setContext(mode,remote,iris);
                                require(FirstPersonRenderPolicy.isLocalBody(entity)==mode.expectedScope,"Unexpected body scope");
                                clear(parent); restore(original);
                                // Native same-entity baseline and isolated part counts establish expected geometry.
                                var baseline=new Counter(false);
                                try(var emf=new EmfProbeContext(entity)){renderer.render(stack,SlotReference.of(entity,slot,0),new PoseStack(),parent,baseline.buffers(),0xF000F0,0,0,0,entity.tickCount,0,0);}
                                require(baseline.vertices>0&&baseline.invalid==0,"No finite native baseline");
                                int removed=0;for(int index:selected(hidden)){var count=new Counter(false);try(var emf=new EmfProbeContext(entity)){parts(nativeModel)[index].render(new PoseStack(),count,0xF000F0,0);}removed+=count.vertices;}
                                var before=snapshot(nativeModel);
                                hide(parent,hidden);var parentBefore=snapshot(parent);var actual=new Counter(false);
                                try(var emf=new EmfProbeContext(entity)){renderer.render(stack,SlotReference.of(entity,slot,0),new PoseStack(),parent,actual.buffers(),0xF000F0,0,0,0,entity.tickCount,0,0);}
                                int expected=baseline.vertices-(mode.expectedScope?removed:0);
                                row.put("baselineVertices",baseline.vertices);row.put("selectedPartVertices",removed);row.put("expectedVertices",expected);row.put("actualVertices",actual.vertices);
                                require(actual.invalid==0&&actual.vertices==expected,"Native shield visibility differs: expected "+expected+", actual "+actual.vertices);
                                require(flagsSame(before),"Native shield visibility/marker/depth leaked");require(allSame(parentBefore),"Source model mutated");
                                if(mode.expectedScope&&hidden.equals("head-marker")) {
                                    boolean threw=false;try(var emf=new EmfProbeContext(entity)){renderer.render(stack,SlotReference.of(entity,slot,0),new PoseStack(),parent,new Counter(true).buffers(),0xF000F0,0,0,0,entity.tickCount,0,0);}catch(ExpectedFailure expectedFailure){threw=true;}
                                    require(threw,"Exception control did not reach visible native geometry");require(flagsSame(before),"Exception leaked shield hidden state");row.put("exceptionRestored",true);
                                }
                                row.put("stateRestored",true);row.put("status","PASS");
                            } catch(Throwable error){row.put("status","FAIL");row.put("error",error.toString());failures.add(id+"/"+mode.name+"/"+hidden+": "+error);}
                            finally {clear(parent);restore(original);}
                        }
                    }
                } finally {restore(original);}
            }
        } catch(Throwable error){failures.add(error.toString());}
        finally {
            core.setRenderingPlayer(false);FirstPersonModelCore.cameraEntityExtract=false;
            if(iris)net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE=shadow;
            mc.options.setCameraType(camera);mc.setCameraEntity(cameraEntity);
            mc.gameRenderer.getMainCamera().setup(mc.level,cameraEntity,!camera.isFirstPerson(),camera.isMirrored(),0F);
            FirstPersonAPI.setEnabled(enabled);core.setRenderingPlayer(raw);FirstPersonModelCore.cameraEntityExtract=extract;AttachmentTestMod.current=current;
            boolean unchanged=inventory.equals(mc.player.getInventory().save(new ListTag()));report.put("inventoryUnchanged",unchanged);if(!unchanged)failures.add("Player inventory changed");
            report.put("fixtureStateRestored",true);
        }
        report.put("caseCount",rows.size());report.put("status",failures.isEmpty()?"PASS":"FAIL");return report;
    }
    private static void setContext(Mode mode,RemotePlayer remote,boolean iris){var mc=Minecraft.getInstance();FirstPersonAPI.setEnabled(mode.enabled);FirstPersonModelCore.instance.setRenderingPlayer(mode.body);FirstPersonModelCore.cameraEntityExtract=mode.extract;mc.options.setCameraType(mode.camera);mc.setCameraEntity(mode.foreign?remote:mc.player);mc.gameRenderer.getMainCamera().setup(mc.level,mc.getCameraEntity(),!mode.camera.isFirstPerson(),mode.camera.isMirrored(),0F);if(iris)net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE=mode.shadow;}
    private static int[] selected(String kind){return kind.equals("all-marker")?new int[]{0,1,2,3,4,5,6}:kind.equals("right-arm-marker")?new int[]{4}:new int[]{0,1};}
    private static void hide(HumanoidModel<?> model,String kind){for(int index:selected(kind)){var p=parts(model)[index];if(kind.equals("head-visible"))p.visible=false;else {float originalZ=p.z;((ModelPartBase)(Object)p).setHidden();p.visible=true;p.z=originalZ;/* Simulate EMF rewriting visible and position; only the independent hidden marker remains. */}}}
    private static void clear(HumanoidModel<?> model){for(var p:parts(model)){var marker=(ModelPartBase)(Object)p;if(marker.isHidden())marker.showAgain();p.visible=true;}}
    private record State(float[] pose,boolean visible,boolean skip,boolean hidden){}
    private static Map<ModelPart,State> snapshot(HumanoidModel<?> model){var result=new IdentityHashMap<ModelPart,State>();for(var root:parts(model))root.getAllParts().forEach(p->result.put(p,new State(ModelState.state(p),p.visible,p.skipDraw,((ModelPartBase)(Object)p).isHidden())));return result;}
    private static void restore(Map<ModelPart,State> states){states.forEach((p,s)->{var marker=(ModelPartBase)(Object)p;if(marker.isHidden()!=s.hidden){if(s.hidden)marker.setHidden();else marker.showAgain();}float[]v=s.pose;p.x=v[0];p.y=v[1];p.z=v[2];p.xRot=v[3];p.yRot=v[4];p.zRot=v[5];p.xScale=v[6];p.yScale=v[7];p.zScale=v[8];p.visible=s.visible;p.skipDraw=s.skip;});}
    private static boolean flagsSame(Map<ModelPart,State> states){return states.entrySet().stream().allMatch(e->{var p=e.getKey();var s=e.getValue();return p.visible==s.visible&&p.skipDraw==s.skip&&((ModelPartBase)(Object)p).isHidden()==s.hidden&&p.z==s.pose[2];});}
    private static boolean allSame(Map<ModelPart,State> states){return flagsSame(states)&&states.entrySet().stream().allMatch(e->ModelState.same(e.getValue().pose,ModelState.state(e.getKey())));}
    private static ModelPart[] parts(HumanoidModel<?> m){return new ModelPart[]{m.head,m.hat,m.body,m.leftArm,m.rightArm,m.leftLeg,m.rightLeg};}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class ExpectedFailure extends RuntimeException{}
    private static final class Counter implements VertexConsumer {
        int vertices,invalid;final boolean fail;Counter(boolean fail){this.fail=fail;}
        MultiBufferSource buffers(){return new MultiBufferSource(){final Map<net.minecraft.client.renderer.RenderType,VertexConsumer> consumers=new IdentityHashMap<>();public VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type){return consumers.computeIfAbsent(type,t->new Delegate(Counter.this));}};}
        public VertexConsumer addVertex(float x,float y,float z){if(fail)throw new ExpectedFailure();vertices++;if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))invalid++;return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}public VertexConsumer setUv(float u,float v){return this;}public VertexConsumer setUv1(int u,int v){return this;}public VertexConsumer setUv2(int u,int v){return this;}public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
    private static final class Delegate implements VertexConsumer {
        final Counter parent;Delegate(Counter parent){this.parent=parent;}public VertexConsumer addVertex(float x,float y,float z){parent.addVertex(x,y,z);return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}public VertexConsumer setUv(float u,float v){return this;}public VertexConsumer setUv1(int u,int v){return this;}public VertexConsumer setUv2(int u,int v){return this;}public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
}

