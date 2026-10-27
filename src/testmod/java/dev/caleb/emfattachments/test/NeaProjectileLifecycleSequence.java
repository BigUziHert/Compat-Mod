package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.config.CompatConfig;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.*;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

/** Small real-input bow/camera lifecycle, separate from the exhaustive weapon matrix. */
public final class NeaProjectileLifecycleSequence {
    private static Session active;
    private NeaProjectileLifecycleSequence() {}
    public static void register() {
        NeoForge.EVENT_BUS.addListener(NeaProjectileLifecycleSequence::tick);
        NeoForge.EVENT_BUS.addListener(NeaProjectileLifecycleSequence::pre);
        NeoForge.EVENT_BUS.addListener(NeaProjectileLifecycleSequence::frame);
    }
    private static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(active!=null) {
            try { active.tick(); } catch(Throwable e) { active.finish("FAIL",e.toString()); }
            return;
        }
        if(mc.player==null||mc.screen!=null||CombatRenderCounters.active) return;
        Path trigger=mc.gameDirectory.toPath().resolve("emfattachments-nea-lifecycle.txt");
        if(!Files.isRegularFile(trigger)) return;
        String label="nea-lifecycle";
        try {
            if(!mc.hasSingleplayerServer()||!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client")))
                throw new IllegalStateException("Disposable integrated client required");
            String[] args=Files.readString(trigger).trim().split("\\s+");Files.delete(trigger);
            label=args[0].replaceAll("[^a-zA-Z0-9._-]","_");
            active=new Session(mc,label,Set.of(Arrays.copyOfRange(args,1,args.length))); active.prepare();
        } catch(Throwable e) {
            if(active!=null) active.finish("FAIL",e.toString());
            else try {
                Path output=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(output);
                Files.writeString(output.resolve("nea-lifecycle-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("status","FAIL","detail",e.toString())));
            } catch(Exception ignored) {}
        }
    }
    private static void pre(RenderFrameEvent.Pre event) { if(active!=null&&!active.finishing) CombatRenderCounters.reset(); }
    private static void frame(RenderFrameEvent.Post event) {
        if(active!=null&&!active.finishing) try { active.frame(); } catch(Throwable e) { active.finish("FAIL",e.toString()); }
    }
    private static final class Session {
        final Minecraft mc; final LocalPlayer player; final Path output; final CameraType camera;
        final boolean gui; final float yaw,pitch,bodyYaw,headYaw; final Vec3 position,motion;
        final boolean fpmPresent,originalFpm,originalBowCompatibility;
        final Set<String> options;
        final Map<KeyMapping,Boolean> keys=new LinkedHashMap<>();
        final Map<String,Object> report=new LinkedHashMap<>(); final List<Map<String,Object>> captures=new ArrayList<>();
        final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>(); final Vec3 origin;
        volatile List<ItemStack> inventory; volatile Vec3 serverPosition,serverMotion; volatile float saturation; volatile int food;
        volatile long restoreTick; CompletableFuture<Void> ready; int stage,ticks; boolean finishing; String finalStatus,finalDetail;
        Session(Minecraft mc,String label,Set<String> options) throws Exception {
            this.mc=mc; player=mc.player;
            this.options=options;
            if(!Set.of("fpm-disabled","bow-compat-off").containsAll(options))throw new IllegalArgumentException("Unknown lifecycle option: "+options);
            fpmPresent=net.neoforged.fml.ModList.get().isLoaded("firstperson");
            if(options.contains("fpm-disabled")&&!fpmPresent)throw new IllegalArgumentException("fpm-disabled control requires installed FirstPerson");
            originalFpm=fpmPresent&&Fpm.enabled();originalBowCompatibility=CompatConfig.firstPersonBowPose();
            if(dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.bowAnimation!=dev.tr7zw.notenoughanimations.versionless.animations.BowAnimation.CUSTOM_V1)
                throw new IllegalStateException("This scope fixture requires the existing CUSTOM_V1 bow setting; it will not change NEA configuration");
            if(player.isPassenger()||player.isUsingItem()||ExpandedCombatActions.attackActive(player)) throw new IllegalStateException("Start recovered, idle and unmounted");
            output=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label); Files.createDirectories(output);
            camera=mc.options.getCameraType();gui=mc.options.hideGui;yaw=player.getYRot();pitch=player.getXRot();bodyYaw=player.yBodyRot;headYaw=player.yHeadRot;position=player.position();motion=player.getDeltaMovement();
            origin=new Vec3(288.5,mc.level.getMinBuildHeight()+13,288.5);
            for(var key:List.of(mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyJump,mc.options.keySprint,mc.options.keyShift,mc.options.keyAttack,mc.options.keyUse)) keys.put(key,key.isDown());
            report.put("label",label);report.put("captures",captures);report.put("bowConfig",dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.bowAnimation.name());
            report.put("options",options);report.put("firstPersonInstalled",fpmPresent);report.put("originalFirstPersonEnabled",originalFpm);report.put("originalBowCompatibility",originalBowCompatibility);
            report.put("status","PREPARING");write();
        }
        ServerPlayer sp() { return Objects.requireNonNull(mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID())); }
        void prepare() {
            keys.keySet().forEach(k->k.setDown(false)); mc.options.hideGui=false; CombatRenderCounters.active=true;
            if(options.contains("fpm-disabled"))Fpm.enabled(false);
            if(options.contains("bow-compat-off"))CompatConfig.FIRST_PERSON_BOW_POSE.set(false);
            ready=new CompletableFuture<>(); mc.getSingleplayerServer().execute(()-> {
                try {
                    var server=sp(); inventory=new ArrayList<>(); for(int i=0;i<server.getInventory().getContainerSize();i++) inventory.add(server.getInventory().getItem(i).copy());
                    serverPosition=server.position();serverMotion=server.getDeltaMovement();food=server.getFoodData().getFoodLevel();saturation=server.getFoodData().getSaturationLevel();
                    for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=-1;y<=5;y++) {
                        BlockPos pos=BlockPos.containing(origin).offset(x,y,z);blocks.put(pos,server.level().getBlockState(pos));server.level().setBlock(pos,y==-1?Blocks.BEDROCK.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
                    }
                    ExpandedRagdollRecovery.release(server);ExpandedReactionTransition.begin(server);
                    server.teleportTo(origin.x,origin.y,origin.z);server.setDeltaMovement(Vec3.ZERO);server.fallDistance=0;ExpandedReactionTransition.displaced(server);
                    server.getFoodData().setFoodLevel(10);server.getInventory().setItem(35,new ItemStack(Items.ARROW,64));
                    server.setItemInHand(InteractionHand.MAIN_HAND,Items.BOW.getDefaultInstance());server.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);server.inventoryMenu.broadcastChanges();ready.complete(null);
                } catch(Throwable e) { ready.completeExceptionally(e); }
            });
        }
        void tick() {
            if(mc.player!=player||mc.level==null||mc.screen!=null) throw new IllegalStateException("World or UI changed");
            ticks++;
            if(finishing) {
                if(ready.isCompletedExceptionally()) ready.join();
                if(ready.isDone() && mc.getSingleplayerServer().getTickCount()-restoreTick>=30 && ticks>=35) {
                    player.setDeltaMovement(motion);player.setYRot(yaw);player.yRotO=yaw;player.setXRot(pitch);player.xRotO=pitch;
                    player.yBodyRot=bodyYaw;player.yBodyRotO=bodyYaw;player.yHeadRot=headYaw;player.yHeadRotO=headYaw;
                    mc.options.setCameraType(camera);mc.options.hideGui=gui;keys.forEach(KeyMapping::setDown);
                    if(fpmPresent)Fpm.enabled(originalFpm);CompatConfig.FIRST_PERSON_BOW_POSE.set(originalBowCompatibility);
                    report.put("clientSettingsRestored",true);report.put("bowConfigPreserved",Objects.equals(report.get("bowConfig"),dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.bowAnimation.name()));
                    report.put("nativeClientSeatReleased",!player.isPassenger());report.put("status",finalStatus);report.put("detail",finalDetail);
                    report.put("firstPersonSettingRestored",!fpmPresent||Fpm.enabled()==originalFpm);report.put("bowCompatibilitySettingRestored",CompatConfig.firstPersonBowPose()==originalBowCompatibility);write();active=null;
                } return;
            }
            if(ready.isCompletedExceptionally()) ready.join();
            mc.options.hideGui=false;player.setYRot(0);player.yRotO=0;player.yBodyRot=0;player.yBodyRotO=0;player.yHeadRot=0;player.yHeadRotO=0;player.setXRot(55);player.xRotO=55;
            if(stage==0 && ready.isDone()&&ticks>=15&&player.getMainHandItem().is(Items.BOW)) { mc.options.setCameraType(CameraType.FIRST_PERSON);use();next(1); }
            else if(stage==6 && ready.isDone()&&ticks>=12&&player.getMainHandItem().is(Items.BREAD)) {use();next(7);}
            else if(stage==8 && ExpandedCombatActions.ready(player,weapon(),ready,ticks)) {ExpandedCombatActions.startAttack(mc,0);next(9);}
            if(ticks>200 && stage!=10) throw new IllegalStateException("Stage timeout "+stage);
        }
        ItemStack weapon() {return BuiltInRegistries.ITEM.get(ResourceLocation.parse("simplyswords:diamond_greathammer")).getDefaultInstance();}
        void use() { if(!mc.gameMode.useItem(player,InteractionHand.MAIN_HAND).consumesAction()) throw new IllegalStateException("Native use rejected");mc.options.keyUse.setDown(true); }
        void next(int value) {stage=value;ticks=0;report.put("stage",stage);write();}
        void frame() throws Exception {
            switch(stage) {
                case 1 -> {if(ticks>=25){capture("bow_first_person",true,true);mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);next(2);}}
                case 2 -> {if(ticks>=15){capture("bow_f5_back",true,true);mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);next(3);}}
                case 3 -> {if(ticks>=15){capture("bow_f5_front",true,true);mc.options.setCameraType(CameraType.FIRST_PERSON);next(4);}}
                case 4 -> {if(ticks>=15){capture("bow_return_first_person",true,true);mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(player);next(5);}}
                case 5 -> {if(ticks>=25){capture("bow_released_idle",false,false);ready=ExpandedCombatActions.equip(mc,Items.BREAD.getDefaultInstance(),ItemStack.EMPTY);next(6);}}
                case 7 -> {if(ticks>=16){capture("food_native_use",true,false);mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(player);ready=ExpandedCombatActions.equip(mc,weapon(),ItemStack.EMPTY);next(8);}}
                case 9 -> {if(ticks>=8){if(!ExpandedCombatActions.attackActive(player)) throw new AssertionError("Native swing absent");capture("melee_active",false,false);next(10);}}
                case 10 -> {if(ticks>=65&&!ExpandedCombatActions.attackActive(player)){capture("melee_expired",false,false);report.put("remoteControl",remote());finish("CAPTURED","Eight native lifecycle views plus remote model control; inspect actual transforms.");}}
                default -> {}
            }
        }
        void capture(String name,boolean using,boolean customBow) throws Exception {
            if(player.isUsingItem()!=using) throw new AssertionError(name+" native using state incorrect");
            var row=new LinkedHashMap<String,Object>(CombatRenderCounters.snapshot()); row.put("name",name);row.put("cameraType",mc.options.getCameraType().name());row.put("usingItem",player.isUsingItem());row.put("useElapsed",player.getTicksUsingItem());
            var pose=NeaProjectilePoseProbe.snapshot(player.getUUID());row.put("pose",pose);
            boolean selected=String.valueOf(pose.get("selected")).contains("CustomBowAnimation");
            row.put("customBowSelected",selected);row.put("expectedCustomBow",customBow);
            boolean bodyExpected=!mc.options.getCameraType().isFirstPerson()||CombatRenderCounters.bodyPasses>0;
            row.put("neaBodyPoseApplicable",bodyExpected);
            if(bodyExpected&&selected!=customBow) throw new AssertionError(name+" stale/missing selected custom bow pose: "+pose);
            if(customBow&&bodyExpected) {
                if(!Boolean.TRUE.equals(pose.get("usingItem")))throw new AssertionError(name+" native model was not using its bow");
                boolean corrected=mc.options.getCameraType().isFirstPerson()&&fpmPresent&&Fpm.enabled()&&CompatConfig.firstPersonBowPose();
                assertBowTarget(pose,corrected,name);row.put("nativeBowTargetVerified",corrected?"LOCAL_NATIVE_BOW_DRAW":"UNCHANGED_CUSTOM_V1");
            }
            row.put("firstPersonEnabled",fpmPresent&&Fpm.enabled());row.put("bowCompatibilityEnabled",CompatConfig.firstPersonBowPose());
            String file=String.format(Locale.ROOT,"%02d-%s.png",captures.size()+1,name);
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {image.writeToFile(output.resolve(file));}
            row.put("screenshot",file);captures.add(row);write();
        }
        Map<String,Object> remote() {
            var remote=new BowRemotePlayer(mc.level);remote.setPos(player.position());remote.setXRot(55);remote.xRotO=55;remote.tickCount=120;
            remote.setYRot(0);remote.yRotO=0;remote.yBodyRot=remote.yBodyRotO=0;remote.yHeadRot=remote.yHeadRotO=0;
            remote.setItemInHand(InteractionHand.MAIN_HAND,Items.BOW.getDefaultInstance());remote.nativeBowUse(true);
            if(!remote.isUsingItem())throw new AssertionError("Remote native synced use flag absent");
            var renderer=(PlayerRenderer)(Object)mc.getEntityRenderDispatcher().getRenderer(remote); var model=renderer.getModel();
            Map<ModelPart,float[]> saved=new IdentityHashMap<>();Map<ModelPart,boolean[]> flags=new IdentityHashMap<>();
            for(ModelPart part:List.of(model.head,model.hat,model.body,model.leftArm,model.rightArm,model.leftLeg,model.rightLeg,model.leftSleeve,model.rightSleeve,model.leftPants,model.rightPants,model.jacket)) part.getAllParts().forEach(p->{saved.put(p,ModelState.state(p));flags.put(p,new boolean[]{p.visible,p.skipDraw});});
            boolean counters=CombatRenderCounters.active;CombatRenderCounters.active=false;Counter counter=new Counter();
            try(var context=new EmfProbeContext(remote)) {
                renderer.render(remote,0,0,new PoseStack(),type->counter,0xF000F0);
                var result=new LinkedHashMap<>(NeaProjectilePoseProbe.snapshot(remote.getUUID()));result.put("vertices",counter.vertices);result.put("invalidVertices",counter.invalid);
                if(counter.vertices==0||counter.invalid!=0) throw new AssertionError("Remote bow geometry absent/invalid");
                if(!String.valueOf(result.get("selected")).contains("CustomBowAnimation")) throw new AssertionError("Remote native bow selection missing");
                if(!Boolean.TRUE.equals(result.get("usingItem"))||!Boolean.FALSE.equals(result.get("localPlayer")))throw new AssertionError("Remote active-use identity control missing");
                assertBowTarget(result,false,"remote");result.put("nativeCustomBowTargetVerified",true);
                return result;
            } finally {
                remote.nativeBowUse(false);strm.emfcompat.core.PoseManager.clearPoses(remote.getUUID());CombatRenderCounters.active=counters;
                saved.forEach((p,s)->{p.x=s[0];p.y=s[1];p.z=s[2];p.xRot=s[3];p.yRot=s[4];p.zRot=s[5];p.xScale=s[6];p.yScale=s[7];p.zScale=s[8];p.visible=flags.get(p)[0];p.skipDraw=flags.get(p)[1];});
            }
        }
        void finish(String status,String detail) {
            if(finishing) {report.put("restoreError",detail);report.put("status","RESTORE_FAILURE");write();active=null;return;}
            finishing=true;finalStatus=status;finalDetail=detail;ticks=0;CombatRenderCounters.active=false;mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(player);
            if(fpmPresent)Fpm.enabled(originalFpm);CompatConfig.FIRST_PERSON_BOW_POSE.set(originalBowCompatibility);
            ready=new CompletableFuture<>();mc.getSingleplayerServer().execute(()->{
                try {
                    var server=sp();ExpandedRagdollRecovery.release(server);ExpandedReactionTransition.begin(server);server.stopUsingItem();
                    if(inventory!=null)for(int i=0;i<inventory.size();i++)server.getInventory().setItem(i,inventory.get(i));
                    for(var entity:server.serverLevel().getAllEntities()) if(entity instanceof Projectile projectile && projectile.getOwner()==server && entity.distanceToSqr(origin)<4096) entity.discard();
                    blocks.forEach((p,s)->server.level().setBlock(p,s,3));server.getFoodData().setFoodLevel(food);server.getFoodData().setSaturation(saturation);
                    if(serverPosition!=null)server.teleportTo(serverPosition.x,serverPosition.y,serverPosition.z);server.setDeltaMovement(Vec3.ZERO);server.fallDistance=0;ExpandedReactionTransition.displaced(server);server.inventoryMenu.broadcastChanges();
                    restoreTick=mc.getSingleplayerServer().getTickCount();report.put("serverInventoryRestored",true);report.put("arenaRestored",true);ready.complete(null);
                } catch(Throwable e) {ready.completeExceptionally(e);}
            });
        }
        void write() {try {Files.writeString(output.resolve("nea-lifecycle-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception e){throw new RuntimeException(e);}}
    }
    /** Targets are checked before bridge smoothing, independently of any later cached output. */
    private static void assertBowTarget(Map<String,Object> pose,boolean nativeDraw,String label) {
        if(!(pose.get("neaArms") instanceof Map<?,?> arms)
                || !(arms.get("left") instanceof List<?> left)
                || !(arms.get("right") instanceof List<?> right))
            throw new AssertionError(label+" missing actual native NEA arm target");
        float aim=(float)Math.toRadians(55);
        float rightX=nativeDraw ? -(float)Math.PI/2+aim : -(float)Math.PI/2;
        float leftX=nativeDraw ? rightX : -(float)Math.PI/2+0.8f;
        // Vanilla retains the native +/-0.05-radian idle arm bob; CustomV1 replaces pitch.
        near(((Number)right.get(3)).floatValue(),rightX,nativeDraw?.06f:.03f,label+" right target pitch");
        near(((Number)left.get(3)).floatValue(),leftX,nativeDraw?.06f:.03f,label+" left target pitch");
        near(((Number)right.get(5)).floatValue(),nativeDraw?0:1.5f,.11f,label+" right target roll");
        near(((Number)left.get(5)).floatValue(),nativeDraw?0:1.5f,.11f,label+" left target roll");
        if(!nativeDraw) {
            near(((Number)right.get(4)).floatValue(),-.1f-aim,.03f,label+" right CustomV1 yaw");
            near(((Number)left.get(4)).floatValue(),.1f-aim,.03f,label+" left CustomV1 yaw");
        }
    }
    private static void near(float actual,float expected,float tolerance,String label) {
        if(!Float.isFinite(actual)||Math.abs(actual-expected)>tolerance)
            throw new AssertionError(label+": expected "+expected+" +/- "+tolerance+", got "+actual);
    }
    /** Reproduce the synchronized native use bit; client RemotePlayer cannot receive a server packet here. */
    private static final class BowRemotePlayer extends RemotePlayer {
        BowRemotePlayer(net.minecraft.client.multiplayer.ClientLevel level) {
            super(level,new GameProfile(UUID.randomUUID(),"BowScopeControl"));
        }
        void nativeBowUse(boolean using) {
            if(using) { startUsingItem(InteractionHand.MAIN_HAND);setLivingEntityFlag(1,true); }
            else {stopUsingItem();setLivingEntityFlag(1,false);}
        }
    }
    /** Optional dependency is reached only after the installed-mod guard. */
    private static final class Fpm {
        static boolean enabled(){return dev.tr7zw.firstperson.api.FirstPersonAPI.isEnabled();}
        static void enabled(boolean value){dev.tr7zw.firstperson.api.FirstPersonAPI.setEnabled(value);}
    }
    private static final class Counter implements VertexConsumer {
        int vertices,invalid;
        public VertexConsumer addVertex(float x,float y,float z){vertices++;if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))invalid++;return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}public VertexConsumer setUv(float u,float v){return this;}
        public VertexConsumer setUv1(int u,int v){return this;}public VertexConsumer setUv2(int u,int v){return this;}public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
}
