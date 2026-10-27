package dev.caleb.emfattachments.test;

import com.github.L_Ender.cataclysm.entity.projectile.Tidal_Hook_Entity;
import com.github.L_Ender.cataclysm.init.ModDataAttachments;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Native Tidal Claws block grapple, including pull and release; disposable world only. */
public final class ExpandedTidalGrappleSequence {
    private static Session active;
    private ExpandedTidalGrappleSequence() {}
    public static void register(){
        NeoForge.EVENT_BUS.addListener(ExpandedTidalGrappleSequence::tick);
        NeoForge.EVENT_BUS.addListener(ExpandedTidalGrappleSequence::preFrame);
        NeoForge.EVENT_BUS.addListener(ExpandedTidalGrappleSequence::postFrame);
    }
    private static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(active!=null){try{active.tick();}catch(Throwable e){active.finish("FAIL",e.toString());}return;}
        if(mc.player==null||mc.screen!=null||CombatRenderCounters.active||ExpandedLiveWearableSequence.isActive())return;
        var trigger=mc.gameDirectory.toPath().resolve("emfattachments-tidal-grapple.txt");
        if(!Files.isRegularFile(trigger))return;
        try{
            if(!mc.hasSingleplayerServer()||!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client")))throw new IllegalStateException("Disposable integrated world only");
            String label=Files.readString(trigger).trim().replaceAll("[^a-zA-Z0-9._-]","_");Files.delete(trigger);
            active=new Session(mc,label);active.prepare();
        }catch(Throwable e){if(active!=null)active.finish("FAIL",e.toString());LogUtils.getLogger().error("Tidal grapple fixture failed",e);}
    }
    private static void preFrame(RenderFrameEvent.Pre e){if(active!=null&&!active.finishing)CombatRenderCounters.reset();}
    private static void postFrame(RenderFrameEvent.Post e){if(active!=null&&!active.finishing)try{active.frame();}catch(Throwable ex){active.finish("FAIL",ex.toString());}}

