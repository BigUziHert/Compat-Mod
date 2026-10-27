package dev.caleb.emfattachments.test;

import artifacts.client.item.renderer.GloveArtifactRenderer;
import com.google.gson.GsonBuilder;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.menu.ArmorSlotTypes;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Real native empty-hand rendering in a disposable integrated client, without FirstPerson. */
public final class NativeHandAccessorySequence {
    private static Session active;
    private NativeHandAccessorySequence() {}
    public static void register() {
        NeoForge.EVENT_BUS.addListener(NativeHandAccessorySequence::tick);
        NeoForge.EVENT_BUS.addListener(NativeHandAccessorySequence::pre);
        NeoForge.EVENT_BUS.addListener(NativeHandAccessorySequence::frame);
    }
    private static void tick(ClientTickEvent.Post ignored) {
        var mc=Minecraft.getInstance();
        if(active!=null){try{active.tick();}catch(Throwable error){active.finish("FAIL",error.toString());}return;}
        if(mc.player==null||mc.screen!=null||CombatRenderCounters.active||!NativeWorldReadiness.ready())return;
        Path trigger=mc.gameDirectory.toPath().resolve("emfattachments-native-hand-accessories.txt");
        if(!Files.isRegularFile(trigger))return;
        String label="native-hand-accessories";
        try{
            UUID offline=UUID.nameUUIDFromBytes("OfflinePlayer:AttachmentTest".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(!mc.hasSingleplayerServer()||mc.getSingleplayerServer().isPublished()
                    ||!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client"))
                    ||!"AttachmentTest".equals(mc.getUser().getName())||!offline.equals(mc.getUser().getProfileId())
                    ||mc.getUser().getType()!=User.Type.LEGACY||!offline.equals(mc.player.getUUID()))
                throw new IllegalStateException("Exact offline AttachmentTest identity in unpublished disposable .local/client required");
            if(ModList.get().isLoaded("firstperson"))throw new IllegalStateException("This native-hand diagnostic requires FirstPerson physically absent");
            var args=Files.readString(trigger).trim().split("\\s+");label=args[0].replaceAll("[^a-zA-Z0-9._-]","_");
            Set<String> options=new HashSet<>(Arrays.asList(args).subList(1,args.length));
            if(options.stream().anyMatch(option->!Set.of("baseline","quick","aether","thirdperson","weaponstyles","phases","armsoff","offhandoff").contains(option)&&!option.startsWith("glove=")&&!option.startsWith("weapon=")&&!option.startsWith("combo=")))throw new IllegalArgumentException("Unknown native-hand option "+options);
            if(options.stream().filter(option->option.startsWith("combo=")).count()>1)throw new IllegalArgumentException("Only one combo=<n> may be selected");
            if((options.contains("phases")||options.contains("armsoff")||options.contains("offhandoff")||options.stream().anyMatch(option->option.startsWith("combo=")))&&!options.contains("aether"))throw new IllegalArgumentException("phases/combo/visibility controls require the Aether diagnostic");
            if(options.stream().filter(option->option.startsWith("weapon=")).count()>1)throw new IllegalArgumentException("Only one weapon=<id> may be selected");
            if(options.contains("weaponstyles")&&(!options.contains("aether")||options.stream().anyMatch(option->option.startsWith("weapon="))))throw new IllegalArgumentException("weaponstyles requires aether and cannot be combined with weapon=<id>");
            if(options.contains("thirdperson")&&!options.contains("aether"))throw new IllegalArgumentException("thirdperson requires the Aether native body diagnostic");
            Files.delete(trigger);active=new Session(mc,label,options);active.prepare();
        }catch(Throwable error){
            if(active!=null)active.finish("FAIL",error.toString());
            else try{Path out=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(out);Files.writeString(out.resolve("native-hand-accessories-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("status","FAIL","detail",error.toString())));}catch(Exception ignoredError){}
        }
    }
    private static void pre(RenderFrameEvent.Pre ignored){if(active!=null&&!active.finishing){NativeHandAccessoryProbe.beginFrame();NativeAetherHandProbe.beginFrame();CombatRenderCounters.reset();}}
    private static void frame(RenderFrameEvent.Post ignored){if(active!=null&&!active.finishing)try{active.frame();}catch(Throwable error){active.finish("FAIL",error.toString());}}
    private record View(String id,HumanoidArm arm,boolean crouch,boolean swing,boolean shrink,boolean weapon,String heldItem){}
    private record ContainerState(List<ItemStack> items,List<ItemStack> cosmetics,List<Boolean> flags){}

    private static final class Session {
        final Minecraft mc;final LocalPlayer player;final Path out;final boolean baseline,aether,thirdperson,weaponstyles,phases,armsOff,offHandOff;
        final boolean originalShowArms,originalShowOtherHand;
        final int requestedCombo;
        final List<View> views=new ArrayList<>();final List<Map<String,Object>> cases=new ArrayList<>(),captures=new ArrayList<>();
        final List<String> failures=new ArrayList<>(),poseMismatches=new ArrayList<>();final Map<String,Object> report=new LinkedHashMap<>();
        final Map<KeyMapping,Boolean> keys=new LinkedHashMap<>();final Map<String,List<Boolean>> originalClientFlags=new LinkedHashMap<>();
        final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();final List<CompletableFuture<Void>> imageWrites=new ArrayList<>();
        final CameraType originalCamera;final boolean originalGui,originalFlying;final HumanoidArm originalMainOption,originalClientArm;
        final int originalClientSelected;final float yaw,pitch,bodyYaw,headYaw;final Vec3 originalMotion,origin;
        final String originalNea,originalCombat,weaponId;
        volatile Snapshot saved;volatile String slot;volatile Map<String,Object> serverState=Map.of();volatile int restoreAt;
        CompletableFuture<Void> operation=CompletableFuture.completedFuture(null),observation=CompletableFuture.completedFuture(null);
        int index,ticks,stable,samples,firstSwingTime;boolean started,finishing,restorationChecked,viewOptionsApplied,caseCaptured;
        String finalStatus,finalDetail;Map<String,Object> row;float[] firstPose;
        net.bettercombat.logic.WeaponSwing nativeSwing;int[] attackCaptureTicks;
        Session(Minecraft mc,String label,Set<String> options)throws Exception{
            this.mc=mc;player=mc.player;baseline=options.contains("baseline");aether=options.contains("aether");thirdperson=options.contains("thirdperson");weaponstyles=options.contains("weaponstyles");phases=options.contains("phases");armsOff=options.contains("armsoff");offHandOff=options.contains("offhandoff");
            requestedCombo=Integer.parseInt(options.stream().filter(option->option.startsWith("combo=")).map(option->option.substring(6)).findFirst().orElse("0"));
            if(requestedCombo<0)throw new IllegalArgumentException("combo must be nonnegative");
            weaponId=options.stream().filter(option->option.startsWith("weapon=")).map(option->option.substring(7)).findFirst().orElse("minecraft:diamond_sword");
            if(aether&&(item(weaponId).isEmpty()||net.bettercombat.logic.WeaponRegistry.getAttributes(item(weaponId))==null))throw new IllegalArgumentException("Weapon has no native Better Combat attributes: "+weaponId);
            if(aether&&(!ModList.get().isLoaded("aether")||!ModList.get().isLoaded("bettercombat")))throw new IllegalStateException("Aether/Better Combat required");
            if(!player.isAlive()||player.isPassenger()||player.isUsingItem())throw new IllegalStateException("Start alive, unmounted and idle");
            originalCamera=mc.options.getCameraType();originalGui=mc.options.hideGui;originalFlying=player.getAbilities().flying;
            originalMainOption=mc.options.mainHand().get();originalClientArm=player.getMainArm();originalClientSelected=player.getInventory().selected;
            yaw=player.getYRot();pitch=player.getXRot();bodyYaw=player.yBodyRot;headYaw=player.yHeadRot;originalMotion=player.getDeltaMovement();
            origin=new Vec3(480.5,mc.level.getMinBuildHeight()+13,480.5);
            originalNea=new GsonBuilder().create().toJson(dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config);
            originalCombat=new GsonBuilder().create().toJson(net.bettercombat.client.BetterCombatClientMod.config);
            originalShowArms=net.bettercombat.client.BetterCombatClientMod.config.isShowingArmsInFirstPerson;originalShowOtherHand=net.bettercombat.client.BetterCombatClientMod.config.isShowingOtherHandFirstPerson;
            for(var key:List.of(mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyJump,mc.options.keyShift,mc.options.keySprint,mc.options.keyUse,mc.options.keyAttack))keys.put(key,key.isDown());
            AccessoriesCapability.get(player).getContainers().forEach((name,c)->originalClientFlags.put(name,new ArrayList<>(c.renderOptions())));
            var catalog=new ArrayList<String>();
            for(var item:BuiltInRegistries.ITEM){var id=BuiltInRegistries.ITEM.getKey(item);if(id.getNamespace().equals("artifacts")&&GloveArtifactRenderer.getGloveRenderer(item.getDefaultInstance())!=null)catalog.add(id.toString());}
            Collections.sort(catalog);if(!catalog.contains("artifacts:onion_ring"))throw new IllegalStateException("No native Onion Ring glove renderer");
            if(aether){
                catalog.clear();catalog.add(options.stream().filter(option->option.startsWith("glove=")).map(option->option.substring(6)).findFirst().orElse("aether:diamond_gloves"));
                if(item(catalog.getFirst()).isEmpty())throw new IllegalStateException("Glove item unavailable: "+catalog.getFirst());
                if(weaponstyles){
                    List<String> representatives=List.of("simplyswords:diamond_claymore","simplyswords:diamond_katana","simplyswords:diamond_longsword","simplyswords:diamond_halberd","simplyswords:diamond_warglaive","simplyswords:enigma");
                    for(String selected:representatives){
                        if(item(selected).isEmpty()||net.bettercombat.logic.WeaponRegistry.getAttributes(item(selected))==null)throw new IllegalArgumentException("Representative weapon unavailable or has no native attributes: "+selected);
                        for(boolean shrink:new boolean[]{false,true})for(boolean attack:new boolean[]{false,true})views.add(new View(catalog.getFirst(),HumanoidArm.RIGHT,false,attack,shrink,true,selected));
                    }
                    report.put("representativeWeaponStyles",representatives.stream().map(id->weaponStyle(item(id))).toList());
                }else for(HumanoidArm arm:HumanoidArm.values())for(boolean shrink:new boolean[]{false,true})for(int state=0;state<3;state++)views.add(new View(catalog.getFirst(),arm,false,state==2,shrink,state>0,state>0?weaponId:"minecraft:air"));
            } else {
                for(String id:catalog)if(!options.contains("quick")||id.equals("artifacts:onion_ring"))for(HumanoidArm arm:HumanoidArm.values())for(boolean crouch:new boolean[]{false,true})views.add(new View(id,arm,crouch,false,false,false,"minecraft:air"));
                for(HumanoidArm arm:HumanoidArm.values())views.add(new View("artifacts:onion_ring",arm,false,true,false,false,"minecraft:air"));
            }
            out=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(out);
            report.put("label",label);report.put("baseline",baseline);report.put("aetherMode",aether);report.put("firstPersonInstalled",false);report.put("catalog",catalog);
            report.put("weaponStylesMode",weaponstyles);report.put("weapon",weaponstyles?"six-native-representatives":weaponId);if(aether&&!weaponstyles)report.put("weaponStyle",weaponStyle(item(weaponId)));
            report.put("cameraPerspective",thirdperson?"THIRD_PERSON_FRONT":"FIRST_PERSON");
            report.put("visibilityControls",Map.of("armsOff",armsOff,"offHandOff",offHandOff,"originalShowArms",originalShowArms,"originalShowOtherHand",originalShowOtherHand));
            report.put("phasesMode",phases);report.put("requestedCombo",requestedCombo);report.put("plannedContexts",views.size());report.put("plannedCaptures",views.stream().mapToInt(view->aether&&phases&&view.swing?3:2).sum());report.put("cases",cases);report.put("captures",captures);
            report.put("failures",failures);report.put("poseMismatches",poseMismatches);if(aether)report.put("diagnosticFindings",failures);
            report.put("scope","Native empty MAIN_HAND render for both real dominant-hand preferences; actual Artifacts slot0. No fabricated offhand draw. Two native frames per context; Onion swing uses LocalPlayer.swing and never changes model transforms. Numerical pose/geometry assertions do not certify visibility in every screenshot.");
            if(aether)report.put("scope","Native Aether diamond-glove representative of the shared GlovesRenderer, two actual dominant-hand preferences, full/half scale from the real shrinking charm, empty-hand idle, selected-weapon held idle, and native Better Combat attacks with that weapon. Actual skin/glove compile matrices, source parts, vertices and screenshots are recorded; alignment requires independent review. No model/animation transforms are fabricated.");
            if(weaponstyles)report.put("scope","Six installed Simply Swords representatives, actual RIGHT dominant hand, native full/half scale, held-idle and native selected-combo attacks: 24 contexts; capture count is reported in plannedCaptures. Actual runtime weapon attributes, selected native combo, EMF skin/body and Aether glove matrices are recorded. No model/animation transforms or culling flags are fabricated.");
            if(thirdperson)report.put("perspectiveScope","The camera is THIRD_PERSON_FRONT throughout all contexts. Native PlayerRenderer body and Accessories GlovesRenderer calls provide the evidence; EMF child and glove-root compilation is observed after native setup/animation. No pose, scale or culling flags are forced by the probes.");
            write("PREPARING","");
        }
        ServerPlayer sp(){return Objects.requireNonNull(mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID()));}
        void server(Runnable work){var future=new CompletableFuture<Void>();operation=future;mc.getSingleplayerServer().execute(()->{try{work.run();future.complete(null);}catch(Throwable error){future.completeExceptionally(error);}});}
        View view(){return views.get(index);}
        void prepare(){
            if(armsOff)net.bettercombat.client.BetterCombatClientMod.config.isShowingArmsInFirstPerson=false;
            if(offHandOff)net.bettercombat.client.BetterCombatClientMod.config.isShowingOtherHandFirstPerson=false;
            report.put("appliedVisibilitySettings",Map.of("showArms",net.bettercombat.client.BetterCombatClientMod.config.isShowingArmsInFirstPerson,"showOtherHand",net.bettercombat.client.BetterCombatClientMod.config.isShowingOtherHandFirstPerson));
            keys.keySet().forEach(k->k.setDown(false));CombatRenderCounters.active=true;NativeHandAccessoryProbe.active=!aether;NativeAetherHandProbe.active=aether;
            mc.options.hideGui=false;mc.options.setCameraType(thirdperson?CameraType.THIRD_PERSON_FRONT:CameraType.FIRST_PERSON);player.getAbilities().flying=false;
            beginView(false);
            server(()->{
                var p=sp();saved=new Snapshot(p);ExpandedRagdollRecovery.release(p);ExpandedReactionTransition.begin(p);
                p.stopUsingItem();p.stopRiding();p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=false;p.onUpdateAbilities();
                for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=-1;y<=5;y++){var pos=BlockPos.containing(origin).offset(x,y,z);blocks.put(pos,p.level().getBlockState(pos));p.level().setBlock(pos,y==-1?Blocks.BEDROCK.defaultBlockState():Blocks.AIR.defaultBlockState(),3);}
                p.serverLevel().setDayTime(6000);p.teleportTo(origin.x,origin.y,origin.z);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;ExpandedReactionTransition.displaced(p);
                equip(p,view());
            });
        }
        void beginView(boolean equip){
            ticks=stable=samples=0;started=false;caseCaptured=false;viewOptionsApplied=false;firstPose=null;nativeSwing=null;attackCaptureTicks=null;row=new LinkedHashMap<>();cases.add(row);
            row.put("item",view().id);row.put("dominantArm",view().arm.name());row.put("crouch",view().crouch);row.put("nativeSwing",view().swing);row.put("shrunk",view().shrink);row.put("heldItem",view().heldItem);row.put("expectedScale",view().shrink?.5:1.0);row.put("captures",new ArrayList<Map<String,Object>>());
            mc.options.keyShift.setDown(view().crouch);
            if(equip){View next=view();server(()->equip(sp(),next));}
        }
        void equip(ServerPlayer p,View view){
            clear(p);if(aether&&view.weapon)p.getInventory().setItem(0,item(view.heldItem));p.getInventory().selected=0;p.connection.send(new ClientboundSetCarriedItemPacket(0));
            ItemStack stack=item(view.id);var cap=AccessoriesCapability.get(p);
            slot=AccessoriesAPI.getStackSlotTypes(p,stack).stream().filter(s->!ArmorSlotTypes.isArmorType(s.name()))
                    .map(s->s.name()).filter(name->cap.getContainers().containsKey(name)&&cap.getContainers().get(name).getSize()>0)
                    .sorted().findFirst().orElseThrow(()->new IllegalStateException("No native accessory slot for "+view.id));
            var container=cap.getContainers().get(slot);container.getAccessories().setItem(0,stack);container.markChanged(false);
            if(view.shrink){
                ItemStack charm=item("artifacts:charm_of_shrinking");
                var charmSlot=AccessoriesAPI.getStackSlotTypes(p,charm).stream().filter(type->!ArmorSlotTypes.isArmorType(type.name()))
                    .filter(type->cap.getContainer(type)!=null&&cap.getContainer(type).getSize()>0&&cap.getContainer(type).getAccessories().getItem(0).isEmpty())
                    .sorted(Comparator.comparing(type->type.name())).findFirst().orElseThrow(()->new IllegalStateException("No free native shrinking-charm slot"));
                var target=cap.getContainer(charmSlot);target.getAccessories().setItem(0,charm);target.renderOptions().set(0,true);target.markChanged(false);
                row.put("shrinkingCharmSlot",charmSlot.name());row.put("shrinkingCharmDisabled",charm.has(artifacts.registry.ModDataComponents.DISABLED_BY_TOGGLE.get()));
            }
            p.inventoryMenu.broadcastChanges();
        }
        void observe(){
            if(!observation.isDone())return;observation.join();var next=new CompletableFuture<Void>();observation=next;
            mc.getSingleplayerServer().execute(()->{try{var p=sp();serverState=Map.of("mainArm",p.getMainArm().name(),"ground",p.onGround(),"crouch",p.isShiftKeyDown(),"selected",p.getInventory().selected,"tick",mc.getSingleplayerServer().getTickCount(),"scale",p.getScale());next.complete(null);}catch(Throwable error){next.completeExceptionally(error);}});
        }
        boolean gear(){
            if(slot==null||!player.getOffhandItem().isEmpty())return false;
            if(aether&&view().weapon){if(!player.getMainHandItem().is(item(view().heldItem).getItem()))return false;}
            else if(!player.getMainHandItem().isEmpty())return false;
            var c=AccessoriesCapability.get(player).getContainers().get(slot);
            return c!=null&&c.getSize()>0&&ItemStack.isSameItemSameComponents(c.getAccessories().getItem(0),item(view().id))&&c.getCosmeticAccessories().getItem(0).isEmpty()&&c.shouldRender(0);
        }
        void tick(){
            if(finishing){finishTick();return;}
            if(mc.player!=player||mc.screen!=null||!player.isAlive()||player.isPassenger())throw new IllegalStateException("Native hand fixture lost active living player");
            if(mc.options.getCameraType()!=(thirdperson?CameraType.THIRD_PERSON_FRONT:CameraType.FIRST_PERSON))throw new IllegalStateException("Native glove fixture camera perspective changed");
            keys.keySet().forEach(k->k.setDown(false));mc.options.keyShift.setDown(view().crouch);
            player.setYRot(0);player.yRotO=0;player.yBodyRot=player.yBodyRotO=0;player.yHeadRot=player.yHeadRotO=0;player.setXRot(25);player.xRotO=25;
            if(!operation.isDone())return;operation.join();
            if(!viewOptionsApplied){
                // Snapshot the authoritative selection/arm before sending any
                // client setting changes; an old login can have a stale slot.
                mc.options.mainHand().set(view().arm);mc.options.broadcastOptions();
                player.getInventory().selected=0;player.connection.send(new ServerboundSetCarriedItemPacket(0));
                viewOptionsApplied=true;
            }
            ticks++;observe();
            if(ticks>220)throw new IllegalStateException("Native hand equipment/state timeout: "+serverState+" slot="+slot+" clientArm="+player.getMainArm());
            if(!started){
                boolean ready=gear()&&player.getMainArm()==view().arm&&player.onGround()&&player.isCrouching()==view().crouch
                        &&view().arm.name().equals(serverState.get("mainArm"))&&Boolean.TRUE.equals(serverState.get("ground"))
                        &&Boolean.valueOf(view().crouch).equals(serverState.get("crouch"))&&Integer.valueOf(0).equals(serverState.get("selected"))&&NativeWorldReadiness.ready()
                        &&Math.abs(player.getScale()-(view().shrink?.5:1))<.001
                        &&serverState.get("scale") instanceof Number serverScale&&Math.abs(serverScale.doubleValue()-(view().shrink?.5:1))<.001
                        &&(!aether||!ExpandedCombatActions.attackActive(player));
                stable=ready?stable+1:0;
                if(stable>=8&&ticks>=30){started=true;ticks=0;if(view().swing){
                    if(aether){
                        var swing=ExpandedCombatActions.startAttack(mc,requestedCombo);nativeSwing=swing;
                        attackCaptureTicks=phases?new int[]{Math.max(3,swing.upswingTicks()/2),swing.upswingTicks()+2,(int)Math.ceil(swing.duration()*1.3)}:new int[]{2,5};
                        row.put("betterCombatAttack",swing.attackHand().attack().animation());
                        row.put("nativeAttackSelection",Map.of("requestedCombo",requestedCombo,"actualCombo",swing.attackHand().combo().current(),"comboTotal",swing.attackHand().combo().total(),"offHand",swing.attackHand().isOffHand(),"item",BuiltInRegistries.ITEM.getKey(swing.attackHand().itemStack().getItem()).toString()));
                        row.put("nativeSwingTiming",Map.of("startedAt",swing.startedAt(),"upswingTicks",swing.upswingTicks(),"durationTicks",swing.duration(),"captureTicks",attackCaptureTicks));
                    }
                    else player.swing(InteractionHand.MAIN_HAND);
                }}
                return;
            }
            if(!gear())throw new IllegalStateException("Native accessory changed during capture");
            if(caseCaptured && (!view().swing || !ExpandedCombatActions.attackActive(player))){advance();return;}
            if(!aether&&view().swing&&ticks>10&&samples<2)throw new IllegalStateException("Missed native Onion swing samples");
            if(!aether&&!view().swing&&ticks>35&&samples<2)throw new IllegalStateException("No native hand geometry samples");
        }
        void frame()throws Exception{
            if(!started||finishing||caseCaptured)return;
            if(aether){
                if((view().swing&&nativeSwing!=null?player.tickCount-nativeSwing.startedAt():ticks)<expectedCaptureTick())return;
                if(view().swing&&!phases&&!ExpandedCombatActions.attackActive(player)&&ticks<60)return;
            } else if(view().swing){if(!player.swinging||player.swingTime<1||(samples==1&&player.swingTime<=firstSwingTime))return;}
            else if(ticks<(samples==0?5:15))return;
            var probe=aether?NativeAetherHandProbe.snapshot().stream().filter(source->!Boolean.TRUE.equals(source.get("irisShadow"))).toList():NativeHandAccessoryProbe.snapshot();if(probe.isEmpty()&&!aether)return;
            capture(probe);if(++samples<capturesForView()){if(samples==1)firstSwingTime=player.swingTime;return;}
            row.put("sourcePoseVaried",firstPose!=null&&selectedSource(probe)!=null&&!ModelState.same(firstPose,selectedSource(probe)));row.put("status","CAPTURED");
            if(!aether&&view().swing&&ModelState.same(firstPose,selectedSource(probe)))failures.add("Onion "+view().arm+": native swing did not vary actual EMF source pose");
            if(aether){caseCaptured=true;return;}advance();
        }
        int capturesForView(){return aether&&phases&&view().swing?3:2;}
        int expectedCaptureTick(){return aether&&view().swing?attackCaptureTicks[samples]:samples==0?5:15;}
        String capturePhase(){return view().swing?(phases?new String[]{"windup","strike","recovery"}[samples]:"early-attack-"+(samples+1)):"idle-"+(samples+1);}
        void advance(){
            started=false;if(++index<views.size()){beginView(true);write("RUNNING","");}
            else finish(aether?(baseline?"BASELINE_CAPTURED":failures.isEmpty()&&poseMismatches.isEmpty()?"CAPTURED":"CAPTURED_WITH_FINDINGS"):failures.isEmpty()&&(baseline||poseMismatches.isEmpty())?(baseline?"BASELINE_CAPTURED":"CAPTURED"):"FAIL","Native hand capture complete; separate visual review required");
        }
        float[] selectedSource(List<Map<String,Object>> probe){return probe.isEmpty()?null:(float[])probe.getFirst().get("sourcePose");}
        @SuppressWarnings("unchecked") void capture(List<Map<String,Object>> probe)throws Exception{
            var shot=new LinkedHashMap<String,Object>();shot.put("item",view().id);shot.put("dominantArm",view().arm.name());shot.put("crouch",view().crouch);shot.put("swing",view().swing);shot.put("swingTime",player.swingTime);shot.put("swinging",player.swinging);
            shot.put("capturePhase",capturePhase());shot.put("expectedCaptureTick",expectedCaptureTick());shot.put("actualCaptureTick",nativeSwing==null?ticks:player.tickCount-nativeSwing.startedAt());shot.put("attackActiveRequired",view().swing&&!(phases&&samples==2));
            shot.put("cameraPerspective",mc.options.getCameraType().name());if(aether&&view().weapon)shot.put("actualWeaponStyle",weaponStyle(player.getMainHandItem()));
            shot.put("heldItem",BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());shot.put("shrunk",view().shrink);shot.put("sample",samples+1);shot.put("clientTick",player.tickCount);shot.put("server",serverState);shot.put("nativeHandProbe",probe);shot.put("renderCounts",CombatRenderCounters.snapshot());shot.put("actualSkin",player.getSkin().model().name());shot.put("scale",player.getScale());shot.put("betterCombatActive",aether&&ExpandedCombatActions.attackActive(player));
            if(aether){
                var skinProbe=NativeAetherHandProbe.skinSnapshot().stream().filter(source->!Boolean.TRUE.equals(source.get("irisShadow"))).toList();
                shot.put("nativeSkinProbe",skinProbe);
                Map<String,Object> visibility=skinProbe.stream().map(source->(Map<String,Object>)source.get("nativeVisibility"))
                    .filter(flags->flags!=null&&Boolean.TRUE.equals(flags.get("firstPersonPass"))).findFirst().orElseGet(NativeAetherHandProbe::nativeVisibility);
                shot.put("nativeVisibility",visibility);
                boolean visibilityApplies=Boolean.TRUE.equals(visibility.get("firstPersonPass"));
                var hiddenArms=new ArrayList<String>();
                if(visibilityApplies&&!Boolean.TRUE.equals(visibility.get("showLeftArm")))hiddenArms.add("LEFT");
                if(visibilityApplies&&!Boolean.TRUE.equals(visibility.get("showRightArm")))hiddenArms.add("RIGHT");
                shot.put("expectedHiddenArms",hiddenArms);
                if(probe.isEmpty()&&hiddenArms.size()!=2)failures.add(view()+": no native Aether renderer rows (hand or body path)");
                if(hiddenArms.size()==2&&probe.stream().anyMatch(source->((Number)source.get("vertices")).intValue()>0))failures.add(view()+": glove geometry drawn while both native arms are hidden");
                if(view().swing&&!(phases&&samples==2)&&!ExpandedCombatActions.attackActive(player))failures.add(view()+": native Better Combat attack inactive at sample");
                for(var source:probe){
                    if(!view().id.equals(source.get("item")))failures.add(view()+": unexpected rendered glove item");
                    if(((Number)source.get("vertices")).intValue()<=0||((Number)source.get("invalidVertices")).intValue()!=0)failures.add(view()+": missing or invalid glove vertices");
                    if("native-hand-accessory".equals(source.get("renderPath"))&&!Boolean.TRUE.equals(source.get("nativeFirstPersonHandState")))failures.add(view()+": no native EMF hand context for hand render path");
                    if(((List<?>)source.get("gloveDraws")).isEmpty()||((List<?>)source.get("skinDraws")).isEmpty())failures.add(view()+": native skin/glove compile witness missing");
                    if(!Boolean.TRUE.equals(source.get("sourcePoseUnchanged")))poseMismatches.add(view()+": Aether rendering mutated the source arm pose");
                    if("body-accessory".equals(source.get("renderPath"))&&!hiddenArms.isEmpty())for(var draw:(List<Map<String,Object>>)source.get("gloveDraws")){
                        if(((Number)draw.get("cubeCount")).intValue()==0)continue;
                        float[] actual=(float[])draw.get("pose"),left=(float[])source.get("leftSourcePose"),right=(float[])source.get("rightSourcePose");
                        if(hiddenArms.contains("LEFT")&&ModelState.same(actual,left)&&!ModelState.same(actual,right))failures.add(view()+": native hidden LEFT arm produced glove geometry");
                        if(hiddenArms.contains("RIGHT")&&ModelState.same(actual,right)&&!ModelState.same(actual,left))failures.add(view()+": native hidden RIGHT arm produced glove geometry");
                    }
                }
            } else for(var source:probe){
                if(!view().id.equals(source.get("item"))||!view().arm.name().equals(source.get("side")))failures.add("Unexpected item or side "+source.get("item")+" "+source.get("side"));
                for(String property:List.of("sourceAnimatedEmf","nativeFirstPersonHandState","nativeStateLocalPlayer","sourcePoseUnchanged"))if(!Boolean.TRUE.equals(source.get(property)))failures.add(view()+": "+property);
                for(var draw:(List<Map<String,Object>>)source.get("draws")){
                    if(((Number)draw.get("vertices")).intValue()<=0||((Number)draw.get("invalidVertices")).intValue()!=0||((Number)draw.get("actualPartDraws")).intValue()<=0)failures.add(view()+": missing/invalid actual native geometry");
                    if(!Boolean.TRUE.equals(draw.get("nativeResetPoseRestored")))failures.add(view()+": native reset state not restored");
                    if(((Number)draw.get("worstSourcePoseError")).floatValue()>1e-5f)poseMismatches.add(view()+" sample"+(samples+1)+" error="+draw.get("worstSourcePoseError"));
                }
            }
            if(samples==0&&selectedSource(probe)!=null)firstPose=selectedSource(probe).clone();
            String file=String.format(Locale.ROOT,"%03d-%02d-%s-%s-%s.png",captures.size()+1,index+1,view().id.replace(':','_'),view().arm.name().toLowerCase(Locale.ROOT),(view().shrink?"shrunk-":"full-")+(view().swing?"swing":view().weapon?"held-idle":view().crouch?"crouch":"idle"));
            var pixels=Screenshot.takeScreenshot(mc.getMainRenderTarget());imageWrites.add(CompletableFuture.runAsync(()->{try(pixels){pixels.writeToFile(out.resolve(file));}catch(Exception error){throw new RuntimeException(error);}}));
            shot.put("screenshot",file);captures.add(shot);((List<Map<String,Object>>)row.get("captures")).add(shot);
        }
        void finish(String status,String detail){
            if(finishing){
                report.put("restoreFailure",detail);report.put("cleanupVerified",false);report.put("finished",true);
                failures.add("Cleanup failed: "+detail);
                // Keep only the restoration flags actually observed so far.
                // Ending the failed diagnostic does not certify cleanup.
                try{write("FAIL",detail);}finally{active=null;}
                return;
            }
            net.bettercombat.client.BetterCombatClientMod.config.isShowingArmsInFirstPerson=originalShowArms;
            net.bettercombat.client.BetterCombatClientMod.config.isShowingOtherHandFirstPerson=originalShowOtherHand;
            finishing=true;finalStatus=status;finalDetail=detail;ticks=0;NativeHandAccessoryProbe.active=false;NativeAetherHandProbe.active=false;CombatRenderCounters.active=false;
            keys.keySet().forEach(k->k.setDown(false));mc.options.mainHand().set(originalMainOption);mc.options.broadcastOptions();
            write("RESTORING",detail);
            server(()->{var p=sp();ExpandedRagdollRecovery.release(p);p.stopUsingItem();p.stopRiding();if(saved!=null)saved.restore(p);blocks.forEach((pos,state)->p.level().setBlock(pos,state,3));ExpandedReactionTransition.displaced(p);restoreAt=mc.getSingleplayerServer().getTickCount();});
        }
        void finishTick(){
            if(++ticks>300)throw new IllegalStateException("Native hand cleanup timeout");
            if(!operation.isDone())return;operation.join();
            if(mc.getSingleplayerServer().getTickCount()-restoreAt<35)return;
            for(var future:imageWrites){if(!future.isDone())return;future.join();}
            if(!restorationChecked){restorationChecked=true;server(()->{var p=sp();report.put("serverInventoryAndAccessoriesRestored",saved==null||saved.same(p));report.put("serverDominantArmRestored",saved==null||p.getMainArm()==saved.arm);report.put("nativeServerSeatReleased",!p.isPassenger()&&!ExpandedRagdollRecovery.active(p));report.put("nativePendingPoseDrained",!ExpandedRagdollRecovery.pending(p));});return;}
            if(saved!=null){player.getInventory().selected=saved.selected;player.connection.send(new ServerboundSetCarriedItemPacket(saved.selected));}
            player.setMainArm(originalClientArm);player.setYRot(yaw);player.yRotO=yaw;player.setXRot(pitch);player.xRotO=pitch;player.yBodyRot=player.yBodyRotO=bodyYaw;player.yHeadRot=player.yHeadRotO=headYaw;
            player.getAbilities().flying=originalFlying;player.setDeltaMovement(originalMotion);
            originalClientFlags.forEach((name,flags)->{var c=AccessoriesCapability.get(player).getContainers().get(name);if(c!=null)for(int i=0;i<Math.min(flags.size(),c.renderOptions().size());i++)c.renderOptions().set(i,flags.get(i));});
            mc.options.setCameraType(originalCamera);mc.options.hideGui=originalGui;keys.forEach(KeyMapping::setDown);
            report.put("originalClientSelected",originalClientSelected);report.put("originalServerSelected",saved==null?null:saved.selected);
            report.put("selectionReconciledToOriginalServer",saved==null||player.getInventory().selected==saved.selected);
            report.put("clientOriginalSelectionRestored",saved==null||originalClientSelected==saved.selected);
            report.put("clientSettingsRestored",mc.options.mainHand().get()==originalMainOption&&player.getMainArm()==originalClientArm&&mc.options.getCameraType()==originalCamera&&mc.options.hideGui==originalGui&&keys.entrySet().stream().allMatch(e->e.getKey().isDown()==e.getValue()));
            report.put("nativeClientSeatReleased",!player.isPassenger());report.put("arenaRestored",true);
            report.put("nativeVisibilitySettingsRestored",net.bettercombat.client.BetterCombatClientMod.config.isShowingArmsInFirstPerson==originalShowArms&&net.bettercombat.client.BetterCombatClientMod.config.isShowingOtherHandFirstPerson==originalShowOtherHand);
            report.put("nativeConfigurationsPreserved",originalNea.equals(new GsonBuilder().create().toJson(dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config))&&originalCombat.equals(new GsonBuilder().create().toJson(net.bettercombat.client.BetterCombatClientMod.config)));
            report.put("baselineMismatchObserved",!poseMismatches.isEmpty());
            boolean cleanupVerified=true;
            for(String check:List.of("serverInventoryAndAccessoriesRestored","serverDominantArmRestored","nativeServerSeatReleased","nativePendingPoseDrained","selectionReconciledToOriginalServer","clientSettingsRestored","nativeClientSeatReleased","nativeConfigurationsPreserved","nativeVisibilitySettingsRestored"))if(!Boolean.TRUE.equals(report.get(check))){cleanupVerified=false;finalStatus="FAIL";failures.add("Restoration: "+check);}
            if(baseline&&!aether&&poseMismatches.isEmpty()){finalStatus="FAIL";failures.add("Requested baseline did not reproduce pose mismatch");}
            report.put("cleanupVerified",cleanupVerified);report.put("finished",true);
            write(finalStatus,finalDetail);active=null;
        }
        void write(String status,String detail){report.put("status",status);report.put("detail",detail);report.put("completedContexts",index);try{Files.writeString(out.resolve("native-hand-accessories-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception error){throw new IllegalStateException(error);}}
    }
    private static Map<String,Object> weaponStyle(ItemStack stack){
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        var attributes=net.bettercombat.logic.WeaponRegistry.getAttributes(stack);
        if(attributes!=null){row.put("pose",attributes.pose());row.put("offHandPose",attributes.offHandPose());row.put("twoHanded",attributes.isTwoHanded());row.put("category",attributes.category());row.put("attacks",attributes.attacks()==null?List.of():Arrays.stream(attributes.attacks()).map(attack->attack.animation()).toList());}
        return row;
    }
    private static ItemStack item(String id){return BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id)).getDefaultInstance();}
    private static void clear(ServerPlayer p){p.getInventory().clearContent();var cap=AccessoriesCapability.get(p);cap.getContainers().values().forEach(c->{c.getAccessories().clearContent();c.getCosmeticAccessories().clearContent();for(int i=0;i<c.renderOptions().size();i++)c.renderOptions().set(i,true);c.markChanged(false);});}
    private static final class Snapshot {
        final List<ItemStack> inventory;final Map<String,ContainerState> containers=new LinkedHashMap<>();final List<MobEffectInstance> effects;
        final int selected,food;final float health,saturation,yaw,pitch,fall;final HumanoidArm arm;final Vec3 position,motion;final GameType mode;final boolean flying;final long day;
        Snapshot(ServerPlayer p){
            inventory=new ArrayList<>();for(int i=0;i<p.getInventory().getContainerSize();i++)inventory.add(p.getInventory().getItem(i).copy());selected=p.getInventory().selected;
            health=p.getHealth();food=p.getFoodData().getFoodLevel();saturation=p.getFoodData().getSaturationLevel();effects=p.getActiveEffects().stream().map(MobEffectInstance::new).toList();
            arm=p.getMainArm();position=p.position();motion=p.getDeltaMovement();yaw=p.getYRot();pitch=p.getXRot();fall=p.fallDistance;mode=p.gameMode.getGameModeForPlayer();flying=p.getAbilities().flying;day=p.serverLevel().getDayTime();
            AccessoriesCapability.get(p).getContainers().forEach((name,c)->{var normal=new ArrayList<ItemStack>();var cosmetic=new ArrayList<ItemStack>();for(int i=0;i<c.getSize();i++){normal.add(c.getAccessories().getItem(i).copy());cosmetic.add(c.getCosmeticAccessories().getItem(i).copy());}containers.put(name,new ContainerState(normal,cosmetic,new ArrayList<>(c.renderOptions())));});
        }
        void restore(ServerPlayer p){
            clear(p);for(int i=0;i<inventory.size();i++)p.getInventory().setItem(i,inventory.get(i).copy());p.getInventory().selected=selected;p.connection.send(new ClientboundSetCarriedItemPacket(selected));
            containers.forEach((name,s)->{var c=AccessoriesCapability.get(p).getContainers().get(name);for(int i=0;i<s.items.size();i++){c.getAccessories().setItem(i,s.items.get(i).copy());c.getCosmeticAccessories().setItem(i,s.cosmetics.get(i).copy());}for(int i=0;i<s.flags.size();i++)c.renderOptions().set(i,s.flags.get(i));c.markChanged(false);});
            p.removeAllEffects();effects.forEach(e->p.addEffect(new MobEffectInstance(e)));p.setHealth(health);p.getFoodData().setFoodLevel(food);p.getFoodData().setSaturation(saturation);p.setMainArm(arm);p.setGameMode(mode);p.getAbilities().flying=flying;p.onUpdateAbilities();p.serverLevel().setDayTime(day);
            p.teleportTo(p.serverLevel(),position.x,position.y,position.z,yaw,pitch);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=fall;p.inventoryMenu.broadcastChanges();
        }
        boolean same(ServerPlayer p){
            if(p.getInventory().selected!=selected)return false;
            for(int i=0;i<inventory.size();i++)if(!ItemStack.matches(inventory.get(i),p.getInventory().getItem(i)))return false;
            for(var entry:containers.entrySet()){var c=AccessoriesCapability.get(p).getContainers().get(entry.getKey());var s=entry.getValue();if(c==null||c.getSize()!=s.items.size()||!c.renderOptions().equals(s.flags))return false;for(int i=0;i<c.getSize();i++)if(!ItemStack.matches(s.items.get(i),c.getAccessories().getItem(i))||!ItemStack.matches(s.cosmetics.get(i),c.getCosmeticAccessories().getItem(i)))return false;}return true;
        }
    }
}
