package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.User;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Real sleeping state and real Sable beds, restricted to the disposable offline world. */
public final class SableSleepSequence {
    private static final UUID OFFLINE = UUID.nameUUIDFromBytes("OfflinePlayer:AttachmentTest".getBytes(StandardCharsets.UTF_8));
    private static volatile Session active;
    private static int polls;
    private static RenderScope rendering;
    private SableSleepSequence() {}
    private record Case(String name, boolean deck, double yaw, double pitch, double roll, boolean moving,
                        CameraType camera, String item, boolean visible) {}
    private static final List<Case> CASES = List.of(
        new Case("world-native-goggles",false,0,0,0,false,CameraType.FIRST_PERSON,"artifacts:night_vision_goggles",true),
        new Case("cardinal-deck-goggles",true,0,0,0,false,CameraType.FIRST_PERSON,"artifacts:night_vision_goggles",true),
        new Case("yaw35-native-goggles",true,35,0,0,false,CameraType.FIRST_PERSON,"artifacts:night_vision_goggles",true),
        new Case("yaw35-third-person-goggles",true,35,0,0,false,CameraType.THIRD_PERSON_BACK,"artifacts:night_vision_goggles",true),
        new Case("yaw35-hidden-goggles",true,35,0,0,false,CameraType.FIRST_PERSON,"artifacts:night_vision_goggles",false),
        new Case("yaw35-native-snorkel",true,35,0,0,false,CameraType.FIRST_PERSON,"artifacts:snorkel",true),
        new Case("yaw35-native-necklace",true,35,0,0,false,CameraType.FIRST_PERSON,"artifacts:cross_necklace",true),
        new Case("yaw75-pitch8-roll6-third-person",true,75,8,6,false,CameraType.THIRD_PERSON_BACK,"artifacts:night_vision_goggles",true),
        new Case("moving-native-goggles",true,35,0,0,true,CameraType.FIRST_PERSON,"artifacts:night_vision_goggles",true),
        new Case("moving-third-person-goggles",true,35,0,0,true,CameraType.THIRD_PERSON_BACK,"artifacts:night_vision_goggles",true),
        new Case("yaw90-native-goggles",true,90,0,0,false,CameraType.FIRST_PERSON,"artifacts:night_vision_goggles",true),
        new Case("yaw145-native-goggles",true,145,0,0,false,CameraType.FIRST_PERSON,"artifacts:night_vision_goggles",true));

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SableSleepSequence::tick);
        NeoForge.EVENT_BUS.addListener(SableSleepSequence::serverTick);
        NeoForge.EVENT_BUS.addListener(SableSleepSequence::preFrame);
        NeoForge.EVENT_BUS.addListener(SableSleepSequence::postFrame);
    }
    private static boolean disposable(Minecraft mc) {
        return mc.player!=null && mc.level!=null && mc.hasSingleplayerServer() && !mc.getSingleplayerServer().isPublished()
            && mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client"))
            && mc.getUser().getType()==User.Type.LEGACY && "AttachmentTest".equals(mc.getUser().getName())
            && OFFLINE.equals(mc.getUser().getProfileId()) && OFFLINE.equals(mc.player.getUUID());
    }
    private static void tick(ClientTickEvent.Post event) {
        if(active!=null) { try { active.tick(); } catch(Throwable t) { active.finish("FAIL",t.toString()); } return; }
        Minecraft mc=Minecraft.getInstance();
        if(++polls%20!=0 || !disposable(mc) || mc.screen!=null || !NativeWorldReadiness.ready())return;
        Path trigger=mc.gameDirectory.toPath().resolve("emfattachments-sable-sleep.txt");
        if(!Files.isRegularFile(trigger))return;
        try {
            String label=Files.readString(trigger).trim().replaceAll("[^a-zA-Z0-9._-]","_");
            if(label.isBlank() || label.equals(".") || label.equals(".."))throw new IllegalArgumentException("Invalid label");
            Files.delete(trigger);active=new Session(mc,label);active.prepare();
        } catch(Throwable t) { if(active!=null)active.finish("FAIL",t.toString());else com.mojang.logging.LogUtils.getLogger().error("Sleep fixture startup",t); }
    }
    private static void serverTick(ServerTickEvent.Pre event) {
        Session s=active;if(s==null || s.finishing || s.platform==null || event.getServer()!=s.server)return;
        try { s.drive(); } catch(Throwable t) { s.serverFailure=t.toString(); }
    }
    private static void preFrame(RenderFrameEvent.Pre event) { if(active!=null){active.frameDraws.clear();active.frameOrientations.clear();active.frameNameTags.clear();} }
    private static void postFrame(RenderFrameEvent.Post event) {
        Session s=active;if(s!=null&&!s.finishing)try{s.frame();}catch(Throwable t){s.finish("FAIL",t.toString());}
    }
    private static boolean shadow(){return ModList.get().isLoaded("iris")&&net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();}
    private static List<Double> vec(Vec3 v){return List.of(v.x,v.y,v.z);}
    private static List<Float> matrix(Matrix4f m){float[] values=new float[16];m.get(values);List<Float> out=new ArrayList<>(16);for(float f:values)out.add(f);return out;}
    private static ItemStack item(String id){ItemStack s=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();if(s.isEmpty())throw new IllegalStateException("Missing "+id);return s;}

    /** Entry before Sable's dispatcher hook, so the witness includes every native rotation. */
    public static RenderScope enter(Entity entity,PoseStack poses){
        Session s=active;
        if(s==null || s.finishing || s.index<0 || (entity!=s.local && entity!=s.remote))return null;
        RenderScope scope=new RenderScope(rendering,entity,new Matrix4f(poses.last().pose()),shadow());rendering=scope;return scope;
    }
    public static final class RenderScope implements AutoCloseable {
        final RenderScope previous;final Entity entity;final Matrix4f entry;final boolean shadow;
        RenderScope(RenderScope previous,Entity entity,Matrix4f entry,boolean shadow){this.previous=previous;this.entity=entity;this.entry=entry;this.shadow=shadow;}
        @Override public void close(){rendering=previous;}
    }
    public static void oriented(LivingEntity entity,PoseStack poses){
        Session s=active;RenderScope scope=rendering;
        if(s==null || scope==null || scope.entity!=entity || !entity.isSleeping() || entity.getBedOrientation()==null)return;
        Matrix4f actual=new Matrix4f(scope.entry).invert().mul(poses.last().pose());
        Quaternionf q=new Quaternionf();
        var sleeping=entity.getSleepingPos();
        SubLevel level=sleeping.isEmpty()?null:dev.ryanhcode.sable.Sable.HELPER.getContaining(entity.level(),sleeping.get());
        if(level instanceof ClientSubLevel client)q.set(client.renderPose().orientation());
        float direction=switch(entity.getBedOrientation()){case SOUTH->90;case WEST->0;case NORTH->270;case EAST->180;default->0;};
        Matrix4f expected=new Matrix4f().rotate(q).rotateY((float)Math.toRadians(direction)).rotateZ((float)Math.PI/2).rotateY((float)Math.PI*1.5f);
        double error=0;
        for(Vector3f axis:List.of(new Vector3f(1,0,0),new Vector3f(0,1,0),new Vector3f(0,0,1))) {
            Vector3f a=actual.transformDirection(new Vector3f(axis)).normalize(),b=expected.transformDirection(new Vector3f(axis)).normalize();
            error=Math.max(error,a.distance(b));
        }
        Map<String,Object> row=new LinkedHashMap<>();row.put("entity",entity==s.local?"local":"remote-client-clone");row.put("shadow",scope.shadow);
        row.put("actualRelativeMatrix",matrix(actual));row.put("expectedRotation",matrix(expected));row.put("maximumBasisError",error);
        row.put("bedFacing",entity.getBedOrientation().name());row.put("sleepingPos",sleeping.map(Object::toString).orElse(""));row.put("position",vec(entity.position()));
        s.frameOrientations.add(row);
    }
    public static void artifact(ItemStack stack,LivingEntity entity,PoseStack poses,MultiBufferSource buffers,java.util.function.Consumer<MultiBufferSource> draw){
        Session s=active;
        if(s==null||s.finishing||s.index<0||(entity!=s.local&&entity!=s.remote)){draw.accept(buffers);return;}
        var count=new AttachmentTestMod.TestResult();
        draw.accept(type->new CountingVertexConsumer(buffers.getBuffer(type),count));
        Map<String,Object> row=new LinkedHashMap<>();row.put("entity",entity==s.local?"local":"remote-client-clone");row.put("shadow",shadow());
        row.put("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());row.put("vertices",count.vertices);row.put("invalidVertices",count.invalidVertices);
        row.put("sleeping",entity.isSleeping());s.frameDraws.add(row);
    }
    /** Actual nameplate matrix after its native billboard rotation, before font scaling. */
    public static void nameTag(Entity entity,PoseStack poses,String text){
        Session s=active;RenderScope scope=rendering;
        if(s==null||s.finishing||scope==null||scope.entity!=entity||entity!=s.remote||!s.remote.isSleeping())return;
        Matrix4f actual=new Matrix4f(scope.entry).invert().mul(poses.last().pose());
        Matrix4f expected=new Matrix4f().rotate(new Quaternionf(s.mc.getEntityRenderDispatcher().cameraOrientation()));
        double error=0;
        for(Vector3f axis:List.of(new Vector3f(1,0,0),new Vector3f(0,1,0),new Vector3f(0,0,1))){
            Vector3f a=actual.transformDirection(new Vector3f(axis)).normalize(),b=expected.transformDirection(new Vector3f(axis)).normalize();
            error=Math.max(error,a.distance(b));
        }
        Map<String,Object> row=new LinkedHashMap<>();row.put("entity","remote-client-clone");row.put("text",text);row.put("shadow",scope.shadow);
        row.put("actualRelativeMatrix",matrix(actual));row.put("expectedBillboardRotation",matrix(expected));row.put("maximumBasisError",error);
        s.frameNameTags.add(row);
    }

    private static final class Witness extends RemotePlayer {
        private final PlayerSkin skin;
        Witness(Minecraft mc,int id){super(mc.level,new GameProfile(UUID.randomUUID(),"SleepWitness"));skin=mc.player.getSkin();setId(id);setNoGravity(true);}
        @Override public PlayerSkin getSkin(){return skin;}
        @Override public boolean isModelPartShown(PlayerModelPart part){return true;}
    }
    private static final class Session {
        final Minecraft mc;final net.minecraft.client.server.IntegratedServer server;final net.minecraft.client.multiplayer.ClientLevel client;
        final net.minecraft.client.player.LocalPlayer local;final Path out;final CameraType camera;final Entity cameraEntity;
        final float yaw,pitch;final Map<KeyMapping,Boolean> keys=new LinkedHashMap<>();
        final Map<String,Object> report=new LinkedHashMap<>();final List<Map<String,Object>> cases=new ArrayList<>(),shots=new ArrayList<>();
        final List<Map<String,Object>> frameDraws=new ArrayList<>(),frameOrientations=new ArrayList<>(),frameNameTags=new ArrayList<>();
        final Map<BlockPos,BlockState> worldBlocks=new LinkedHashMap<>();
        final long deadline=System.nanoTime()+240_000_000_000L;
        volatile ServerSubLevel platform;volatile Vec3 base;volatile UUID platformId;volatile int index=-1,driveTicks;volatile boolean finishing;
        volatile String serverFailure;volatile String serverSleepResult="";volatile String slot;
        CompletableFuture<?> task,restoration;Snapshot saved;Witness remote;BlockPos worldHead,worldRemoteHead,plotHead,plotRemoteHead;
        int ticks,stable,stageTicks,pendingShot,restoreTicks;boolean configured,remoteReady;String finalStatus,finalDetail;Map<String,Object> current;
        Session(Minecraft mc,String label)throws Exception{
            this.mc=mc;server=mc.getSingleplayerServer();client=mc.level;local=mc.player;camera=mc.options.getCameraType();cameraEntity=mc.getCameraEntity();yaw=local.getYRot();pitch=local.getXRot();
            out=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(out);
            for(KeyMapping key:List.of(mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyShift,mc.options.keyJump,mc.options.keyUse,mc.options.keyAttack)){keys.put(key,key.isDown());key.setDown(false);}
            report.put("label",label);report.put("startedAt",Instant.now().toString());report.put("firstPersonInstalled",ModList.get().isLoaded("firstperson"));
            report.put("productionMixinsDisabled",Boolean.getBoolean("emfattachments.disable"));report.put("resourcePacks",mc.getResourcePackRepository().getSelectedIds());
            report.put("shaderPackActive",ModList.get().isLoaded("iris")&&net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse());
            report.put("cases",cases);report.put("screenshots",shots);
            report.put("fixtureRevision","sleep-setup-current-bed-r3");
            report.put("scope","Actual server-owned Sable bed and native startSleepInBed; bounded physics-pipeline motion. Remote sleeper is client-only, not a multiplayer packet test. Vertex counts and basis errors require independent image review.");
        }
        <T> CompletableFuture<T> server(Function<ServerPlayer,T> action){
            CompletableFuture<T> f=new CompletableFuture<>();server.execute(()->{try{if(server.isPublished())throw new IllegalStateException("Published");ServerPlayer p=server.getPlayerList().getPlayer(OFFLINE);if(p==null)throw new IllegalStateException("Missing offline player");f.complete(action.apply(p));}catch(Throwable t){f.completeExceptionally(t);}});return f;
        }
        void prepare(){task=server(p->{
            saved=new Snapshot(p);p.stopUsingItem();p.stopRiding();p.stopSleepInBed(true,true);ExpandedRagdollRecovery.release(p);ExpandedReactionTransition.begin(p);
            p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=false;p.onUpdateAbilities();
            p.serverLevel().setDayTime(13000);p.serverLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
            p.serverLevel().getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(101,server);
            int x=p.getBlockX()+24,z=p.getBlockZ()+24,y=p.serverLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)+8;
            worldHead=new BlockPos(x,y,z);worldRemoteHead=worldHead.offset(3,0,0);
            for(int dx=-2;dx<=5;dx++)for(int dz=-3;dz<=2;dz++)for(int dy=-1;dy<=3;dy++){
                BlockPos b=worldHead.offset(dx,dy,dz);worldBlocks.put(b,p.level().getBlockState(b));p.level().setBlock(b,dy==-1?Blocks.OAK_PLANKS.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
            }
            placeBed(p.level(),worldHead);placeBed(p.level(),worldRemoteHead);
            var container=SubLevelContainer.getContainer(p.serverLevel());Pose3d pose=new Pose3d();base=new Vec3(x+18.5,y+5,z+.5);pose.position().set(base.x,base.y,base.z);
            platform=(ServerSubLevel)container.allocateNewSubLevel(pose);platformId=platform.getUniqueId();platform.setName("Disposable sleeping-bed fixture");
            var plot=platform.getPlot();ChunkPos center=plot.getCenterChunk();for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)plot.newEmptyChunk(new ChunkPos(center.x+dx,center.z+dz));
            var embedded=plot.getEmbeddedLevelAccessor();for(int dx=-4;dx<=5;dx++)for(int dz=-4;dz<=4;dz++)embedded.setBlock(new BlockPos(dx,0,dz),Blocks.OAK_PLANKS.defaultBlockState(),3);
            placeBed(embedded,new BlockPos(0,1,0));placeBed(embedded,new BlockPos(3,1,0));
            plotHead=plot.getCenterBlock().offset(0,1,0);plotRemoteHead=plot.getCenterBlock().offset(3,1,0);
            platform.logicalPose().position().set(base.x,base.y,base.z);platform.updateLastPose();
            container.physicsSystem().getPipeline().teleport(platform,new Vector3d(base.x,base.y,base.z),new Quaterniond());
            return null;
        });write("PREPARING","");}
        void drive(){
            if(base==null||platform.isRemoved())return;Case c=index<0?CASES.getFirst():CASES.get(index);double t=driveTicks++;
            double a=c.yaw+(c.moving?Math.min(t,140)*.12:0),dy=c.moving?.08*Math.sin(t*.045):0;
            var pipeline=SubLevelContainer.getContainer(platform.getLevel()).physicsSystem().getPipeline();pipeline.resetVelocity(platform);
            pipeline.teleport(platform,new Vector3d(base.x+(c.moving?Math.min(t,140)*.01:0),base.y+dy,base.z),
                new Quaterniond().rotationYXZ(Math.toRadians(a),Math.toRadians(c.pitch),Math.toRadians(c.roll)));
        }
        void begin(int next){index=next;ticks=stable=stageTicks=0;driveTicks=0;configured=false;remoteReady=false;pendingShot=0;
            Case c=CASES.get(index);current=new LinkedHashMap<>();current.put("case",c.name);current.put("item",c.item);current.put("visible",c.visible);current.put("camera",c.camera.name());cases.add(current);
            mc.options.setCameraType(c.camera);mc.setCameraEntity(local);local.setYRot(180);local.setXRot(20);
            if(remote!=null){client.removeEntity(remote.getId(),Entity.RemovalReason.DISCARDED);remote=null;}
            task=server(p->{p.stopSleepInBed(true,true);clear(p);equip(p,c);p.serverLevel().setDayTime(13000);
                placeAtCurrentBed(p,c);
                return null;});write("RUNNING",c.name);
        }
        Map<String,Object> placeAtCurrentBed(ServerPlayer p,Case c){
            BlockPos head=c.deck?plotHead:worldHead;
            Vec3 before=p.position(),bed=Vec3.atCenterOf(head),spot=Vec3.atBottomCenterOf(head).add(0,1,0);
            if(c.deck){bed=platform.logicalPose().transformPosition(bed);spot=platform.logicalPose().transformPosition(spot);}
            ExpandedRagdollRecovery.release(p);p.stopUsingItem();
            p.teleportTo(p.serverLevel(),spot.x,spot.y,spot.z,180,20);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;ExpandedReactionTransition.displaced(p);
            Map<String,Object> setup=new LinkedHashMap<>();setup.put("bedHead",head.toString());setup.put("bedWorldCenter",vec(bed));
            setup.put("positionBeforeReposition",vec(before));setup.put("distanceBeforeReposition",before.distanceTo(bed));
            setup.put("positionImmediatelyBeforeSleep",vec(p.position()));setup.put("distanceImmediatelyBeforeSleep",p.position().distanceTo(bed));
            setup.put("serverTick",p.serverLevel().getGameTime());setup.put("platformDriveTicks",driveTicks);return setup;
        }
        void equip(LivingEntity p,Case c){
            var cap=AccessoriesCapability.get(p);ItemStack stack=item(c.item);
            String selected=AccessoriesAPI.getStackSlotTypes(p,stack).stream().map(s->s.name()).filter(name->!name.equals("head")&&!name.equals("chest")&&!name.equals("legs")&&!name.equals("feet"))
                .filter(name->cap.getContainers().containsKey(name)&&cap.getContainers().get(name).getSize()>0).sorted().findFirst()
                .orElseThrow(()->new IllegalStateException("No accessory slot for "+c.item));
            var target=cap.getContainers().get(selected);target.getAccessories().setItem(0,stack);target.getCosmeticAccessories().setItem(0,ItemStack.EMPTY);target.renderOptions().set(0,c.visible);target.markChanged(false);
            if(p instanceof ServerPlayer sp){slot=selected;sp.inventoryMenu.broadcastChanges();}
        }
        void tick(){
            if(finishing){restoreTick();return;}if(mc.player!=local||mc.level!=client||mc.getSingleplayerServer()!=server||!disposable(mc))throw new IllegalStateException("World changed");
            if(serverFailure!=null)throw new IllegalStateException(serverFailure);if(System.nanoTime()>deadline)throw new IllegalStateException("Fixture deadline");
            if(!task.isDone())return;task.join();ticks++;
            if(index<0){SubLevel deck=SubLevelContainer.getContainer(client).getSubLevel(platformId);if(deck==null||!client.getBlockState(plotHead).is(Blocks.RED_BED)){if(ticks>240)throw new IllegalStateException("Deck readiness timeout");return;}begin(0);return;}
            Case c=CASES.get(index);if(ticks>260)throw new IllegalStateException("Sleep readiness timeout "+c.name+" result="+serverSleepResult+" client="+local.isSleeping());
            if(!configured){if(ticks<25)return;configured=true;task=server(p->{
                // The platform can rotate/move during readiness. Reposition at its CURRENT bed
                // immediately before native reach checks; never reuse the preceding case's pose.
                current.put("sleepSetup",placeAtCurrentBed(p,c));
                var result=p.startSleepInBed(c.deck?plotHead:worldHead);serverSleepResult=result.toString();current.put("nativeSleepResult",serverSleepResult);
                if(result.left().isPresent())throw new IllegalStateException("Native sleep rejected: "+result);return null;});return;}
            if(!local.isSleeping())return;
            var cap=AccessoriesCapability.get(local);var gear=cap.getContainers().get(slot);if(gear==null||gear.getSize()==0||!ItemStack.isSameItemSameComponents(gear.getAccessories().getItem(0),item(c.item))||gear.shouldRender(0)!=c.visible)return;
            if(!remoteReady){
                int id=Integer.MIN_VALUE+8192;while(client.getEntity(id)!=null)id++;remote=new Witness(mc,id);client.addEntity(remote);equip(remote,c);
                BlockPos head=c.deck?plotRemoteHead:worldRemoteHead;remote.startSleeping(head);remote.setOldPosAndRot();
                if(c.deck){SubLevel deck=SubLevelContainer.getContainer(client).getSubLevel(platformId);((EntityMovementExtension)(Object)remote).sable$setTrackingSubLevel(deck);((EntityStickExtension)(Object)remote).sable$setPlotPosition(Vec3.atBottomCenterOf(head).add(0,.6875,0));}
                remoteReady=true;current.put("nativeSleepResult",serverSleepResult);current.put("slot",slot);
            }
            if(c.camera!=CameraType.FIRST_PERSON){local.setYRot(180);local.yRotO=180;local.setXRot(35);local.xRotO=35;}
            stageTicks++;if(stageTicks==35||stageTicks==70)pendingShot++;
            if(stageTicks>=90){if(index==CASES.size()-1)finish("CAPTURED","Native sleep cases captured; inspect basis/vertices/images.");else begin(index+1);}
        }
        void frame()throws Exception{
            if(index<0||pendingShot==0||!local.isSleeping()||!remoteReady)return;pendingShot--;
            String file=String.format(Locale.ROOT,"%02d-%s-%03d.png",shots.size()+1,CASES.get(index).name,stageTicks);
            try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(out.resolve(file));}
            Map<String,Object> row=new LinkedHashMap<>();row.put("file",file);row.put("case",CASES.get(index).name);row.put("stageTick",stageTicks);
            row.put("camera",mc.options.getCameraType().name());row.put("cameraPosition",vec(mc.gameRenderer.getMainCamera().getPosition()));row.put("localPosition",vec(local.position()));
            row.put("clientSleeping",local.isSleeping());row.put("screen",mc.screen==null?"":mc.screen.getClass().getName());row.put("orientation",new ArrayList<>(frameOrientations));row.put("artifacts",new ArrayList<>(frameDraws));row.put("nameTags",new ArrayList<>(frameNameTags));shots.add(row);write("RUNNING",CASES.get(index).name);
        }
        void finish(String status,String detail){
            if(finishing)return;finishing=true;finalStatus=status;finalDetail=detail;keys.keySet().forEach(k->k.setDown(false));
            if(remote!=null)client.removeEntity(remote.getId(),Entity.RemovalReason.DISCARDED);mc.options.setCameraType(camera);mc.setCameraEntity(cameraEntity);local.setYRot(yaw);local.setXRot(pitch);
            restoration=server(p->{p.stopSleepInBed(true,true);ExpandedRagdollRecovery.release(p);if(saved!=null)saved.restore(p,server);worldBlocks.forEach((pos,state)->p.level().setBlock(pos,state,3));
                report.put("inventoryAndAccessoriesRestored",saved==null||saved.same(p));
                if(platform!=null&&!platform.isRemoved())SubLevelContainer.getContainer(p.serverLevel()).removeSubLevel(platform,SubLevelRemovalReason.REMOVED);ExpandedReactionTransition.displaced(p);return null;});write("RESTORING",detail);
        }
        void restoreTick(){if(restoration==null||!restoration.isDone()||++restoreTicks<35)return;try{
            restoration.join();keys.forEach(KeyMapping::setDown);report.put("platformRemoved",platformId==null||SubLevelContainer.getContainer(client).getSubLevel(platformId)==null);
            report.put("remoteRemoved",remote==null||client.getEntity(remote.getId())==null);report.put("sleepEnded",!local.isSleeping());report.put("cameraRestored",mc.options.getCameraType()==camera&&mc.getCameraEntity()==cameraEntity);
            report.put("inputsRestored",keys.entrySet().stream().allMatch(e->e.getKey().isDown()==e.getValue()));report.put("serverRestorationCompleted",true);report.put("finished",true);write(finalStatus,finalDetail);
        }catch(Throwable t){report.put("finished",true);write("FAIL","Restoration "+t);}active=null;}
        void write(String status,String detail){report.put("status",status);report.put("detail",detail);try{Files.writeString(out.resolve("sable-sleep-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception e){throw new IllegalStateException(e);}}
    }
    private static void placeBed(net.minecraft.world.level.LevelAccessor level,BlockPos head){
        BlockState base=Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH);
        // Send block updates without removing the first half before its partner exists.
        level.setBlock(head.relative(Direction.NORTH),base.setValue(BedBlock.PART,BedPart.FOOT),2);level.setBlock(head,base.setValue(BedBlock.PART,BedPart.HEAD),2);
    }
    private static void clear(ServerPlayer p){p.getInventory().clearContent();AccessoriesCapability.get(p).getContainers().values().forEach(c->{c.getAccessories().clearContent();c.getCosmeticAccessories().clearContent();for(int i=0;i<c.renderOptions().size();i++)c.renderOptions().set(i,true);c.markChanged(false);});}
    private record Gear(List<ItemStack> normal,List<ItemStack> cosmetic,List<Boolean> visible){}
    private static final class Snapshot {
        final net.minecraft.nbt.ListTag inventory;final Map<String,Gear> gear=new LinkedHashMap<>();final Vec3 position,motion;final float yaw,pitch,fall;final boolean flying,noGravity,daylight;final int sleepPercentage;final long time;final GameType mode;
        final int selected;final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> respawnDimension;final BlockPos respawnPosition;final float respawnAngle;final boolean respawnForced;
        final List<net.minecraft.world.effect.MobEffectInstance> effects;
        Snapshot(ServerPlayer p){inventory=p.getInventory().save(new net.minecraft.nbt.ListTag());position=p.position();motion=p.getDeltaMovement();yaw=p.getYRot();pitch=p.getXRot();fall=p.fallDistance;flying=p.getAbilities().flying;noGravity=p.isNoGravity();mode=p.gameMode.getGameModeForPlayer();time=p.serverLevel().getDayTime();daylight=p.serverLevel().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);sleepPercentage=p.serverLevel().getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
            selected=p.getInventory().selected;respawnDimension=p.getRespawnDimension();respawnPosition=p.getRespawnPosition();respawnAngle=p.getRespawnAngle();respawnForced=p.isRespawnForced();effects=p.getActiveEffects().stream().map(net.minecraft.world.effect.MobEffectInstance::new).toList();
            AccessoriesCapability.get(p).getContainers().forEach((name,c)->{List<ItemStack> a=new ArrayList<>(),b=new ArrayList<>();for(int i=0;i<c.getSize();i++){a.add(c.getAccessories().getItem(i).copy());b.add(c.getCosmeticAccessories().getItem(i).copy());}gear.put(name,new Gear(a,b,new ArrayList<>(c.renderOptions())));});}
        void restore(ServerPlayer p,net.minecraft.server.MinecraftServer server){clear(p);p.getInventory().load(inventory);p.getInventory().selected=selected;p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(selected));gear.forEach((name,s)->{var c=AccessoriesCapability.get(p).getContainers().get(name);for(int i=0;i<s.normal.size();i++){c.getAccessories().setItem(i,s.normal.get(i).copy());c.getCosmeticAccessories().setItem(i,s.cosmetic.get(i).copy());c.renderOptions().set(i,s.visible.get(i));}c.markChanged(false);});
            p.setRespawnPosition(respawnDimension,respawnPosition,respawnAngle,respawnForced,false);p.removeAllEffects();effects.forEach(e->p.addEffect(new net.minecraft.world.effect.MobEffectInstance(e)));
            p.serverLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,server);p.serverLevel().getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(sleepPercentage,server);p.serverLevel().setDayTime(time);
            p.setGameMode(mode);p.setNoGravity(noGravity);p.getAbilities().flying=flying;p.onUpdateAbilities();p.teleportTo(p.serverLevel(),position.x,position.y,position.z,yaw,pitch);p.setDeltaMovement(motion);p.fallDistance=fall;p.inventoryMenu.broadcastChanges();}
        boolean same(ServerPlayer p){
            if(!inventory.equals(p.getInventory().save(new net.minecraft.nbt.ListTag()))||p.getInventory().selected!=selected)return false;
            for(var entry:gear.entrySet()){var c=AccessoriesCapability.get(p).getContainers().get(entry.getKey());Gear g=entry.getValue();if(c==null||c.getSize()!=g.normal.size()||!g.visible.equals(c.renderOptions()))return false;for(int i=0;i<c.getSize();i++)if(!ItemStack.matches(c.getAccessories().getItem(i),g.normal.get(i))||!ItemStack.matches(c.getCosmeticAccessories().getItem(i),g.cosmetic.get(i)))return false;}
            return Objects.equals(respawnPosition,p.getRespawnPosition())&&respawnDimension.equals(p.getRespawnDimension())&&respawnForced==p.isRespawnForced();
        }
    }
}
