package dev.caleb.emfattachments.test;

import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.client.RevivalClient;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.leo.sableplayerragdoll.neoforge.config.RagdollClientConfig;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Native RagRevival down/visibility-toggle/revive lifecycle in a disposable integrated world. */
public final class RevivalFaceSequence {
    private static Session active;
    private static Recovery recovery;
    private static RespawnPreflight respawn;
    private RevivalFaceSequence() {}
    public static void register() {
        NeoForge.EVENT_BUS.addListener(RevivalFaceSequence::tick);
        NeoForge.EVENT_BUS.addListener(RevivalFaceSequence::frame);
    }
    private static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        try {
            if(respawn!=null){respawn.tick();return;}
            if(recovery!=null){recovery.tick();return;}
            if(active!=null){if(active.tick())active=null;return;}
            if(mc.player==null||mc.level==null||CombatRenderCounters.active)return;
            var trigger=mc.gameDirectory.toPath().resolve("emfattachments-revival-face.txt");if(!Files.exists(trigger))return;
            if(!mc.hasSingleplayerServer()||!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client")))throw new IllegalStateException("Disposable integrated .local/client world required");
            var lines=Files.readAllLines(trigger);String label=lines.getFirst().trim().replaceAll("[^a-zA-Z0-9._-]","_");boolean baseline=lines.stream().anyMatch(s->s.trim().equals("baseline"));boolean recoverFirst=lines.stream().anyMatch(s->s.trim().equals("recover-first"));
            if(mc.screen!=null&&!(recoverFirst&&mc.screen instanceof DeathScreen))return;
            if(recoverFirst&&!disposableIdentity(mc))throw new IllegalStateException("recover-first requires the offline AttachmentTest identity in an unpublished disposable integrated world");
            // Recovery must be able to release a persisted native sublevel seat
            // before ordinary compiled-player-section readiness can be reached.
            if(!recoverFirst&&!NativeWorldReadiness.ready())return;
            Files.delete(trigger);
            if(recoverFirst&&mc.screen instanceof DeathScreen death){respawn=new RespawnPreflight(mc,label.isBlank()?"revival-face":label,baseline,death);return;}
            if(recoverFirst){recovery=new Recovery(mc,label.isBlank()?"revival-face":label,baseline,Map.of("requested",false,"reason","No DeathScreen; native revival recovery only"));return;}
            try {active=new Session(mc,label.isBlank()?"revival-face":label,baseline,Map.of());}
            catch(Throwable t){var out=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(out);Files.writeString(out.resolve("revival-face-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("status","FAIL","startupFailure",t.toString())));throw t;}
        }catch(Throwable t){if(respawn!=null){try{respawn.fail(t);}catch(Exception writeFailure){LogUtils.getLogger().error("Native respawn preflight report failed",writeFailure);}respawn=null;}else if(recovery!=null){try{recovery.fail(t);}catch(Exception writeFailure){LogUtils.getLogger().error("Revival recovery report failed",writeFailure);}recovery=null;}else if(active==null)LogUtils.getLogger().error("Revival face diagnostic startup failed",t);else active.finish("FAIL",t.toString());}
    }
    private static void frame(RenderFrameEvent.Post event){if(active!=null)try{active.frame();}catch(Throwable t){active.finish("FAIL",t.toString());}}
    private static boolean disposableIdentity(Minecraft mc){
        UUID expected=UUID.nameUUIDFromBytes("OfflinePlayer:AttachmentTest".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return mc.hasSingleplayerServer()&&!mc.getSingleplayerServer().isPublished()
                &&mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client"))
                &&mc.player!=null&&"AttachmentTest".equals(mc.player.getGameProfile().getName())&&expected.equals(mc.player.getUUID())
                &&"AttachmentTest".equals(mc.getUser().getName())&&expected.equals(mc.getUser().getProfileId())
                &&mc.getUser().getType()==net.minecraft.client.User.Type.LEGACY;
    }
    private record Saved(List<ItemStack> normal,List<ItemStack> cosmetic,List<Boolean> flags){}
    private record Step(String name,boolean down,boolean visible,int equipment,boolean newSession){}
    private static final List<Step> STEPS=List.of(
            new Step("normal_visible_down",true,true,0,true),new Step("normal_hide_while_downed",true,false,0,false),
            new Step("normal_show_while_downed",true,true,0,false),new Step("normal_revived",false,true,0,false),
            new Step("hidden_before_down",true,false,0,true),new Step("hidden_revived",false,false,0,false),
            new Step("cosmetic_whoopee_down",true,true,1,true),new Step("cosmetic_hide_while_downed",true,false,1,false),
            new Step("cosmetic_revived",false,false,1,false),new Step("distinct_cosmetic_down",true,true,2,true),
            new Step("distinct_cosmetic_revived",false,true,2,false));
    /** The native respawn packet is permitted only for an explicit failed-test recovery request. */
    private static final class RespawnPreflight {
        final Minecraft mc;final String label;final boolean baseline;final Path out;final LocalPlayer oldPlayer;final UUID playerId;
        final Map<String,Object> evidence=new LinkedHashMap<>();CompletableFuture<Map<String,Object>> observation;int ticks,aliveTicks;
        RespawnPreflight(Minecraft mc,String label,boolean baseline,DeathScreen screen)throws Exception{
            this.mc=mc;this.label=label;this.baseline=baseline;oldPlayer=mc.player;playerId=oldPlayer.getUUID();out=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(out);
            if(!disposableIdentity(mc)||mc.screen!=screen||oldPlayer.isAlive())throw new IllegalStateException("Native respawn requires exact disposable offline identity and current dead-player DeathScreen");
            evidence.put("requested",true);evidence.put("playerName",oldPlayer.getGameProfile().getName());evidence.put("profileId",playerId.toString());evidence.put("runtime",mc.gameDirectory.toPath().toAbsolutePath().normalize().toString());evidence.put("originalClientEntityId",oldPlayer.getId());evidence.put("originalClientAlive",oldPlayer.isAlive());evidence.put("originalClientHealth",oldPlayer.getHealth());
            evidence.put("action","LocalPlayer.respawn: native PERFORM_RESPAWN packet and resetToggleKeys; only the exact current DeathScreen is closed");
            oldPlayer.respawn();
            if(mc.screen==screen)mc.setScreen(null);
            write("RESPAWNING",false,"");
        }
        void tick()throws Exception{
            if(++ticks>300)throw new IllegalStateException("Native respawn did not produce synchronized living client/server player");
            if(mc.player==null||mc.level==null)return;
            if(!disposableIdentity(mc))throw new IllegalStateException("Disposable player identity changed during native respawn");
            // Never dismiss another GUI. Native respawn may temporarily show its loading screen.
            if(mc.screen!=null)return;
            if(mc.player==oldPlayer||!mc.player.isAlive()||mc.player.getHealth()<=0){aliveTicks=0;return;}
            if(++aliveTicks<5)return;
            if(observation==null){observation=new CompletableFuture<>();var target=observation;mc.getSingleplayerServer().execute(()->{try{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);target.complete(p==null?Map.of("serverPlayerPresent",false):Map.of("serverPlayerPresent",true,"serverAlive",p.isAlive(),"serverHealth",p.getHealth(),"serverEntityId",p.getId(),"serverProfileId",p.getUUID().toString(),"serverDimension",p.level().dimension().location().toString()));}catch(Throwable t){target.completeExceptionally(t);}});return;}
            if(!observation.isDone())return;var server=observation.join();
            if(!Boolean.TRUE.equals(server.get("serverPlayerPresent"))||!Boolean.TRUE.equals(server.get("serverAlive"))||((Number)server.get("serverHealth")).floatValue()<=0){observation=null;return;}
            if(!playerId.toString().equals(server.get("serverProfileId"))||!Integer.valueOf(mc.player.getId()).equals(server.get("serverEntityId"))||!mc.level.dimension().location().toString().equals(server.get("serverDimension")))throw new IllegalStateException("Native respawn client/server identity mismatch: "+server);
            evidence.putAll(server);evidence.put("clientInstanceReplaced",true);evidence.put("clientEntityId",mc.player.getId());evidence.put("clientAlive",mc.player.isAlive());evidence.put("clientHealth",mc.player.getHealth());evidence.put("clientProfileId",mc.player.getUUID().toString());evidence.put("waitedClientTicks",ticks);evidence.put("nativeRespawnSynchronized",true);
            var saved=new LinkedHashMap<>(evidence);recovery=new Recovery(mc,label,baseline,saved);respawn=null;
        }
        void fail(Throwable t)throws Exception{write("FAIL",true,t.toString());}
        void write(String state,boolean finished,String detail)throws Exception{Files.writeString(out.resolve("revival-face-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("status",state,"finished",finished,"detail",detail,"nativeRespawnBeforeRecovery",evidence)));}
    }
    /** Explicit recovery only in the same disposable integrated world required by the trigger gate. */
    private static final class Recovery {
        final Minecraft mc;final String label;final boolean baseline;final Path out;final Map<String,Object> evidence=new LinkedHashMap<>();
        CompletableFuture<Map<String,Object>> pending=new CompletableFuture<>(), finalObservation;int ticks;
        Recovery(Minecraft mc,String label,boolean baseline,Map<String,Object> respawnEvidence)throws Exception{
            this.mc=mc;this.label=label;this.baseline=baseline;out=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(out);
            evidence.put("scope","Explicit recover-first: native DownedManager.revive and Sable session release, followed by actual server/client state synchronization. No downed/visibility flags manually cleared.");
            evidence.put("respawnPreflight",respawnEvidence);
            mc.getSingleplayerServer().execute(()->{try{
                var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());var row=new LinkedHashMap<String,Object>();
                row.put("serverWasDowned",DownedManager.isDowned(p));row.put("serverHadSession",ExpandedRagdollRecovery.active(p));
                DownedManager.revive(p);ExpandedRagdollRecovery.release(p);ExpandedReactionTransition.begin(p);
                row.put("serverDownedAfterNativeRecovery",DownedManager.isDowned(p));row.put("serverSessionAfterNativeRecovery",ExpandedRagdollRecovery.active(p));
                pending.complete(row);
            }catch(Throwable t){pending.completeExceptionally(t);}});
            write("RECOVERING",false,"");
        }
        void tick()throws Exception{
            if(mc.player==null||mc.level==null)throw new IllegalStateException("World closed during explicit recovery");
            if(!pending.isDone())return;evidence.putAll(pending.join());
            if(++ticks>240)throw new IllegalStateException("Native recovery did not release client downed state/seat");
            if(ticks<35||RevivalClient.isDowned(mc.player)||!ExpandedRagdollRecovery.clientRestored(mc.player)||mc.player.isUsingItem())return;
            if(Boolean.TRUE.equals(evidence.get("serverDownedAfterNativeRecovery"))||Boolean.TRUE.equals(evidence.get("serverSessionAfterNativeRecovery")))throw new IllegalStateException("Native recovery retained server session");
            if(finalObservation==null){finalObservation=new CompletableFuture<>();var future=finalObservation;mc.getSingleplayerServer().execute(()->{try{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());future.complete(Map.of("serverDownedAfterWait",DownedManager.isDowned(p),"serverSessionAfterWait",ExpandedRagdollRecovery.active(p),"serverPendingPoseAfterWait",ExpandedRagdollRecovery.pending(p)));}catch(Throwable t){future.completeExceptionally(t);}});return;}
            if(!finalObservation.isDone())return;var finalServer=finalObservation.join();evidence.putAll(finalServer);
            if(finalServer.values().stream().anyMatch(Boolean.TRUE::equals))throw new IllegalStateException("Native server recovery reactivated after wait: "+finalServer);
            evidence.put("clientDownedCleared",true);evidence.put("clientSeatReleased",true);evidence.put("clientInvisible",mc.player.isInvisible());evidence.put("waitedClientTicks",ticks);
            var savedEvidence=new LinkedHashMap<>(evidence);recovery=null;
            try{active=new Session(mc,label,baseline,savedEvidence);}
            catch(Throwable failure){fail(failure);throw failure;}
        }
        void fail(Throwable t)throws Exception{write("FAIL",true,t.toString());}
        void write(String state,boolean finished,String detail)throws Exception{Files.writeString(out.resolve("revival-face-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("status",state,"finished",finished,"detail",detail,"nativeRecoveryBeforeTest",evidence)));}
    }
    private static final class Session {
        final Minecraft mc;final Path out;final boolean baseline;
        final CameraType camera;final boolean gui,firstPersonRagdoll,invisible;final Vec3 position;final float yaw,pitch;
        final Map<KeyMapping,Boolean> keys=new LinkedHashMap<>();final Map<String,Saved> saved=new LinkedHashMap<>();final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
        final Map<String,Object> report=new LinkedHashMap<>();final List<Map<String,Object>> captures=new ArrayList<>();final List<String> behaviorFailures=new ArrayList<>();
        ServerPlayer player;ListTag inventory;GameType gameType;float health;int food;float saturation;boolean flying,serverInvisible;long dayTime;Vec3 arena;
        CompletableFuture<Void> pending;CompletableFuture<Map<String,Object>> observation;
        int index,ticks,cleanupTicks;boolean prepared,counting,finishing,restoreSubmitted;String finalStatus,detail;
        Session(Minecraft mc,String label,boolean baseline,Map<String,Object> recoveryEvidence)throws Exception{
            this.mc=mc;this.baseline=baseline;out=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(out);
            if(mc.player.isPassenger()||mc.player.isUsingItem()||RevivalClient.isDowned(mc.player))throw new IllegalStateException("Start recovered, unmounted and without active item use");
            camera=mc.options.getCameraType();gui=mc.options.hideGui;firstPersonRagdoll=RagdollClientConfig.USE_FIRST_PERSON_CAMERA.get();invisible=mc.player.isInvisible();position=mc.player.position();yaw=mc.player.getYRot();pitch=mc.player.getXRot();
            for(var key:List.of(mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyJump,mc.options.keyShift,mc.options.keySprint,mc.options.keyUse,mc.options.keyAttack))keys.put(key,key.isDown());
            report.put("baseline",baseline);report.put("firstPersonInstalled",ModList.get().isLoaded("firstperson"));report.put("captures",captures);report.put("behaviorFailures",behaviorFailures);report.put("plannedCaptureCount",STEPS.size());report.put("nativeRecoveryBeforeTest",recoveryEvidence);
            report.put("scope","Actual public RagRevival DownedManager.down -> native persistent downed state, Sable physical limbs/seat, live visibility changes, public revive -> native release. External native camera. Entry is the native down API, not a simulated lethal damage event or feeding test.");
            var probe=RevivalFaceHelperProbe.run(baseline);report.put("nativeHelperProbe",probe);
            if(probe.containsKey("detail")||probe.containsKey("restoreError")){
                Files.writeString(out.resolve("native-helper-structural-failure.json"),new GsonBuilder().setPrettyPrinting().create().toJson(probe));
                throw new IllegalStateException("Structural native helper failure before fixture input/camera/server mutation: "+probe.get("detail")+"; cause="+probe.get("cause")+"; restore="+probe.get("restoreError"));
            }
            if("FAIL".equals(probe.get("status")))behaviorFailures.add("Controlled helper probe failed; see nativeHelperProbe");
            keys.keySet().forEach(k->k.setDown(false));
            mc.options.hideGui=false;RagdollClientConfig.USE_FIRST_PERSON_CAMERA.set(false);mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);CombatRenderCounters.active=true;
            pending=task(()->{
                player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());if(DownedManager.isDowned(player))throw new IllegalStateException("Server already downed");
                inventory=player.getInventory().save(new ListTag());gameType=player.gameMode.getGameModeForPlayer();health=player.getHealth();food=player.getFoodData().getFoodLevel();saturation=player.getFoodData().getSaturationLevel();flying=player.getAbilities().flying;serverInvisible=player.isInvisible();dayTime=player.serverLevel().getDayTime();
                AccessoriesCapability.get(player).getContainers().forEach((name,c)->{var n=new ArrayList<ItemStack>();var cos=new ArrayList<ItemStack>();for(int i=0;i<c.getSize();i++){n.add(c.getAccessories().getItem(i).copy());cos.add(c.getCosmeticAccessories().getItem(i).copy());}saved.put(name,new Saved(n,cos,new ArrayList<>(c.renderOptions())));});
                arena=new Vec3(Math.floor(position.x)+.5,Math.floor(position.y)+.05,Math.floor(position.z)+.5);
                var floor=BlockPos.containing(arena).below();for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)for(int y=0;y<=6;y++){var p=floor.offset(x,y,z);blocks.put(p,player.serverLevel().getBlockState(p));player.serverLevel().setBlockAndUpdate(p,y==0?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.AIR.defaultBlockState());}
                player.serverLevel().setDayTime(6000);player.setGameMode(GameType.SURVIVAL);player.getAbilities().flying=false;player.onUpdateAbilities();player.getInventory().clearContent();player.stopUsingItem();player.setHealth(player.getMaxHealth());
                configure(STEPS.getFirst());
            });write("PREPARING","");
        }
        CompletableFuture<Void> task(Runnable action){var f=new CompletableFuture<Void>();mc.getSingleplayerServer().execute(()->{try{action.run();f.complete(null);}catch(Throwable t){f.completeExceptionally(t);}});return f;}
        void configure(Step step){
            if(step.newSession){if(DownedManager.isDowned(player))DownedManager.revive(player);ExpandedRagdollRecovery.release(player);player.stopRiding();player.teleportTo(player.serverLevel(),arena.x,arena.y,arena.z,0,20);player.setDeltaMovement(Vec3.ZERO);player.fallDistance=0;
                var cap=AccessoriesCapability.get(player);cap.getContainers().values().forEach(c->{c.getAccessories().clearContent();c.getCosmeticAccessories().clearContent();});
                var whoopee=RevivalFaceHelperProbe.item("artifacts:whoopee_cushion");if(AccessoriesAPI.getStackSlotTypes(player,whoopee).stream().noneMatch(s->s.name().equals("face")))throw new IllegalStateException("Whoopee is not accepted in actual Face slot");
                var face=cap.getContainers().get("face");if(face==null||face.getSize()==0)throw new IllegalStateException("No Face slot");
                face.getAccessories().setItem(0,step.equipment==1?ItemStack.EMPTY:whoopee);
                face.getCosmeticAccessories().setItem(0,step.equipment==1?whoopee:step.equipment==2?RevivalFaceHelperProbe.item("artifacts:snorkel"):ItemStack.EMPTY);
            }
            var face=AccessoriesCapability.get(player).getContainers().get("face");
            face.renderOptions().set(0,step.visible);
            // Same state notification used by Accessories' native visibility-toggle packet.
            face.markChanged(false);
            player.inventoryMenu.broadcastChanges();
            if(!step.down)DownedManager.revive(player);
        }
        boolean tick()throws Exception{
            if(finishing)return cleanup();
            if(mc.player==null||mc.level==null)throw new IllegalStateException("World closed during revival test");
            keys.keySet().forEach(k->k.setDown(false));
            if(mc.screen!=null)throw new IllegalStateException("Unexpected screen "+mc.screen.getClass().getName());
            if(pending!=null){if(!pending.isDone())return false;pending.join();pending=null;ticks=0;}
            Step step=STEPS.get(index);ticks++;
            if(step.newSession&&!prepared&&ticks>=35){
                if(!NativeWorldReadiness.ready())return false;
                prepared=true;pending=task(()->DownedManager.down(player,player.damageSources().generic()));return false;
            }
            if(step.newSession&&!prepared)return false;
            if(ticks>300)throw new IllegalStateException("Native state did not synchronize for "+step.name);
            boolean clientReady=step.down?RevivalClient.isDowned(mc.player)&&!ExpandedRagdollRecovery.clientRestored(mc.player):!RevivalClient.isDowned(mc.player)&&ExpandedRagdollRecovery.clientRestored(mc.player)&&mc.player.isInvisible()==invisible;
            var face=AccessoriesCapability.get(mc.player).getContainers().get("face");boolean flagReady=face!=null&&face.shouldRender(0)==step.visible;
            if(!counting&&ticks>=30&&clientReady&&flagReady){
                if(step.down&&mc.options.getCameraType().isFirstPerson())throw new IllegalStateException("Native external ragdoll camera not active");
                if(!step.down)mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                RevivalFaceRenderProbe.reset();RagdollRenderCounters.reset();RevivalFaceRenderProbe.active=true;RagdollRenderCounters.active=true;counting=true;ticks=0;
            }
            return false;
        }
        void frame()throws Exception{
            if(finishing||!counting||ticks<15||pending!=null)return;
            if(observation==null){observation=new CompletableFuture<>();var target=observation;mc.getSingleplayerServer().execute(()->{try{target.complete(serverState());}catch(Throwable t){target.completeExceptionally(t);}});return;}
            if(!observation.isDone())return;var server=observation.join();Step step=STEPS.get(index);
            if(!Boolean.valueOf(step.down).equals(server.get("downed"))||!Boolean.valueOf(step.down).equals(server.get("nativeSession")))throw new IllegalStateException("Actual server revival/session state mismatch "+server);
            if(!Boolean.valueOf(step.visible).equals(server.get("renderFlag")))throw new IllegalStateException("Server render flag mismatch");
            var row=new LinkedHashMap<String,Object>();row.put("stage",step.name);row.put("server",server);row.put("clientDowned",RevivalClient.isDowned(mc.player));row.put("clientFace",clientFace());row.put("actualCamera",mc.options.getCameraType().name());row.put("cameraPosition",mc.gameRenderer.getMainCamera().getPosition().toString());row.put("playerPosition",mc.player.position().toString());row.put("nativeGeometry",RevivalFaceRenderProbe.snapshot());row.put("nativeRagdoll",RagdollRenderCounters.snapshot());
            if(step.down){
                int whoopee=RevivalFaceRenderProbe.count("","whoopee_cushion"),snorkel=RevivalFaceRenderProbe.count("","snorkel");
                String expectedTexture=step.equipment==2?"snorkel":"whoopee_cushion";
                int correct=RevivalFaceRenderProbe.count("accessories/stored/HEAD",expectedTexture),wrongCurios=RevivalFaceRenderProbe.count("curios/",expectedTexture);
                boolean pass=step.visible?correct>0&&wrongCurios==0:(whoopee+snorkel)==0;
                if(step.equipment==2)pass&=whoopee==0;
                row.put("expectedBehaviorPassed",pass);row.put("whoopeeVertices",whoopee);row.put("snorkelVertices",snorkel);
                if(!pass)behaviorFailures.add(step.name+": unexpected duplicate/hidden/wrong cosmetic geometry");
                if(RagdollRenderCounters.partCount("HEAD")==0||RagdollRenderCounters.partCount("TORSO")==0)throw new IllegalStateException("External camera did not observe actual HEAD and TORSO helpers");
            }
            String file=String.format("%02d-%s.png",captures.size()+1,step.name);try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(out.resolve(file));}row.put("file",file);captures.add(row);
            RevivalFaceRenderProbe.active=false;RagdollRenderCounters.active=false;counting=false;observation=null;write("RUNNING","");
            if(++index>=STEPS.size()){finish(baseline?"BASELINE_RECORDED":behaviorFailures.isEmpty()?"CAPTURED":"FAIL",baseline?"Baseline observations are not a passing regression result":"Native lifecycle complete; inspect all images");return;}
            prepared=false;ticks=0;pending=task(()->configure(STEPS.get(index)));
        }
        Map<String,Object> clientFace(){var c=AccessoriesCapability.get(mc.player).getContainers().get("face");return Map.of("normal",c.getAccessories().getItem(0).toString(),"cosmetic",c.getCosmeticAccessories().getItem(0).toString(),"renderFlag",c.shouldRender(0));}
        Map<String,Object> serverState(){var c=AccessoriesCapability.get(player).getContainers().get("face");var m=new LinkedHashMap<String,Object>();m.put("downed",DownedManager.isDowned(player));m.put("remainingMillis",DownedManager.remainingMillis(player));m.put("nativeSession",ExpandedRagdollRecovery.active(player));m.put("seat",player.getVehicle()==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString());m.put("health",player.getHealth());m.put("normal",c.getAccessories().getItem(0).toString());m.put("cosmetic",c.getCosmeticAccessories().getItem(0).toString());m.put("renderFlag",c.shouldRender(0));m.put("revivalGrace",DownedManager.hasRevivalGrace(player));return m;}
        void finish(String status,String message){if(finishing)return;finishing=true;finalStatus=status;detail=message;RevivalFaceRenderProbe.active=false;RagdollRenderCounters.active=false;}
        boolean cleanup()throws Exception{
            if(pending!=null&&!pending.isDone())return false;
            if(!restoreSubmitted){restoreSubmitted=true;pending=task(()->{
                if(player==null)return;if(DownedManager.isDowned(player))DownedManager.revive(player);ExpandedRagdollRecovery.release(player);ExpandedReactionTransition.begin(player);player.stopUsingItem();player.stopRiding();
                if(inventory!=null)player.getInventory().load(inventory);var cap=AccessoriesCapability.get(player);saved.forEach((name,s)->{var c=cap.getContainers().get(name);for(int i=0;i<s.normal.size();i++){c.getAccessories().setItem(i,s.normal.get(i));c.getCosmeticAccessories().setItem(i,s.cosmetic.get(i));c.renderOptions().set(i,s.flags.get(i));}c.markChanged(false);});
                blocks.forEach((p,s)->player.serverLevel().setBlockAndUpdate(p,s));player.serverLevel().setDayTime(dayTime);if(gameType!=null)player.setGameMode(gameType);player.getAbilities().flying=flying;player.onUpdateAbilities();player.setHealth(health);player.getFoodData().setFoodLevel(food);player.getFoodData().setSaturation(saturation);player.setInvisible(serverInvisible);player.teleportTo(player.serverLevel(),position.x,position.y,position.z,yaw,pitch);player.setDeltaMovement(Vec3.ZERO);player.fallDistance=0;player.inventoryMenu.broadcastChanges();
                report.put("serverInventoryRestored",inventory==null||inventory.equals(player.getInventory().save(new ListTag())));report.put("serverAccessoriesRestored",saved.entrySet().stream().allMatch(e->{var c=cap.getContainers().get(e.getKey());for(int i=0;i<e.getValue().normal.size();i++)if(!ItemStack.matches(c.getAccessories().getItem(i),e.getValue().normal.get(i))||!ItemStack.matches(c.getCosmeticAccessories().getItem(i),e.getValue().cosmetic.get(i))||c.shouldRender(i)!=e.getValue().flags.get(i))return false;return true;}));report.put("serverDownedCleared",!DownedManager.isDowned(player));report.put("serverSessionReleased",!ExpandedRagdollRecovery.active(player));
            });return false;}
            if(pending!=null){if(!pending.isDone())return false;try{pending.join();}catch(Throwable t){finalStatus="FAIL";detail+="; cleanup "+t;}pending=null;}
            if(++cleanupTicks<35)return false;
            boolean restored=mc.player!=null&&!RevivalClient.isDowned(mc.player)&&ExpandedRagdollRecovery.clientRestored(mc.player)&&mc.player.isInvisible()==invisible;
            if(!restored&&cleanupTicks<200)return false;
            if(!restored){finalStatus="FAIL";detail+="; client revival state not restored";}
            RagdollClientConfig.USE_FIRST_PERSON_CAMERA.set(firstPersonRagdoll);mc.options.setCameraType(camera);mc.options.hideGui=gui;keys.forEach(KeyMapping::setDown);if(mc.player!=null){mc.player.setYRot(yaw);mc.player.yRotO=yaw;mc.player.setXRot(pitch);mc.player.xRotO=pitch;}
            CombatRenderCounters.active=false;report.put("clientRevivalStateRestored",restored);report.put("cameraAndInputRestored",mc.options.getCameraType()==camera&&keys.entrySet().stream().allMatch(e->e.getKey().isDown()==e.getValue()));report.put("nativeRevivalGraceNote","Native 200-tick post-revival protection is allowed to expire naturally; its private state is never rewritten.");report.put("finished",true);write(finalStatus,detail);return true;
        }
        void write(String state,String message)throws Exception{report.put("status",state);report.put("detail",message);Files.writeString(out.resolve("revival-face-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}
    }
}