    private static final class Session {
        final Minecraft mc;final UUID uuid;final Path output;final CameraType camera;final boolean gui,clientHookFalling,clientNoGravity;final float yaw,pitch,clientFall;final Map<KeyMapping,Boolean> keys=new LinkedHashMap<>();
        final Map<String,Object> report=new LinkedHashMap<>();final List<Map<String,Object>> samples=new ArrayList<>(),shots=new ArrayList<>();final List<String> failures=new ArrayList<>();
        final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();Snapshot original;volatile Vec3 origin;CompletableFuture<?> preparation,restoration;CompletableFuture<Map<String,Object>> sample;String finalStatus,finalDetail;int restorationTicks;
        Map<String,Object> observed=Map.of();String phase="PREPARING",capture;int ticks,stable,useTicks,latchedAt;boolean finishing,launchCaptured,pullCaptured,thirdCaptured;double maxRise,maxUpward;ItemStack stableClaws;
        Map<String,Object> impactControls,postReleaseImpactControl;
        Session(Minecraft mc,String label)throws Exception{
            this.mc=mc;uuid=mc.player.getUUID();if(mc.player.isPassenger()||mc.player.isUsingItem())throw new IllegalStateException("Start unmounted and not using an item");
            output=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(output);camera=mc.options.getCameraType();gui=mc.options.hideGui;yaw=mc.player.getYRot();pitch=mc.player.getXRot();clientHookFalling=mc.player.getData(ModDataAttachments.HOOK_FALLING);clientNoGravity=mc.player.isNoGravity();clientFall=mc.player.fallDistance;
            for(var k:List.of(mc.options.keyUse,mc.options.keyAttack,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyJump,mc.options.keyShift,mc.options.keySprint)){keys.put(k,k.isDown());k.setDown(false);}
            report.put("label",label);report.put("scope","Real native Tidal Claws use key, block collision, native hook pull and upward movement, releaseUsing, hook death and gravity recovery. No hook or motion is spawned/applied by the fixture after native use.");report.put("firstPersonInstalled",net.neoforged.fml.ModList.get().isLoaded("firstperson"));report.put("samples",samples);report.put("screenshots",shots);report.put("failures",failures);report.put("resourcePacks",mc.getResourcePackRepository().getSelectedIds());
            mc.options.hideGui=false;mc.options.setCameraType(CameraType.FIRST_PERSON);CombatRenderCounters.active=true;
        }
        <T> CompletableFuture<T> server(Function<ServerPlayer,T> fn){var f=new CompletableFuture<T>();mc.getSingleplayerServer().execute(()->{try{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);if(p==null)throw new IllegalStateException("Missing player");f.complete(fn.apply(p));}catch(Throwable e){f.completeExceptionally(e);}});return f;}
        List<Tidal_Hook_Entity> hooks(ServerPlayer p){return p.serverLevel().getEntitiesOfClass(Tidal_Hook_Entity.class,p.getBoundingBox().inflate(80),h->h.getOwner()==p);}
        void prepare(){
            preparation=server(p->{
                if(p.getData(ModDataAttachments.HOOK_FALLING)||!hooks(p).isEmpty())throw new IllegalStateException("Existing native grapple must finish before this test");
                original=new Snapshot(p);ExpandedRagdollRecovery.release(p);
                int y=p.serverLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,p.getBlockX()+64,p.getBlockZ()+64)+24;
                BlockPos floor=new BlockPos(p.getBlockX()+64,y,p.getBlockZ()+64);origin=new Vec3(floor.getX()+.5,y+1,floor.getZ()+.5);
                // Every changed block is verified air, snapshotted and restored; no existing terrain is overwritten.
                for(int x=-4;x<=4;x++)for(int z=-4;z<=14;z++)for(int dy=0;dy<=17;dy++){
                    var pos=floor.offset(x,dy,z);if(!p.serverLevel().getBlockState(pos).isAir()||p.serverLevel().getBlockEntity(pos)!=null)throw new IllegalStateException("Grapple arena is not empty at "+pos);
                    if(dy==0||z==12)blocks.put(pos,p.serverLevel().getBlockState(pos));
                }
                blocks.keySet().forEach(pos->p.serverLevel().setBlock(pos,Blocks.STONE.defaultBlockState(),3));
                p.getInventory().clearContent();p.setItemInHand(InteractionHand.MAIN_HAND,BuiltInRegistries.ITEM.get(ResourceLocation.parse("cataclysm:tidal_claws")).getDefaultInstance());p.setItemSlot(EquipmentSlot.HEAD,BuiltInRegistries.ITEM.get(ResourceLocation.parse("aeronautics:aviators_goggles")).getDefaultInstance());
                p.setGameMode(GameType.SURVIVAL);p.getAbilities().flying=false;p.onUpdateAbilities();p.setNoGravity(false);p.removeAllEffects();p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);p.teleportTo(p.serverLevel(),origin.x,origin.y,origin.z,0,-35);p.setDeltaMovement(Vec3.ZERO);p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));p.inventoryMenu.broadcastChanges();return null;
            });write("RUNNING","");
        }
        Map<String,Object> observe(ServerPlayer p){
            var found=hooks(p);boolean pulling=false;var hookPositions=new ArrayList<String>();
            for(var h:found){var tag=new CompoundTag();h.addAdditionalSaveData(tag);pulling|=tag.getBoolean("isPulling");hookPositions.add(h.position().toString());}
            if(net.neoforged.fml.ModList.get().isLoaded("ragdoll_reactions"))try{
                if(pulling&&impactControls==null&&p.getY()>origin.y)impactControls=TidalImpactProbe.whilePulling(p,found.getFirst());
                if(impactControls!=null&&postReleaseImpactControl==null&&found.isEmpty()&&!p.isUsingItem()&&!p.getData(ModDataAttachments.HOOK_FALLING))postReleaseImpactControl=TidalImpactProbe.afterRelease(p);
            }catch(Exception e){throw new IllegalStateException("Native Tidal impact controls failed",e);}
            var m=new LinkedHashMap<String,Object>();m.put("hookCount",found.size());m.put("pulling",pulling);m.put("noGravity",p.isNoGravity());m.put("hookFalling",p.getData(ModDataAttachments.HOOK_FALLING));m.put("serverUsing",p.isUsingItem());m.put("position",p.position().toString());m.put("rise",p.getY()-origin.y);m.put("verticalSpeed",p.getDeltaMovement().y);m.put("hookPositions",hookPositions);m.put("nativeRagdoll",ExpandedRagdollRecovery.active(p));if(impactControls!=null)m.put("impactControls",impactControls);if(postReleaseImpactControl!=null)m.put("postReleaseImpactControl",postReleaseImpactControl);return m;
        }
        void tick(){
            if(finishing){
                if(restoration==null||!restoration.isDone()||++restorationTicks<10)return;
                Throwable error=null;try{restoration.join();}catch(Throwable e){error=e;}
                // kill() intentionally sends HOOK_FALLING=true to the client for native fall protection.
                // Observe that untouched during the case, then restore the pre-test client state after packets settle.
                if(mc.player!=null){mc.player.setData(ModDataAttachments.HOOK_FALLING,clientHookFalling);mc.player.setNoGravity(clientNoGravity);mc.player.fallDistance=clientFall;}
                report.put("stateRestored",error==null);report.put("worldBlocksRestored",error==null);report.put("clientHookStateRestored",mc.player!=null&&mc.player.getData(ModDataAttachments.HOOK_FALLING)==clientHookFalling&&mc.player.isNoGravity()==clientNoGravity);report.put("inputRestored",keys.entrySet().stream().allMatch(e->e.getKey().isDown()==e.getValue()));write(error==null?finalStatus:"FAIL",error==null?finalDetail:error.toString());active=null;return;
            }
            if(mc.player==null||mc.screen!=null||!mc.player.isAlive())throw new IllegalStateException("Player/world changed during grapple");ticks++;
            if(phase.equals("PREPARING")){
                if(preparation.isCompletedExceptionally())preparation.join();if(!preparation.isDone())return;
                mc.player.setYRot(0);mc.player.setXRot(-35);
                boolean ready=mc.player.getMainHandItem().is(BuiltInRegistries.ITEM.get(ResourceLocation.parse("cataclysm:tidal_claws")))&&mc.player.position().distanceTo(origin)<1&&mc.player.onGround()&&!mc.player.isPassenger();stable=ready?stable+1:0;
                if(ticks>180)throw new IllegalStateException("Grapple equipment/platform readiness timeout at "+mc.player.position());if(stable<8)return;
                stableClaws=mc.player.getMainHandItem();mc.options.keyUse.setDown(true);mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);if(!mc.player.isUsingItem()||mc.player.getUseItem()!=stableClaws)throw new IllegalStateException("Native claw use did not begin");phase="PULLING";ticks=0;report.put("initialUseDuration",mc.player.getUseItemRemainingTicks());sample=server(this::observe);return;
            }
            if(sample!=null&&sample.isDone()){
                observed=sample.join();if(observed.containsKey("impactControls"))report.put("impactControls",observed.get("impactControls"));if(observed.containsKey("postReleaseImpactControl"))report.put("postReleaseImpactControl",observed.get("postReleaseImpactControl"));var sampleRow=new LinkedHashMap<>(observed);sampleRow.remove("impactControls");sampleRow.remove("postReleaseImpactControl");sampleRow.put("phase",phase);sampleRow.put("clientTick",ticks);samples.add(sampleRow);maxRise=Math.max(maxRise,((Number)observed.get("rise")).doubleValue());maxUpward=Math.max(maxUpward,((Number)observed.get("verticalSpeed")).doubleValue());sample=null;
            }
            if(sample==null)sample=server(this::observe);
            if(phase.equals("PULLING")){
                mc.options.keyUse.setDown(true);useTicks=stableClaws.getUseDuration(mc.player)-mc.player.getUseItemRemainingTicks();
                if(!mc.player.isUsingItem()||mc.player.getUseItem()!=stableClaws)throw new IllegalStateException("Native grapple use ended unexpectedly");
                if(Boolean.TRUE.equals(observed.get("nativeRagdoll")))throw new IllegalStateException("Native ragdoll interrupted active grapple: "+observed);
                if(!launchCaptured&&ticks>=2&&capture==null)capture="native-launch-firstperson";
                if(launchCaptured&&!pullCaptured&&Boolean.TRUE.equals(observed.get("pulling"))&&maxRise>1&&capture==null)capture="latched-pulling-firstperson";
                if(pullCaptured&&!thirdCaptured&&ticks>=latchedAt+5&&maxRise>3&&capture==null)capture="latched-pulling-thirdperson";
                if(ticks>120)throw new IllegalStateException("Native hook did not latch and pull upward: "+observed);
            }else if(phase.equals("RELEASING")){
                boolean cleaned=observed.get("hookCount") instanceof Number n&&n.intValue()==0&&!Boolean.TRUE.equals(observed.get("noGravity"))&&!Boolean.TRUE.equals(observed.get("hookFalling"));
                stable=cleaned?stable+1:0;if(stable>=4&&capture==null)capture="released-gravity-restored";
                if(ticks>80)throw new IllegalStateException("Native release did not remove hook and restore gravity: "+observed);
            }
        }
        void frame()throws Exception{
            if(capture==null)return;String requested=capture;capture=null;
            String name=String.format(Locale.ROOT,"%02d-%s.png",shots.size()+1,requested);try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve(name));}
            var s=new LinkedHashMap<String,Object>();s.put("file",name);s.put("stage",requested);s.put("camera",mc.options.getCameraType().name());s.put("useTicks",useTicks);s.put("clientPosition",mc.player.position().toString());s.put("clientNoGravity",mc.player.isNoGravity());s.put("clientHookFalling",mc.player.getData(ModDataAttachments.HOOK_FALLING));s.put("clientUsing",mc.player.isUsingItem());s.put("serverState",observed);s.put("renderCounts",CombatRenderCounters.snapshot());shots.add(s);
            if(requested.equals("native-launch-firstperson"))launchCaptured=true;
            if(requested.equals("latched-pulling-firstperson")){pullCaptured=true;latchedAt=ticks;mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);mc.player.setXRot(8);}
            if(requested.equals("latched-pulling-thirdperson")){thirdCaptured=true;release();phase="RELEASING";ticks=stable=0;mc.options.setCameraType(CameraType.FIRST_PERSON);mc.player.setXRot(35);}
            if(requested.equals("released-gravity-restored")){if(net.neoforged.fml.ModList.get().isLoaded("ragdoll_reactions")&&!report.containsKey("postReleaseImpactControl"))throw new IllegalStateException("Post-release ordinary impact control was not observed");report.put("nativeLatchAndPull",pullCaptured&&maxRise>3&&maxUpward>0);report.put("nativeReleaseAndGravityRestored",true);finish("PASS","Native block grapple, upward pull, release and gravity recovery observed");return;}
            write("RUNNING","");
        }
        void release(){mc.options.keyUse.setDown(false);if(mc.player!=null&&mc.player.isUsingItem())mc.gameMode.releaseUsingItem(mc.player);}
        void finish(String status,String detail){
            if(finishing)return;finishing=true;finalStatus=status;finalDetail=detail;release();CombatRenderCounters.active=false;if(!status.equals("PASS"))failures.add(detail);mc.options.setCameraType(camera);mc.options.hideGui=gui;keys.forEach(KeyMapping::setDown);if(mc.player!=null){mc.player.setYRot(yaw);mc.player.setXRot(pitch);}write("RESTORING",detail);
            restoration=server(p->{
                // Native release first; native kill performs its own gravity/network cleanup.
                if(original!=null){if(p.isUsingItem())p.releaseUsingItem();for(var h:hooks(p))h.kill();ExpandedRagdollRecovery.release(p);}
                if(original!=null)original.restore(p);blocks.forEach((pos,state)->p.serverLevel().setBlock(pos,state,3));
                for(var e:blocks.entrySet())if(!p.serverLevel().getBlockState(e.getKey()).equals(e.getValue()))throw new IllegalStateException("World restoration mismatch");
                if(original!=null&&(p.isNoGravity()!=original.noGravity||p.getData(ModDataAttachments.HOOK_FALLING)!=original.hookFalling))throw new IllegalStateException("Native hook state restoration mismatch");return null;
            });
        }
        void write(String status,String detail){try{report.put("status",status);report.put("detail",detail);report.put("maximumRise",maxRise);report.put("maximumUpwardSpeed",maxUpward);if(origin!=null)report.put("fixtureOrigin",origin.toString());Files.writeString(output.resolve("tidal-grapple-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception e){throw new IllegalStateException(e);}}
    }
    private static final class Snapshot {
        final ListTag inventory;final int selected;final Vec3 position,motion;final float yaw,pitch,health,fall;final boolean flying,noGravity,hookFalling;final GameType gameMode;final CompoundTag food=new CompoundTag();final List<MobEffectInstance> effects;
        Snapshot(ServerPlayer p){inventory=p.getInventory().save(new ListTag());selected=p.getInventory().selected;position=p.position();motion=p.getDeltaMovement();yaw=p.getYRot();pitch=p.getXRot();health=p.getHealth();fall=p.fallDistance;flying=p.getAbilities().flying;noGravity=p.isNoGravity();hookFalling=p.getData(ModDataAttachments.HOOK_FALLING);gameMode=p.gameMode.getGameModeForPlayer();p.getFoodData().addAdditionalSaveData(food);effects=p.getActiveEffects().stream().map(MobEffectInstance::new).toList();}
        void restore(ServerPlayer p){p.getInventory().load(inventory);p.getInventory().selected=selected;p.setGameMode(gameMode);p.getAbilities().flying=flying;p.onUpdateAbilities();p.removeAllEffects();effects.forEach(p::addEffect);p.setHealth(health);p.getFoodData().readAdditionalSaveData(food);p.setNoGravity(noGravity);p.setData(ModDataAttachments.HOOK_FALLING,hookFalling);p.teleportTo(p.serverLevel(),position.x,position.y,position.z,yaw,pitch);p.setDeltaMovement(motion);p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));p.fallDistance=fall;p.inventoryMenu.broadcastChanges();if(!p.getInventory().save(new ListTag()).equals(inventory))throw new IllegalStateException("Inventory restoration mismatch");}
    }
}
