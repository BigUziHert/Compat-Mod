package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import io.redspace.irons_artifice.item.GunItem;
import io.redspace.irons_artifice.item.GunplayManager;
import io.redspace.irons_artifice.item.MagazineContents;
import io.redspace.irons_artifice.item.ReloadState;
import io.redspace.irons_artifice.network.packets.ServerboundFireGunPacket;
import io.redspace.irons_artifice.network.packets.ServerboundReloadGunPacket;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.menu.ArmorSlotTypes;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.combat_roll.client.Keybindings;
import net.combat_roll.internals.RollingEntity;
import net.minecraft.client.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Native gun packets and movement in a disposable integrated world; no fabricated animation poses. */
public final class ArtificeLifecycleSequence {
    private static volatile Session active;
    private ArtificeLifecycleSequence() {}
    public static void register() {
        NeoForge.EVENT_BUS.addListener(ArtificeLifecycleSequence::tick);
        NeoForge.EVENT_BUS.addListener(ArtificeLifecycleSequence::frame);
        NeoForge.EVENT_BUS.addListener(ArtificeLifecycleSequence::preFrame);
        NeoForge.EVENT_BUS.addListener(ArtificeLifecycleSequence::entityAdded);
    }
    private static void tick(ClientTickEvent.Post ignored) {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) {
            try { active.tick(); } catch (Throwable error) { active.finish("FAIL", error.toString()); }
            return;
        }
        if (mc.player == null || mc.screen != null || CombatRenderCounters.active || !NativeWorldReadiness.ready()) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-artifice-lifecycle.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            if (!mc.hasSingleplayerServer() || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                    || !mc.player.getGameProfile().getName().equals("AttachmentTest"))
                throw new IllegalStateException("Only the offline AttachmentTest .local/client fixture is supported");
            String[] request = Files.readString(trigger).trim().split("\\s+"); Files.delete(trigger);
            active = new Session(mc, request[0].replaceAll("[^a-zA-Z0-9._-]", "_"), Arrays.asList(request).contains("quick"), Arrays.asList(request).contains("cushion"),Arrays.asList(request).contains("down"),Arrays.asList(request).contains("free"),Arrays.asList(request).contains("freecontrols"));
            active.prepare();
        } catch (Throwable error) {
            if (active != null) active.finish("FAIL", error.toString());
            com.mojang.logging.LogUtils.getLogger().error("Artifice lifecycle startup", error);
        }
    }
    private static void frame(RenderFrameEvent.Post ignored) {
        if (active != null && !active.finishing) try { active.frame(); } catch (Throwable error) { active.finish("FAIL", error.toString()); }
    }
    private static void preFrame(RenderFrameEvent.Pre ignored){if(active!=null&&!active.finishing){ArtificeRenderProbe.beginFrame();ArtificeRagdollRenderProbe.reset();if(active.emfPresent)NativeHandAccessoryProbe.beginFrame();}}
    private static void entityAdded(EntityJoinLevelEvent event) {
        Session s = active;
        if (s != null && !s.finishing && !event.getLevel().isClientSide()
                && event.getEntity() instanceof Projectile projectile && projectile.getOwner() != null
                && projectile.getOwner().getUUID().equals(s.player.getUUID())) s.projectiles.add(projectile.getUUID());
    }
    private record View(String gun, String glove, CameraType camera, HumanoidArm arm, boolean hidden, boolean cosmetic,
                        String focusSlot,String focusItem,String focusCosmetic,boolean focusHidden,String secondGlove,int pitchOverride,boolean secondHidden,boolean secondCosmetic) {
        View(String gun,String glove,CameraType camera,HumanoidArm arm,boolean hidden,boolean cosmetic){this(gun,glove,camera,arm,hidden,cosmetic,"anklet","artifacts:whoopee_cushion","",false);}
        View(String gun,String glove,CameraType camera,HumanoidArm arm,boolean hidden,boolean cosmetic,String focusSlot,String focusItem,String focusCosmetic,boolean focusHidden){this(gun,glove,camera,arm,hidden,cosmetic,focusSlot,focusItem,focusCosmetic,focusHidden,"");}
        View(String gun,String glove,CameraType camera,HumanoidArm arm,boolean hidden,boolean cosmetic,String focusSlot,String focusItem,String focusCosmetic,boolean focusHidden,String secondGlove){this(gun,glove,camera,arm,hidden,cosmetic,focusSlot,focusItem,focusCosmetic,focusHidden,secondGlove,-1);}
        View(String gun,String glove,CameraType camera,HumanoidArm arm,boolean hidden,boolean cosmetic,String focusSlot,String focusItem,String focusCosmetic,boolean focusHidden,String secondGlove,int pitchOverride){this(gun,glove,camera,arm,hidden,cosmetic,focusSlot,focusItem,focusCosmetic,focusHidden,secondGlove,pitchOverride,false,false);}
    }
    private enum Phase { PREPARE, HOLD, WALK, SPRINT, CROUCH, RELOAD_PREPARE, RELOAD, RELOAD_RETURN, SPRINT_RELOAD_PREPARE, SPRINT_RELOAD, ROLL, ROLL_RECOVER, RAGDOLL_HOLD, RAGDOLL_BACK, FIRE, SWITCH, RETURN }
    private record ContainerState(List<ItemStack> items, List<ItemStack> cosmetics, List<Boolean> flags) {}

    private static final class Session {
        final Minecraft mc; final LocalPlayer player; final Path output;
        final List<View> views = new ArrayList<>(); final List<Map<String,Object>> cases = new ArrayList<>(), captures = new ArrayList<>();
        final Map<String,Object> report = new LinkedHashMap<>(); final List<String> failures = new ArrayList<>();
        final Map<KeyMapping,Boolean> keys = new LinkedHashMap<>(); final Map<BlockPos,BlockState> blocks = new LinkedHashMap<>();
        final Set<UUID> projectiles = ConcurrentHashMap.newKeySet(); final List<CompletableFuture<Void>> images = new ArrayList<>();
        final CameraType originalCamera; final HumanoidArm originalHand; final boolean originalGui, emfPresent, cushionMode, downView,freeArmMode;final int originalFov;
        final float yaw, pitch, bodyYaw, headYaw; final Vec3 originalMotion, origin;
        final Map<String,List<Boolean>> clientFlags = new LinkedHashMap<>();
        CompletableFuture<Void> operation = CompletableFuture.completedFuture(null), observation = CompletableFuture.completedFuture(null);
        volatile Snapshot saved; volatile Map<String,Object> serverState = Map.of(); volatile Map<String,String> equipped = Map.of();
        volatile boolean restored, rollRagdollReleased, focusedRagdollStarted; volatile int restoredTick;
        Map<String,Object> row; Phase phase = Phase.PREPARE; int index, ticks, elapsed, stable, bulletsBefore, fireRequestTick;
        boolean finishing, reloadSeen, reloadEarly, reloadLate, rollSeen, rollCaptured, checkStarted, shotSent, sprintReloadSeen, ragdollCaptured, fireAfterCaptured;
        String finalStatus, finalDetail, pendingCapture; Vec3 movementOrigin;

        Session(Minecraft mc, String label, boolean quick,boolean cushionMode,boolean downView,boolean freeArmMode,boolean freeControlsMode) throws Exception {
            freeArmMode|=freeControlsMode;
            this.mc=mc; player=mc.player;emfPresent=ModList.get().isLoaded("entity_model_features");this.cushionMode=cushionMode;this.downView=downView||freeArmMode;this.freeArmMode=freeArmMode;
            if(cushionMode&&(downView||freeArmMode))throw new IllegalArgumentException("Cushion, down and free modes are separate fixtures");
            quick|=downView||freeArmMode;
            if (!player.isAlive() || player.isPassenger() || player.isUsingItem()) throw new IllegalStateException("Start alive, idle and unmounted");
            originalCamera=mc.options.getCameraType(); originalGui=mc.options.hideGui; originalHand=mc.options.mainHand().get();originalFov=mc.options.fov().get();
            yaw=player.getYRot(); pitch=player.getXRot(); bodyYaw=player.yBodyRot; headYaw=player.yHeadRot; originalMotion=player.getDeltaMovement();
            origin=new Vec3(800.5,mc.level.getMinBuildHeight()+13,800.5);
            for (KeyMapping key : List.of(mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,
                    mc.options.keySprint,mc.options.keyShift,mc.options.keyJump,mc.options.keyAttack,mc.options.keyUse,Keybindings.roll)) keys.put(key,key.isDown());
            AccessoriesCapability.get(player).getContainers().forEach((n,c)->clientFlags.put(n,new ArrayList<>(c.renderOptions())));
            List<String> guns=quick?List.of("flintlock","musket"):List.of("flintlock","musket","blunderbuss","blackpowder_revolver","six_shooter","arquebus","clockwork_rifle");
            int gear=0;
            for(String gun:guns) {
                String glove=List.of("artifacts:power_glove","aether:diamond_gloves","artifacts:onion_ring").get(gear++%3);
                for(CameraType camera:List.of(CameraType.FIRST_PERSON,CameraType.THIRD_PERSON_FRONT)) views.add(new View("irons_artifice:"+gun,glove,camera,HumanoidArm.RIGHT,false,false));
            }
            if(!quick) for(CameraType camera:List.of(CameraType.FIRST_PERSON,CameraType.THIRD_PERSON_FRONT)) {
                views.add(new View("irons_artifice:musket","artifacts:power_glove",camera,HumanoidArm.LEFT,false,false));
                views.add(new View("irons_artifice:flintlock","artifacts:power_glove",camera,HumanoidArm.RIGHT,true,false));
                views.add(new View("irons_artifice:flintlock","artifacts:power_glove",camera,HumanoidArm.RIGHT,false,true));
                views.add(new View("irons_artifice:musket","deep_aether:skyjade_gloves",camera,HumanoidArm.RIGHT,false,false));
            }
            if(!quick){
                views.add(new View("irons_artifice:musket","aether:diamond_gloves",CameraType.THIRD_PERSON_BACK,HumanoidArm.RIGHT,false,false));
                views.add(new View("irons_artifice:flintlock","artifacts:power_glove",CameraType.THIRD_PERSON_BACK,HumanoidArm.RIGHT,false,false));
            }
            if(cushionMode){
                views.clear();
                for(String slot:List.of("anklet","face"))views.add(new View("irons_artifice:musket","artifacts:power_glove",CameraType.THIRD_PERSON_FRONT,HumanoidArm.RIGHT,false,false,slot,"artifacts:whoopee_cushion","",false));
                views.add(new View("irons_artifice:musket","artifacts:power_glove",CameraType.THIRD_PERSON_FRONT,HumanoidArm.RIGHT,false,false,"anklet","artifacts:whoopee_cushion","",true));
                views.add(new View("irons_artifice:musket","artifacts:power_glove",CameraType.THIRD_PERSON_FRONT,HumanoidArm.RIGHT,false,false,"anklet","artifacts:whoopee_cushion","artifacts:snorkel",false));
                views.add(new View("irons_artifice:musket","artifacts:power_glove",CameraType.THIRD_PERSON_FRONT,HumanoidArm.RIGHT,false,false,"anklet","minecraft:air","artifacts:whoopee_cushion",false));
                views.add(new View("irons_artifice:musket","artifacts:power_glove",CameraType.THIRD_PERSON_FRONT,HumanoidArm.RIGHT,false,false,"shoes","artifacts:running_shoes","",false));
                for(String glove:List.of("aether:diamond_gloves","deep_aether:skyjade_gloves","artifacts:onion_ring"))
                    views.add(new View("irons_artifice:musket",glove,CameraType.THIRD_PERSON_FRONT,HumanoidArm.RIGHT,false,false,"face","artifacts:whoopee_cushion","",false));
                views.add(new View("irons_artifice:musket","artifacts:digging_claws",CameraType.THIRD_PERSON_FRONT,HumanoidArm.RIGHT,false,false,"face","artifacts:whoopee_cushion","",false,"artifacts:feral_claws"));
                views.add(new View("irons_artifice:musket","artifacts:power_glove",CameraType.THIRD_PERSON_FRONT,HumanoidArm.RIGHT,true,false,"face","artifacts:whoopee_cushion","",false));
                views.add(new View("irons_artifice:musket","artifacts:power_glove",CameraType.THIRD_PERSON_FRONT,HumanoidArm.RIGHT,false,true,"face","artifacts:whoopee_cushion","",false));
            }
            if(downView)views.removeIf(view->view.camera()!=CameraType.FIRST_PERSON);
            if(freeArmMode){
                views.clear();
                for(String gun:List.of("flintlock","blackpowder_revolver","six_shooter"))for(HumanoidArm arm:List.of(HumanoidArm.RIGHT,HumanoidArm.LEFT))
                    views.add(freeView(gun,gun.equals("flintlock")?"artifacts:power_glove":"aether:diamond_gloves",arm,false,false,gun.equals("flintlock")?"artifacts:fire_gauntlet":"",90));
                views.add(freeView("flintlock","aether:diamond_gloves",HumanoidArm.RIGHT,false,false,"",0));
                for(HumanoidArm arm:List.of(HumanoidArm.RIGHT,HumanoidArm.LEFT))views.add(freeView("musket","aether:diamond_gloves",arm,false,false,"",90));
                views.add(freeView("flintlock","artifacts:power_glove",HumanoidArm.LEFT,true,false,"artifacts:fire_gauntlet",90));
                views.add(freeView("flintlock","artifacts:power_glove",HumanoidArm.LEFT,false,true,"artifacts:onion_ring",90));
                views.add(freeView("flintlock","deep_aether:skyjade_gloves",HumanoidArm.RIGHT,false,false,"",90));
                views.add(new View("irons_artifice:flintlock","aether:diamond_gloves",CameraType.THIRD_PERSON_FRONT,HumanoidArm.RIGHT,false,false,"face","artifacts:whoopee_cushion","",false,"",0));
            }
            if(freeControlsMode){
                views.clear();
                views.add(new View("irons_artifice:flintlock","artifacts:power_glove",CameraType.FIRST_PERSON,HumanoidArm.RIGHT,false,false,"face","artifacts:whoopee_cushion","",false,"artifacts:fire_gauntlet",90,true,false));
                views.add(new View("irons_artifice:flintlock","artifacts:power_glove",CameraType.FIRST_PERSON,HumanoidArm.LEFT,false,false,"face","artifacts:whoopee_cushion","",false,"artifacts:onion_ring",90,false,true));
            }
            output=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label); Files.createDirectories(output);
            report.put("label",label); report.put("status","PREPARING"); report.put("plannedContexts",views.size()); report.put("quick",quick);
            report.put("oneHandedSixShooter", ArtificeRenderProbe.ONE_HANDED_SIX_SHOOTER);
            report.put("firstPersonInstalled",ModList.get().isLoaded("firstperson")); report.put("cases",cases); report.put("captures",captures); report.put("failures",failures);
            report.put("cushionMode",cushionMode);
            report.put("downView",this.downView);report.put("freeArmMode",freeArmMode);report.put("freeControlsMode",freeControlsMode);
            report.put("originalFov",originalFov);report.put("captureFov",cushionMode?35:originalFov);
            report.put("scope","Actual native reload and fire packets, authoritative magazine transitions/projectiles, native movement keys and Combat Roll, real first/third-person rendering. Empty magazines are fixture setup before each reload. Screenshots require visual review; gameplay assertions do not certify alignment. Base guns have no aim-down-sights use action, so ordinary aiming uses actual camera yaw/pitch; scope attachments are outside this suite.");
            if(cushionMode)report.put("scope","One actual selected head accessory in anklet/face, hidden, distinct cosmetic, cosmetic-only and normal shoes control. Hand representatives include Power Glove, Aether diamond gloves, Skyjade gloves, Onion Ring, paired Digging/Feral Claws, hidden glove and cosmetic Fire Gauntlet. Native Sable public launch/seat lifecycle settles for three seconds before front/rear captures, then native release restores normal player. Current 0.7.5 helper probes count per-physical-part Accessories/Curios vertices and exact stored stacks. No render poses or visibility fields are fabricated by probes. Actual footage and emitted geometry require review.");
            if(freeArmMode)report.put("freeArmScope","Three pistols in both dominant hands, distinct physical-hand accessories, paired Aether/Skyjade gloves, hidden/cosmetic free-hand controls, level/down views, mirrored rifle controls and third-person control. Native hand identity and occupancy are recorded independently; body arm-root vertices include nested sleeve children. Original images independently establish bare free-arm visibility. Native post-reload idle, roll recovery and switch/return transitions are included.");
            write();
        }
        View freeView(String gun,String glove,HumanoidArm arm,boolean hidden,boolean cosmetic,String second,int pitch){return new View("irons_artifice:"+gun,glove,CameraType.FIRST_PERSON,arm,hidden,cosmetic,"face","artifacts:whoopee_cushion","",false,second,pitch);}
        ServerPlayer sp(){return Objects.requireNonNull(mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID()));}
        View view(){return views.get(index);}
        void server(Runnable work){var future=new CompletableFuture<Void>();operation=future;mc.getSingleplayerServer().execute(()->{try{work.run();future.complete(null);}catch(Throwable e){future.completeExceptionally(e);}});}
        void prepare(){
            keys.keySet().forEach(k->k.setDown(false));CombatRenderCounters.active=true;ArtificeRenderProbe.active=true;ArtificeRagdollRenderProbe.active=true;
            if(emfPresent)NativeHandAccessoryProbe.active=true;mc.options.hideGui=false;
            if(cushionMode)mc.options.fov().set(35);
            server(()->{
                var p=sp();saved=new Snapshot(p);ExpandedRagdollRecovery.release(p);ExpandedReactionTransition.begin(p);
                p.stopUsingItem();p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=false;p.onUpdateAbilities();
                for(int x=-6;x<=6;x++)for(int z=-120;z<=6;z++)for(int y=-1;y<=4;y++){
                    BlockPos pos=BlockPos.containing(origin).offset(x,y,z);blocks.put(pos,p.level().getBlockState(pos));p.level().setBlock(pos,y==-1?Blocks.BEDROCK.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
                }
                p.serverLevel().setDayTime(6000);equip(p,view());
            });
            begin();
        }
        void begin(){
            ticks=elapsed=stable=0;phase=Phase.PREPARE;reloadSeen=reloadEarly=reloadLate=rollSeen=rollCaptured=shotSent=sprintReloadSeen=ragdollCaptured=fireAfterCaptured=focusedRagdollStarted=false;
            mc.options.setCameraType(view().camera);mc.options.mainHand().set(view().arm);mc.options.broadcastOptions();
            player.getInventory().selected=0;player.connection.send(new ServerboundSetCarriedItemPacket(0));
            row=new LinkedHashMap<>();row.put("view",view());row.put("status","PREPARING");cases.add(row);write();
        }
        void equip(ServerPlayer p,View v){
            clear(p);p.getInventory().selected=0;p.connection.send(new ClientboundSetCarriedItemPacket(0));
            p.getInventory().setItem(0,item(v.gun));p.getInventory().setItem(35,new ItemStack(item("irons_artifice:bullet").getItem(),64));
            Map<String,String> slots=new LinkedHashMap<>();
            String gloveSlot=equipAccessory(p,v.glove,slots);
            var glove=AccessoriesCapability.get(p).getContainers().get(gloveSlot);
            glove.renderOptions().set(0,!v.hidden);
            if(v.cosmetic)glove.getCosmeticAccessories().setItem(0,item("artifacts:fire_gauntlet"));
            if(!v.secondGlove.isEmpty()){
                if(glove.getSize()<2)throw new IllegalStateException("Paired claw slot has no second index");
                glove.getAccessories().setItem(1,item(v.secondGlove));
                glove.renderOptions().set(1,!v.secondHidden);
                if(v.secondCosmetic)glove.getCosmeticAccessories().setItem(1,item("artifacts:fire_gauntlet"));
            }
            glove.markChanged(false);
            equipAccessory(p,"artifacts:cross_necklace",slots);
            var focus=AccessoriesCapability.get(p).getContainers().get(v.focusSlot);
            if(focus==null||focus.getSize()==0)throw new IllegalStateException("Requested focus slot unavailable: "+v.focusSlot);
            focus.getAccessories().setItem(0,item(v.focusItem));focus.getCosmeticAccessories().setItem(0,item(v.focusCosmetic.isEmpty()?"minecraft:air":v.focusCosmetic));
            focus.renderOptions().set(0,!v.focusHidden);focus.markChanged(false);slots.put(v.focusSlot,v.focusItem);
            equipped=Map.copyOf(slots);
            p.teleportTo(p.serverLevel(),origin.x,origin.y,origin.z,180,0);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
            ExpandedReactionTransition.displaced(p);p.inventoryMenu.broadcastChanges();
        }
        String equipAccessory(ServerPlayer p,String id,Map<String,String> slots){
            ItemStack stack=item(id);if(stack.isEmpty())throw new IllegalStateException("Missing accessory "+id);
            var cap=AccessoriesCapability.get(p);
            String name=AccessoriesAPI.getStackSlotTypes(p,stack).stream().filter(t->!ArmorSlotTypes.isArmorType(t.name()))
                    .map(t->t.name()).filter(n->cap.getContainers().containsKey(n)&&cap.getContainers().get(n).getSize()>0
                            &&cap.getContainers().get(n).getAccessories().getItem(0).isEmpty()).sorted().findFirst()
                    .orElseThrow(()->new IllegalStateException("No free accessory slot for "+id));
            var c=cap.getContainers().get(name);c.getAccessories().setItem(0,stack);c.markChanged(false);slots.put(name,id);return name;
        }
        boolean gearReady(){
            if(!player.getMainHandItem().is(item(view().gun).getItem())||player.getMainArm()!=view().arm||equipped.isEmpty())return false;
            for(var e:equipped.entrySet()){
                var c=AccessoriesCapability.get(player).getContainers().get(e.getKey());
                if(c==null||!matchesItem(c.getAccessories().getItem(0),e.getValue()))return false;
                if(e.getValue().equals(view().glove)){
                    // Visibility is a client-side Accessories preference in this fixture.
                    c.renderOptions().set(0,!view().hidden);
                    if(view().cosmetic&&!c.getCosmeticAccessories().getItem(0).is(item("artifacts:fire_gauntlet").getItem()))return false;
                    if(!view().secondGlove.isEmpty()&&(c.getSize()<2||!matchesItem(c.getAccessories().getItem(1),view().secondGlove)))return false;
                    if(!view().secondGlove.isEmpty()){
                        c.renderOptions().set(1,!view().secondHidden);
                        if(view().secondCosmetic&&!matchesItem(c.getCosmeticAccessories().getItem(1),"artifacts:fire_gauntlet"))return false;
                    }
                }
                if(e.getKey().equals(view().focusSlot)){
                    c.renderOptions().set(0,!view().focusHidden);
                    if(!matchesItem(c.getCosmeticAccessories().getItem(0),view().focusCosmetic.isEmpty()?"minecraft:air":view().focusCosmetic))return false;
                }
            }
            return true;
        }
        void observe(){
            if(!observation.isDone())return;observation.join();var future=new CompletableFuture<Void>();observation=future;
            mc.getSingleplayerServer().execute(()->{try{
                var p=sp();var stack=p.getMainHandItem();var state=ReloadState.get(stack);
                boolean ragdolled=cushionMode&&ExpandedRagdollRecovery.active(p);
                if(ragdolled)focusedRagdollStarted=true;
                serverState=Map.of("tick",mc.getSingleplayerServer().getTickCount(),"gun",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        "ammo",GunItem.getMagazine(stack).count(),"reloading",state!=null,"reloadPercent",state==null?0:state.percent(0),
                        "sprinting",p.isSprinting(),"ground",p.onGround(),"selected",p.getInventory().selected,"projectiles",projectiles.size(),"ragdolled",ragdolled);
                future.complete(null);
            }catch(Throwable e){future.completeExceptionally(e);}});
        }
        void next(Phase next){phase=next;ticks=0;stable=0;movementOrigin=player.position();write();}
        void requestCapture(String name){if(pendingCapture!=null)throw new IllegalStateException("Uncaptured frame "+pendingCapture);pendingCapture=name;}
        void tick(){
            if(finishing){finishTick();return;}
            if(mc.player!=player||mc.level==null||mc.screen!=null||!player.isAlive()
                    ||player.isPassenger()&&phase!=Phase.ROLL&&phase!=Phase.ROLL_RECOVER&&phase!=Phase.RAGDOLL_HOLD&&phase!=Phase.RAGDOLL_BACK)throw new IllegalStateException("Fixture lost active living player");
            if(operation.isCompletedExceptionally())operation.join();if(!operation.isDone())return;
            ++ticks;++elapsed;observe();if(ticks>240)throw new IllegalStateException("Phase timeout "+phase+": "+serverState);
            keys.keySet().forEach(k->k.setDown(false));
            player.setYRot(180);player.yRotO=180;player.yHeadRot=player.yHeadRotO=180;
            player.setXRot(view().pitchOverride>=0?view().pitchOverride:downView?90:phase==Phase.CROUCH?35:0);player.xRotO=player.getXRot();
            if(phase==Phase.WALK||phase==Phase.SPRINT||phase==Phase.SPRINT_RELOAD||phase==Phase.ROLL)mc.options.keyUp.setDown(true);
            if(phase==Phase.SPRINT||phase==Phase.SPRINT_RELOAD)mc.options.keySprint.setDown(true);
            if(phase==Phase.CROUCH)mc.options.keyShift.setDown(true);
            switch(phase){
                case PREPARE->{
                    boolean ready=gearReady()&&player.onGround()&&view().gun.equals(serverState.get("gun"))&&NativeWorldReadiness.ready();
                    stable=ready?stable+1:0;if(stable>=8&&ticks>=30){row.put("equippedSlots",equipped);next(Phase.HOLD);}
                }
                case HOLD->{if(ticks==15)requestCapture("hold");if(ticks>=25){
                    if(cushionMode){
                        row.put("nativeLaunchAsyncRequested",true);
                        server(()->dev.leo.sableplayerragdoll.api.RagdollAPI.launch(sp(),Vec3.ZERO,dev.leo.sableplayerragdoll.api.RagdollLaunchOptions.builder().autoSeat(true).lockDismount(true).build()));
                        next(Phase.RAGDOLL_HOLD);
                    }else next(Phase.WALK);
                }}
                case WALK->{if(ticks==18)requestCapture("walk");if(ticks>=25){checkMoved("walk");next(Phase.SPRINT);}}
                case SPRINT->{if(ticks==18)requestCapture("sprint");if(ticks>=25){checkMoved("sprint");row.put("sprintObserved",player.isSprinting());if(!player.isSprinting())throw new AssertionError("Native sprint absent");next(Phase.CROUCH);}}
                case CROUCH->{if(ticks==15)requestCapture("crouch_aim_down");if(ticks>=25){row.put("crouchObserved",player.isCrouching());prepareReload(false);}}
                case RELOAD_RETURN->{if(ticks==15)requestCapture("reload_complete_idle");if(ticks>=25)prepareReload(true);}
                case RELOAD_PREPARE,SPRINT_RELOAD_PREPARE->{
                    if(ticks>=12&&GunItem.getMagazine(player.getMainHandItem()).count()==0&&!GunItem.isReloading(player.getMainHandItem())
                            &&Integer.valueOf(0).equals(serverState.get("ammo"))){
                        PacketDistributor.sendToServer(ServerboundReloadGunPacket.INSTANCE);reloadSeen=reloadEarly=reloadLate=false;
                        next(phase==Phase.RELOAD_PREPARE?Phase.RELOAD:Phase.SPRINT_RELOAD);
                    }
                }
                case RELOAD,SPRINT_RELOAD->{
                    ReloadState state=ReloadState.get(player.getMainHandItem());boolean sprint=phase==Phase.SPRINT_RELOAD;
                    if(state!=null){
                        reloadSeen=true;float progress=state.percent(0);
                        if(sprint&&player.isSprinting()&&Boolean.TRUE.equals(serverState.get("sprinting"))&&Boolean.TRUE.equals(serverState.get("reloading")))sprintReloadSeen=true;
                        if(!reloadEarly&&progress>=.18f){requestCapture(sprint?"sprint_reload_early":"reload_early");reloadEarly=true;}
                        else if(!reloadLate&&progress>=.65f){requestCapture(sprint?"sprint_reload_late":"reload_late");reloadLate=true;}
                    }
                    int capacity=((GunItem)player.getMainHandItem().getItem()).magazineCapacity();
                    if(reloadSeen&&state==null&&GunItem.getMagazine(player.getMainHandItem()).count()==capacity
                            &&Integer.valueOf(capacity).equals(serverState.get("ammo"))&&Boolean.FALSE.equals(serverState.get("reloading"))){
                        if(!reloadEarly||!reloadLate)throw new AssertionError("Native reload capture phases missing");
                        row.put(sprint?"sprintReloadVerified":"reloadVerified",true);
                        if(sprint){checkMoved("sprintReload");row.put("sprintingDuringReloadVerified",sprintReloadSeen);if(!sprintReloadSeen)throw new AssertionError("No simultaneous native sprint and reload");next(Phase.ROLL);}else if(freeArmMode)next(Phase.RELOAD_RETURN);else prepareReload(true);
                    }
                }
                case ROLL->{
                    var manager=((RollingEntity)player).getRollManager();
                    if(!rollSeen)Keybindings.roll.setDown(true);
                    if(manager.isRolling()){rollSeen=true;if(!rollCaptured){requestCapture("combat_roll");rollCaptured=true;}}
                    if(player.isPassenger()){
                        row.put("nativeRagdollDuringRoll",true);
                        if(!ragdollCaptured&&pendingCapture==null){requestCapture("roll_native_ragdoll");ragdollCaptured=true;}
                    }
                    if(rollSeen&&!manager.isRolling()&&ticks>=35){
                        row.put("nativeRollVerified",true);
                        // End the observed action before restoring its native physical reaction.
                        // Do not suppress Ragdoll Reactions while the roll is under test.
                        server(()->{var p=sp();rollRagdollReleased=ExpandedRagdollRecovery.release(p);ExpandedReactionTransition.displaced(p);});
                        next(Phase.ROLL_RECOVER);
                    }
                }
                case RAGDOLL_HOLD->{
                    // The native no-pose launch overload ALWAYS returns null: it
                    // requests the client's pose and finishes asynchronously.
                    // Observe the actual server session and client seat instead.
                    stable=player.isPassenger()&&focusedRagdollStarted&&Boolean.TRUE.equals(serverState.get("ragdolled"))?stable+1:0;
                    if(stable>=60){row.put("nativeRagdollSettledTicks",stable);row.put("nativeRagdollStarted",focusedRagdollStarted);requestCapture("ragdoll_settled_front");next(Phase.RAGDOLL_BACK);}
                }
                case RAGDOLL_BACK->{
                    if(!player.isPassenger())throw new IllegalStateException("Native seat ended before rear capture");
                    if(ticks==1)mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    if(ticks==25)requestCapture("ragdoll_settled_back");
                    if(ticks>=30){server(()->{var p=sp();rollRagdollReleased=ExpandedRagdollRecovery.release(p);ExpandedReactionTransition.displaced(p);});next(Phase.ROLL_RECOVER);}
                }
                case ROLL_RECOVER->{stable=!player.isPassenger()&&NativeWorldReadiness.ready()?stable+1:0;if(stable>=8&&ticks>=30){row.put("nativeRollRagdollReleased",rollRagdollReleased);row.put("nativeRollRecoveryVerified",true);if(cushionMode){mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);next(Phase.RETURN);}else next(Phase.FIRE);}}
                case FIRE->{
                    if(!shotSent&&ticks>=15){
                        bulletsBefore=projectiles.size();int ammo=GunItem.getMagazine(player.getMainHandItem()).count();row.put("ammoBeforeFire",ammo);
                        var outcome=GunplayManager.tryFire(player,player.getLookAngle());row.put("nativeClientFireResult",outcome.toString());
                        if(!outcome.fired())throw new AssertionError("Native shot rejected "+outcome);
                        PacketDistributor.sendToServer(new ServerboundFireGunPacket(player.getLookAngle()));shotSent=true;fireRequestTick=ticks;requestCapture("fire");
                    }
                    if(shotSent&&!fireAfterCaptured&&ticks>=fireRequestTick+2
                            &&((Number)serverState.getOrDefault("ammo",row.get("ammoBeforeFire"))).intValue()<((Number)row.get("ammoBeforeFire")).intValue()
                            &&projectiles.size()>bulletsBefore){
                        requestCapture("fire_native_animation");fireAfterCaptured=true;row.put("fireAnimationCaptureDelayTicks",ticks-fireRequestTick);
                    }
                    if(shotSent&&ticks>=30){
                        int before=((Number)row.get("ammoBeforeFire")).intValue();int now=((Number)serverState.getOrDefault("ammo",before)).intValue();
                        if(now<before&&projectiles.size()>bulletsBefore&&fireAfterCaptured){row.put("nativeShotVerified",true);row.put("shotProjectiles",projectiles.size()-bulletsBefore);
                            player.getInventory().selected=1;player.connection.send(new ServerboundSetCarriedItemPacket(1));next(Phase.SWITCH);}
                    }
                }
                case SWITCH->{if(ticks==15)requestCapture("switch_empty_hand");if(ticks>=25&&player.getMainHandItem().isEmpty()&&Integer.valueOf(1).equals(serverState.get("selected"))){
                    row.put("switchAwayVerified",true);player.getInventory().selected=0;player.connection.send(new ServerboundSetCarriedItemPacket(0));next(Phase.RETURN);}}
                case RETURN->{if(ticks==18)requestCapture("return_to_gun");if(ticks>=30&&gearReady()){
                    row.put("status","CAPTURED");row.put("elapsedTicks",elapsed);write();
                    if(++index==views.size())finish("CAPTURED","Native gameplay checks passed; review screenshots for visual alignment.");
                    else{keys.keySet().forEach(k->k.setDown(false));View v=view();server(()->equip(sp(),v));begin();}
                }}
            }
        }
        void checkMoved(String name){double distance=player.position().distanceTo(movementOrigin);row.put(name+"Distance",distance);if(distance<.3)throw new AssertionError("No native movement in "+name);}
        void prepareReload(boolean sprint){
            server(()->{var p=sp();GunItem.setMagazine(p.getMainHandItem(),MagazineContents.EMPTY);p.inventoryMenu.broadcastChanges();});
            next(sprint?Phase.SPRINT_RELOAD_PREPARE:Phase.RELOAD_PREPARE);
        }
        void frame(){
            if(pendingCapture==null)return;String name=pendingCapture;pendingCapture=null;
            Map<String,Object> capture=new LinkedHashMap<>();capture.put("name",name);capture.put("case",index+1);capture.put("view",view());capture.put("phase",phase.name());
            capture.put("server",serverState);capture.put("position",player.position().toString());capture.put("motion",player.getDeltaMovement().toString());
            capture.put("sprinting",player.isSprinting());capture.put("crouching",player.isCrouching());capture.put("rolling",((RollingEntity)player).getRollManager().isRolling());
            capture.put("passenger",player.isPassenger());capture.put("vehicle",player.getVehicle()==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString());
            capture.put("actualCameraType",mc.options.getCameraType().name());
            capture.put("fovSetting",mc.options.fov().get());
            capture.put("cameraPitch",player.getXRot());
            var actualGun=player.getMainHandItem();capture.put("physicalMainArm",player.getMainArm().name());
            capture.put("nativeGunOccupancy",actualGun.getItem() instanceof GunItem?String.valueOf(GunItem.currentOccupancy(player,actualGun)):"NONE");
            capture.put("ammo",GunItem.getMagazine(player.getMainHandItem()).count());capture.put("reloading",GunItem.isReloading(player.getMainHandItem()));
            capture.put("renderProbe",ArtificeRenderProbe.snapshot());if(emfPresent)capture.put("artifactGloveProbe",NativeHandAccessoryProbe.snapshot());
            capture.put("physicalAccessoryProbe",ArtificeRagdollRenderProbe.snapshot());capture.put("actualAccessoryInventory",accessoryInventory(player));
            String file=String.format(Locale.ROOT,"%03d-%02d-%s.png",captures.size()+1,index+1,name);capture.put("screenshot",file);
            var pixels=Screenshot.takeScreenshot(mc.getMainRenderTarget());images.add(CompletableFuture.runAsync(()->{try(pixels){pixels.writeToFile(output.resolve(file));}catch(Exception e){throw new RuntimeException(e);}}));
            captures.add(capture);write();
        }
        void finish(String status,String detail){
            if(finishing){report.put("restorationError",detail);report.put("status","RESTORE_FAILURE");write();active=null;return;}
            finishing=true;finalStatus=status;finalDetail=detail;ticks=0;pendingCapture=null;
            ArtificeRenderProbe.active=false;ArtificeRagdollRenderProbe.active=false;if(emfPresent)NativeHandAccessoryProbe.active=false;
            if(status.equals("FAIL"))failures.add(detail);
            keys.keySet().forEach(k->k.setDown(false));mc.options.mainHand().set(originalHand);mc.options.broadcastOptions();
            report.put("status","RESTORING");write();
            server(()->{var p=sp();ExpandedRagdollRecovery.release(p);p.stopUsingItem();
                if(saved!=null)saved.restore(p);
                for(UUID id:projectiles){var entity=p.serverLevel().getEntity(id);if(entity!=null)entity.discard();}
                blocks.forEach((pos,state)->p.level().setBlock(pos,state,3));ExpandedReactionTransition.displaced(p);restoredTick=mc.getSingleplayerServer().getTickCount();
            });
        }
        void finishTick(){
            if(++ticks>300)throw new IllegalStateException("Cleanup timeout");if(!operation.isDone())return;operation.join();
            if(mc.getSingleplayerServer().getTickCount()-restoredTick<35)return;
            for(var image:images){if(!image.isDone())return;image.join();}
            if(!checkStarted){checkStarted=true;server(()->{restored=saved!=null&&saved.same(sp());});return;}
            player.getInventory().selected=saved==null?0:saved.selected;player.connection.send(new ServerboundSetCarriedItemPacket(player.getInventory().selected));
            player.setYRot(yaw);player.yRotO=yaw;player.setXRot(pitch);player.xRotO=pitch;player.yBodyRot=player.yBodyRotO=bodyYaw;player.yHeadRot=player.yHeadRotO=headYaw;player.setDeltaMovement(originalMotion);
            clientFlags.forEach((n,flags)->{var c=AccessoriesCapability.get(player).getContainers().get(n);if(c!=null)for(int i=0;i<Math.min(flags.size(),c.renderOptions().size());i++)c.renderOptions().set(i,flags.get(i));});
            mc.options.setCameraType(originalCamera);mc.options.hideGui=originalGui;mc.options.fov().set(originalFov);keys.forEach(KeyMapping::setDown);
            report.put("inventoryAccessoriesRestored",restored);report.put("fovRestored",mc.options.fov().get()==originalFov);report.put("clientSettingsRestored",mc.options.mainHand().get()==originalHand&&mc.options.getCameraType()==originalCamera&&mc.options.fov().get()==originalFov&&keys.entrySet().stream().allMatch(e->e.getKey().isDown()==e.getValue()));
            report.put("arenaRestored",true);report.put("projectileEntitiesCleaned",true);report.put("finished",true);report.put("completedContexts",index);
            report.put("status",restored?finalStatus:"FAIL");report.put("detail",finalDetail);CombatRenderCounters.active=false;write();active=null;
        }
        void write(){report.put("activeCase",index+1);report.put("phase",phase.name());try{Files.writeString(output.resolve("artifice-lifecycle-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception e){throw new IllegalStateException(e);}}
    }
    private static ItemStack item(String id){return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();}
    private static boolean matchesItem(ItemStack stack,String id){return id.equals("minecraft:air")?stack.isEmpty():stack.is(item(id).getItem());}
    private static List<Map<String,Object>> accessoryInventory(net.minecraft.world.entity.LivingEntity player){
        var rows=new ArrayList<Map<String,Object>>();var cap=AccessoriesCapability.get(player);if(cap==null)return rows;
        cap.getContainers().forEach((name,c)->{for(int i=0;i<c.getSize();i++){var normal=c.getAccessories().getItem(i);var cosmetic=c.getCosmeticAccessories().getItem(i);if(!normal.isEmpty()||!cosmetic.isEmpty())rows.add(Map.of("slot",name,"index",i,"normal",BuiltInRegistries.ITEM.getKey(normal.getItem()).toString(),"normalCount",normal.getCount(),"cosmetic",BuiltInRegistries.ITEM.getKey(cosmetic.getItem()).toString(),"cosmeticCount",cosmetic.getCount(),"visible",c.shouldRender(i)));}});return rows;
    }
    private static void clear(ServerPlayer p){p.getInventory().clearContent();AccessoriesCapability.get(p).getContainers().values().forEach(c->{c.getAccessories().clearContent();c.getCosmeticAccessories().clearContent();for(int i=0;i<c.renderOptions().size();i++)c.renderOptions().set(i,true);c.markChanged(false);});}
    private static final class Snapshot {
        final List<ItemStack> inventory=new ArrayList<>();final Map<String,ContainerState> containers=new LinkedHashMap<>();
        final int selected;final Vec3 position;final float yaw,pitch;final GameType mode;final boolean flying;final long day;
        Snapshot(ServerPlayer p){
            for(int i=0;i<p.getInventory().getContainerSize();i++)inventory.add(p.getInventory().getItem(i).copy());selected=p.getInventory().selected;
            position=p.position();yaw=p.getYRot();pitch=p.getXRot();mode=p.gameMode.getGameModeForPlayer();flying=p.getAbilities().flying;day=p.serverLevel().getDayTime();
            AccessoriesCapability.get(p).getContainers().forEach((n,c)->{var items=new ArrayList<ItemStack>();var cosmetics=new ArrayList<ItemStack>();for(int i=0;i<c.getSize();i++){items.add(c.getAccessories().getItem(i).copy());cosmetics.add(c.getCosmeticAccessories().getItem(i).copy());}containers.put(n,new ContainerState(items,cosmetics,new ArrayList<>(c.renderOptions())));});
        }
        void restore(ServerPlayer p){
            clear(p);for(int i=0;i<inventory.size();i++)p.getInventory().setItem(i,inventory.get(i).copy());p.getInventory().selected=selected;p.connection.send(new ClientboundSetCarriedItemPacket(selected));
            containers.forEach((n,s)->{var c=AccessoriesCapability.get(p).getContainers().get(n);for(int i=0;i<s.items.size();i++){c.getAccessories().setItem(i,s.items.get(i).copy());c.getCosmeticAccessories().setItem(i,s.cosmetics.get(i).copy());}for(int i=0;i<s.flags.size();i++)c.renderOptions().set(i,s.flags.get(i));c.markChanged(false);});
            p.setGameMode(mode);p.getAbilities().flying=flying;p.onUpdateAbilities();p.serverLevel().setDayTime(day);p.teleportTo(p.serverLevel(),position.x,position.y,position.z,yaw,pitch);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;p.inventoryMenu.broadcastChanges();
        }
        boolean same(ServerPlayer p){
            if(p.getInventory().selected!=selected)return false;for(int i=0;i<inventory.size();i++)if(!ItemStack.matches(inventory.get(i),p.getInventory().getItem(i)))return false;
            for(var e:containers.entrySet()){var c=AccessoriesCapability.get(p).getContainers().get(e.getKey());var s=e.getValue();if(c==null||c.getSize()!=s.items.size()||!c.renderOptions().equals(s.flags))return false;for(int i=0;i<c.getSize();i++)if(!ItemStack.matches(s.items.get(i),c.getAccessories().getItem(i))||!ItemStack.matches(s.cosmetics.get(i),c.getCosmeticAccessories().getItem(i)))return false;}return true;
        }
    }
}
