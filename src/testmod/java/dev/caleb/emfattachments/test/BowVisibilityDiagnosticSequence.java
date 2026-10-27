package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import dev.caleb.emfattachments.test.BowVisibilityControls.Mode;
import dev.caleb.emfattachments.config.CompatConfig;
import dev.caleb.emfattachments.test.mixin.CombatMinecraftAccess;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import net.bettercombat.logic.WeaponSwing;
import net.bettercombat.client.BetterCombatClientMod;
import net.bettercombat.config.TriStateAuto;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import dev.tr7zw.notenoughanimations.NEAnimationsLoader;
import dev.tr7zw.notenoughanimations.versionless.NEABaseMod;
import dev.tr7zw.notenoughanimations.versionless.animations.BowAnimation;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real use/swing A/B diagnostics, gated by an explicit file in a disposable client. */
public final class BowVisibilityDiagnosticSequence {
    private static Session active;
    private static final int[] ATTACK_SAMPLE_AGES={3,5,7,9,11};
    private BowVisibilityDiagnosticSequence() {}
    public static boolean isRunning(){return active!=null;}
    public static void register() {
        BowVisibilityControls.register();
        ArmVisibilityMatrixProbe.register();
        NeoForge.EVENT_BUS.addListener(BowVisibilityDiagnosticSequence::tick);
        NeoForge.EVENT_BUS.addListener(BowVisibilityDiagnosticSequence::serverTick);
        NeoForge.EVENT_BUS.addListener(BowVisibilityDiagnosticSequence::pre);
        NeoForge.EVENT_BUS.addListener(BowVisibilityDiagnosticSequence::frame);
    }
    private static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(active!=null) {try {active.tick();} catch(Throwable e) {active.finish("FAIL",e.toString());}return;}
        if(mc.player==null||mc.screen!=null||CombatRenderCounters.active) return;
        Path trigger=mc.gameDirectory.toPath().resolve("emfattachments-bow-visibility.txt");
        if(!Files.isRegularFile(trigger)) return;
        if(!NativeWorldReadiness.ready()) return;
        String label="bow-visibility";
        try {
            String[] args=Files.readString(trigger).trim().split("\\s+");Files.delete(trigger);
            label=args[0].replaceAll("[^a-zA-Z0-9._-]","_");
            if(!mc.hasSingleplayerServer()||!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client")))
                throw new IllegalStateException("Disposable integrated client required");
            active=new Session(mc,label,Set.of(Arrays.copyOfRange(args,1,args.length)));active.prepare();
        } catch(Throwable error) {
            if(active!=null) active.finish("FAIL",error.toString());
            else try {
                Path output=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(output);
                Files.writeString(output.resolve("bow-visibility-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("status","FAIL","detail",error.toString())));
            } catch(Exception ignored) {}
        }
    }
    private static void serverTick(ServerTickEvent.Post event) {
        Session session=active;if(session==null) return;
        try {session.observeServer();} catch(Throwable error) {session.serverError=error.toString();}
    }
    private static void pre(RenderFrameEvent.Pre event) {
        if(active!=null&&!active.finishing) {CombatRenderCounters.reset();BowVisibilityControls.resetFrame();ArmVisibilityMatrixProbe.resetFrame();}
    }
    private static void frame(RenderFrameEvent.Post event) {
        if(active!=null&&!active.finishing) try {active.frame();} catch(Throwable error) {active.finish("FAIL",error.toString());}
    }
    private record Case(boolean combat,Mode mode,boolean half,float pitch,boolean crouch,Boolean nativeArms,boolean slim,String wearable) {
        Case(boolean combat,Mode mode,boolean half,float pitch,boolean crouch){this(combat,mode,half,pitch,crouch,null,false,"");}
        String name() {return (combat?"glaive":"bow")+"_"+mode.name().toLowerCase(Locale.ROOT)+"_"+(half?"half":"normal")+"_"+(crouch?"crouch_":"stand_")+(int)pitch
                +(nativeArms==null?"":"_arms_"+nativeArms+"_"+(slim?"slim":"default")+"_"+(wearable.isEmpty()?"armor":wearable.replace(':','_')));}
    }
    private record AccessoryState(List<ItemStack> items,List<ItemStack> cosmetics,List<Boolean> renders) {}
    private static final class Session {
        final Minecraft mc;final LocalPlayer player;final Path output;
        final CameraType originalCamera;final boolean originalGui,originalFpm,originalFlying;
        final boolean armVisibilityMatrix;final int originalClientSelected;
        final boolean sword;
        final boolean nativePa,forceNativeArms,originalNativeArms,originalCombatCompatibility;final TriStateAuto originalNativeFirstPersonMode;
        final BowAnimation originalBow;final boolean originalBowCompat,disableBowCompat,originalPoseCache,disablePoseCache,armored;final float yaw,pitch,bodyYaw,headYaw;final Vec3 position,motion;
        final Map<KeyMapping,Boolean> keys=new LinkedHashMap<>();
        final Map<String,Object> report=new LinkedHashMap<>();final List<Map<String,Object>> captures=new ArrayList<>();
        final List<Case> cases=new ArrayList<>();final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
        final Map<String,AccessoryState> accessories=new LinkedHashMap<>();
        final Vec3 origin;
        volatile List<ItemStack> inventory;volatile Vec3 serverPosition;volatile float health,saturation;volatile int food;
        volatile long dayTime,preparedTick,restoreTick;volatile boolean serverFlying;volatile double serverScale;
        volatile int serverUseElapsed;volatile boolean serverUsing,serverMounted;volatile String serverError;
        volatile Map<String,String> serverArmor=Map.of(),serverAccessories=Map.of();
        volatile int serverSelected,originalServerSelected;volatile String serverMainhand="";volatile Vec3 observedServerPosition=Vec3.ZERO;
        CompletableFuture<Void> ready;Case current;int index,ticks,phase,attackAttempts;boolean finishing,prepared;String finalStatus,finalDetail,nativeAttack="";
        WeaponSwing attackSwing;
        final List<Map<String,Object>> attackAttemptsLog=new ArrayList<>();
        final List<Map<String,Object>> missedAttackCaptures=new ArrayList<>();
        final List<CompletableFuture<Void>> screenshotWrites=new ArrayList<>();
        int attackSampleIndex,attemptCaptureStart,imageSerial;boolean attackRetryPending,selectedPacketSent;
        int[] attackSampleAges=ATTACK_SAMPLE_AGES.clone();int lastAcceptedAttackAge=-1;
        boolean restoreSelectionSent;long restoreSelectionPacketTick;
        long lastFrameNanos,lastReportWriteNanos,maxReportWriteNanos,totalReportWriteNanos;
        double currentFrameIntervalMs;int reportWrites,deferredReportWrites;
        Session(Minecraft mc,String label,Set<String> options) throws Exception {
            this.mc=mc;player=mc.player;originalClientSelected=player.getInventory().selected;
            Set<String> knownOptions=Set.of("native-pa-only","native-arms-on","quaternion-eye-only",
                    "eye-anchor-only","head-anchor-only","keyframe-aim-only","native-fp-only",
                    "bow-compat-off","pose-cache-off","armored","normal","half","smoke",
                    "look-up-only","include-look-up","straight-down-only","body-clearance-controls","sword","combat-only","bow-only","baseline-only","arm-visibility-matrix","arm-visibility-quick");
            for(String option:options) if(!knownOptions.contains(option))
                throw new IllegalArgumentException("Unknown diagnostic option: "+option);
            if(player.isPassenger()||player.isUsingItem()||ExpandedCombatActions.attackActive(player)) throw new IllegalStateException("Start recovered, idle and unmounted");
            output=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);Files.createDirectories(output);
            originalCamera=mc.options.getCameraType();originalGui=mc.options.hideGui;originalFpm=FirstPersonAPI.isEnabled();originalFlying=player.getAbilities().flying;
            originalBow=NEABaseMod.config.bowAnimation;
            nativePa=options.contains("native-pa-only");forceNativeArms=options.contains("native-arms-on");
            if(forceNativeArms&&!nativePa)throw new IllegalArgumentException("native-arms-on requires native-pa-only");
            originalNativeArms=BetterCombatClientMod.config.isShowingArmsInFirstPerson;
            originalNativeFirstPersonMode=BetterCombatClientMod.config.firstPersonAnimations;
            originalCombatCompatibility=CompatConfig.COMBAT_COMPATIBILITY.get();
            originalBowCompat=CompatConfig.FIRST_PERSON_BOW_POSE.get();disableBowCompat=options.contains("bow-compat-off");
            originalPoseCache=CompatConfig.FIRST_PERSON_POSES.get();disablePoseCache=options.contains("pose-cache-off");
            armVisibilityMatrix=options.contains("arm-visibility-matrix")||options.contains("arm-visibility-quick");
            sword=options.contains("sword");
            if(armVisibilityMatrix&&options.size()!=1)throw new IllegalArgumentException("arm-visibility-matrix is a standalone bounded BASELINE matrix");
            armored=options.contains("armored")||armVisibilityMatrix;
            yaw=player.getYRot();pitch=player.getXRot();bodyYaw=player.yBodyRot;headYaw=player.yHeadRot;position=player.position();motion=player.getDeltaMovement();
            origin=new Vec3(320.5,mc.level.getMinBuildHeight()+13,320.5);
            for(var key:List.of(mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyJump,mc.options.keySprint,mc.options.keyShift,mc.options.keyAttack,mc.options.keyUse)) keys.put(key,key.isDown());
            for(boolean half:List.of(false,true)) {
                if(options.contains("normal")&&half || options.contains("half")&&!half) continue;
                for(float view:options.contains("straight-down-only")?List.of(90F):options.contains("look-up-only")?List.of(-55F):options.contains("include-look-up")?List.of(-55F,0F,55F):List.of(0F,55F)) for(boolean crouch:List.of(false,true)) {
                    if(options.contains("smoke")&&crouch) continue;
                    if(options.contains("body-clearance-controls")) {for(Mode mode:List.of(Mode.BASELINE,Mode.BODY_BACK_15,Mode.BODY_BACK_30,Mode.BODY_BACK_45)) cases.add(new Case(true,mode,half,view,crouch));continue;}
                    if(nativePa) {cases.add(new Case(true,Mode.NATIVE_PA,half,view,crouch));continue;}
                    if(options.contains("quaternion-eye-only")) {cases.add(new Case(true,Mode.QUATERNION_EYE_ANCHORED,half,view,crouch));continue;}
                    if(options.contains("eye-anchor-only")) {cases.add(new Case(true,Mode.EYE_ANCHORED_KEYFRAMES,half,view,crouch));continue;}
                    if(options.contains("head-anchor-only")) {cases.add(new Case(true,Mode.HEAD_ANCHORED_KEYFRAMES,half,view,crouch));continue;}
                    if(options.contains("keyframe-aim-only")) {cases.add(new Case(true,Mode.KEYFRAME_AIM_SEPARATED,half,view,crouch));continue;}
                    if(options.contains("native-fp-only")) {cases.add(new Case(true,Mode.NATIVE_FIRST_PERSON_COMBAT,half,view,crouch));continue;}
                    if(!options.contains("combat-only")) for(Mode mode:List.of(Mode.BASELINE,Mode.NATIVE_PIVOTS,Mode.NATIVE_BOW,Mode.VANILLA_MODEL,Mode.ZERO_BODY_OFFSET,Mode.NATIVE_BOW_ZERO_OFFSET))
                        cases.add(new Case(false,mode,half,view,crouch));
                    if(!options.contains("bow-only")) for(Mode mode:List.of(Mode.BASELINE,Mode.NATIVE_COMBAT_TRANSFORMS,Mode.VANILLA_MODEL,Mode.VANILLA_MODEL_NATIVE_COMBAT,Mode.ZERO_BODY_OFFSET))
                        cases.add(new Case(true,mode,half,view,crouch));
                }
            }
            if(options.contains("baseline-only")) cases.removeIf(c->c.mode!=Mode.BASELINE);
            if(armVisibilityMatrix) {
                cases.clear();
                if(options.contains("arm-visibility-quick")) {
                    for(boolean arms:List.of(false,true)) {
                        cases.add(new Case(true,Mode.BASELINE,false,0,false,arms,false,"artifacts:feral_claws"));
                        cases.add(new Case(true,Mode.BASELINE,true,55,true,arms,true,"aether:diamond_gloves"));
                        cases.add(new Case(true,Mode.BASELINE,false,0,false,arms,true,"cataclysm:blazing_grips"));
                        cases.add(new Case(true,Mode.BASELINE,true,55,true,arms,false,"deep_aether:skyjade_gloves"));
                    }
                } else for(String wearable:List.of("","artifacts:feral_claws","aether:diamond_gloves","cataclysm:blazing_grips","deep_aether:skyjade_gloves"))
                    for(boolean slim:List.of(false,true))for(boolean arms:List.of(false,true)) {
                        cases.add(new Case(true,Mode.BASELINE,false,0,false,arms,slim,wearable));
                        cases.add(new Case(true,Mode.BASELINE,true,55,true,arms,slim,wearable));
                    }
            }
            report.put("label",label);report.put("options",options);report.put("caseCount",cases.size());report.put("captures",captures);
            report.put("originalFirstPersonBowPose",originalBowCompat);report.put("bowCompatOptOutRequested",disableBowCompat);
            report.put("originalFirstPersonPoseCache",originalPoseCache);report.put("poseCacheOptOutRequested",disablePoseCache);
            report.put("armVisibilityMatrix",armVisibilityMatrix);report.put("originalClientSelected",originalClientSelected);
            report.put("armMatrixScope",armVisibilityMatrix?"40 native BASELINE cases: five real armor/glove profiles, arms false/true, actual default/slim renderers, paired normal-level-standing and half-down-crouched contexts. Default wrist renderer is a separate synthetic hook probe, not an equipped native-item claim.":"not applicable");
            report.put("nativePaControl",nativePa);report.put("nativeArmsOnRequested",forceNativeArms);
            report.put("originalNativeArms",originalNativeArms);report.put("originalNativeFirstPersonMode",originalNativeFirstPersonMode.name());
            report.put("originalCombatCompatibility",originalCombatCompatibility);
            report.put("nativePaLimitation",nativePa?"FPM API and ownership disabled; BC YES temporarily selects the same PA mode that physical noFPM AUTO selects. FPM jar remains loaded. Arms setting retained unless native-arms-on explicitly requested.":"not applicable");
            report.put("quaternionEyeControl",options.contains("quaternion-eye-only"));report.put("eyeAnchorControl",options.contains("eye-anchor-only"));report.put("headAnchorControl",options.contains("head-anchor-only"));report.put("armored",armored);report.put("nativeFirstPersonAimControl",options.contains("native-fp-only"));report.put("keyframeAimSeparatedControl",options.contains("keyframe-aim-only"));
            report.put("originalBowConfig",originalBow.name());report.put("offsetHandlers",FirstPersonAPI.getPlayerOffsetHandlers().stream().map(h->h.getClass().getName()).toList());
            report.put("customBowRotationLock",NEABaseMod.config.customBowRotationLock);report.put("attackAttempts",attackAttemptsLog);
            report.put("nativeAttackSampleAges",ATTACK_SAMPLE_AGES);report.put("missedAttackCaptures",missedAttackCaptures);
            report.put("nativeAttackSampleAgeMeaning",armVisibilityMatrix?"Reference ages only; each actual native swing supplies duration-scaled strictly ascending targets recorded per attempt and capture.":"Fixed ages, unchanged baseline");
            report.put("nativeAttackSamplingReferenceDuration",NativeAttackSampleAges.REFERENCE_DURATION);
            report.put("plannedCaptures",cases.stream().mapToInt(c->c.combat?12:4).sum());
            report.put("controls","All model/pivot/offset/combat-transform overrides apply only to the local FPM body. Native bow configuration is changed per case in memory and restored. F5 and idle frames verify scope and recovery.");
            report.put("status","PREPARING");write();
        }
        ServerPlayer sp() {return Objects.requireNonNull(mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID()));}
        void observeServer() {
            var server=sp();serverScale=server.getScale();serverUseElapsed=server.getTicksUsingItem();serverUsing=server.isUsingItem();serverMounted=server.isPassenger();
            serverArmor=armorIds(server);serverAccessories=accessoryIds(server);serverSelected=server.getInventory().selected;
            serverMainhand=BuiltInRegistries.ITEM.getKey(server.getMainHandItem().getItem()).toString();observedServerPosition=server.position();
        }
        void prepare() {
            current=cases.get(index);phase=0;ticks=0;prepared=false;attackAttempts=0;attackSwing=null;
            if(armVisibilityMatrix)ArmVisibilityMatrixProbe.begin(current.slim);
            selectedPacketSent=false;
            keys.keySet().forEach(k->k.setDown(false));mc.gameMode.releaseUsingItem(player);
            BowVisibilityControls.end();CombatRenderCounters.active=true;
            CompatConfig.COMBAT_COMPATIBILITY.set(nativePa?false:originalCombatCompatibility);
            BetterCombatClientMod.config.firstPersonAnimations=nativePa?TriStateAuto.YES:originalNativeFirstPersonMode;
            BetterCombatClientMod.config.isShowingArmsInFirstPerson=current.nativeArms!=null?current.nativeArms:nativePa&&forceNativeArms?true:originalNativeArms;
            CompatConfig.FIRST_PERSON_BOW_POSE.set(disableBowCompat?false:originalBowCompat);
            CompatConfig.FIRST_PERSON_POSES.set(disablePoseCache?false:originalPoseCache);
            NEABaseMod.config.bowAnimation=current.mode.vanillaBow()?BowAnimation.VANILLA:BowAnimation.CUSTOM_V1;
            NEAnimationsLoader.INSTANCE.animationProvider.refreshEnabledAnimations();
            strm.emfcompat.core.PoseManager.clearPoses(player.getUUID());
            mc.options.setCameraType(CameraType.FIRST_PERSON);mc.options.hideGui=false;FirstPersonAPI.setEnabled(!nativePa);
            report.put("currentCase",current.name());report.put("completedCases",index);report.put("status","PREPARING");write();
            ready=new CompletableFuture<>();mc.getSingleplayerServer().execute(()-> {
                try {
                    var server=sp();
                    if(inventory==null) {
                        originalServerSelected=server.getInventory().selected;
                        inventory=new ArrayList<>();for(int i=0;i<server.getInventory().getContainerSize();i++) inventory.add(server.getInventory().getItem(i).copy());
                        serverPosition=server.position();health=server.getHealth();food=server.getFoodData().getFoodLevel();saturation=server.getFoodData().getSaturationLevel();
                        serverFlying=server.getAbilities().flying;dayTime=server.serverLevel().getDayTime();
                        Objects.requireNonNull(AccessoriesCapability.get(server)).getContainers().forEach((name,container)-> {
                            var items=new ArrayList<ItemStack>();var cosmetics=new ArrayList<ItemStack>();
                            for(int i=0;i<container.getSize();i++) {items.add(container.getAccessories().getItem(i).copy());cosmetics.add(container.getCosmeticAccessories().getItem(i).copy());}
                            accessories.put(name,new AccessoryState(items,cosmetics,new ArrayList<>(container.renderOptions())));
                        });
                        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)for(int y=-1;y<=6;y++) {
                            var pos=BlockPos.containing(origin).offset(x,y,z);blocks.put(pos,server.level().getBlockState(pos));
                            server.level().setBlock(pos,y==-1?Blocks.BEDROCK.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
                        }
                    }
                    ExpandedRagdollRecovery.release(server);ExpandedReactionTransition.begin(server);server.stopUsingItem();
                    clearAccessories(server);if(current.half) equipCharm(server);
                    server.getInventory().selected=0;
                    if(!current.wearable.isEmpty())equipWearable(server,current.wearable);
                    for(int i=0;i<server.getInventory().getContainerSize();i++) server.getInventory().setItem(i,ItemStack.EMPTY);
                    if(armored) {
                        server.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));
                        server.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));
                        server.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));
                        server.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));
                    }
                    server.getInventory().setItem(35,new ItemStack(Items.ARROW,64));
                    server.setItemInHand(InteractionHand.MAIN_HAND,expected());server.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
                    server.setHealth(server.getMaxHealth());server.getFoodData().setFoodLevel(20);server.getAbilities().flying=false;server.onUpdateAbilities();
                    server.teleportTo(origin.x,origin.y,origin.z);server.setDeltaMovement(Vec3.ZERO);server.fallDistance=0;
                    ExpandedReactionTransition.displaced(server);server.setShiftKeyDown(current.crouch);server.setYRot(0);server.setXRot(current.pitch);
                    server.serverLevel().setDayTime(6000);server.inventoryMenu.broadcastChanges();preparedTick=mc.getSingleplayerServer().getTickCount();ready.complete(null);
                }catch(Throwable error){ready.completeExceptionally(error);}
            });
        }
        ItemStack expected() {return current.combat?(sword?Items.DIAMOND_SWORD.getDefaultInstance():BuiltInRegistries.ITEM.get(ResourceLocation.parse("simplyswords:diamond_glaive")).getDefaultInstance()):Items.BOW.getDefaultInstance();}
        void clearAccessories(ServerPlayer server) {
            Objects.requireNonNull(AccessoriesCapability.get(server)).getContainers().values().forEach(container->{
                for(int i=0;i<container.getSize();i++) {container.getAccessories().setItem(i,ItemStack.EMPTY);container.getCosmeticAccessories().setItem(i,ItemStack.EMPTY);}
            });
        }
        void equipCharm(ServerPlayer server) {
            var cap=Objects.requireNonNull(AccessoriesCapability.get(server));
            var charm=BuiltInRegistries.ITEM.get(ResourceLocation.parse("artifacts:charm_of_shrinking")).getDefaultInstance();
            var slot=AccessoriesAPI.getStackSlotTypes(server,charm).stream()
                    .filter(s->!io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(s.name()))
                    .filter(s->cap.getContainer(s)!=null&&cap.getContainer(s).getSize()>0)
                    .sorted(Comparator.comparing(s->s.name())).findFirst().orElseThrow();
            cap.getContainer(slot).getAccessories().setItem(0,charm);cap.getContainer(slot).renderOptions().set(0,true);
        }
        static String wearableSlot(String id){return id.startsWith("aether:")||id.startsWith("deep_aether:")?"aether:gloves_slot":"hand";}
        void equipWearable(ServerPlayer server,String id) {
            var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDefaultInstance();String slot=wearableSlot(id);
            if(item.isEmpty()||AccessoriesAPI.getStackSlotTypes(server,item).stream().noneMatch(t->t.name().equals(slot)))
                throw new IllegalStateException("Catalogued native wearable/slot no longer valid: "+id+"/"+slot);
            var container=Objects.requireNonNull(AccessoriesCapability.get(server)).getContainers().get(slot);
            if(container==null||container.getSize()==0)throw new IllegalStateException("Native slot unavailable: "+slot);
            container.getAccessories().setItem(0,item);container.renderOptions().set(0,true);
        }
        static Map<String,String> accessoryIds(LivingEntity entity) {
            var map=new LinkedHashMap<String,String>();var cap=AccessoriesCapability.get(entity);if(cap==null)return map;
            cap.getContainers().forEach((slot,c)->{for(int i=0;i<c.getSize();i++){var stack=c.getAccessories().getItem(i);
                if(!stack.isEmpty())map.put(slot+"/"+i,BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());}});return map;
        }
        boolean wearableReady(){return current.wearable.isEmpty()||(current.wearable.equals(accessoryIds(player).get(wearableSlot(current.wearable)+"/0"))
                &&current.wearable.equals(serverAccessories.get(wearableSlot(current.wearable)+"/0")));}
        Map<String,Object> preparationEvidence() {
            var e=new LinkedHashMap<String,Object>();e.put("phase",phase);e.put("ticks",ticks);e.put("futureDone",ready.isDone());e.put("futureExceptional",ready.isCompletedExceptionally());
            e.put("clientMainhand",BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());e.put("serverMainhand",serverMainhand);
            e.put("expectedMainhand",BuiltInRegistries.ITEM.getKey(expected().getItem()).toString());e.put("clientSelected",player.getInventory().selected);e.put("serverSelected",serverSelected);
            e.put("clientPosition",player.position().toString());e.put("serverPosition",observedServerPosition.toString());e.put("expectedPosition",origin.toString());
            e.put("attackCooldown",player.getAttackStrengthScale(0));e.put("attackActive",ExpandedCombatActions.attackActive(player));e.put("using",player.isUsingItem());
            e.put("clientMounted",player.isPassenger());e.put("serverMounted",serverMounted);e.put("clientScale",player.getScale());e.put("serverScale",serverScale);
            e.put("nativeReady",!ready.isCompletedExceptionally()&&ExpandedCombatActions.ready(player,expected(),ready,ticks));e.put("worldReady",NativeWorldReadiness.ready());
            e.put("nativeWorldReadiness",NativeWorldReadiness.evidence());e.put("clientAccessories",accessoryIds(player));e.put("serverAccessories",serverAccessories);e.put("wearableReady",wearableReady());return e;
        }
        void tick() {
            if(mc.player!=player||mc.level==null) throw new IllegalStateException("World/player changed");
            ticks++;
            if(finishing) {restoreClient();return;}
            if(mc.screen!=null) throw new IllegalStateException("Unexpected UI: "+mc.screen.getClass().getName());
            if(serverError!=null) throw new IllegalStateException(serverError);
            if(ready.isCompletedExceptionally()) ready.join();
            if(phase==0&&ready.isDone()&&!selectedPacketSent){player.getInventory().selected=0;
                player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(0));selectedPacketSent=true;}
            mc.options.hideGui=false;mc.options.keyShift.setDown(current.crouch);
            player.setYRot(0);player.yRotO=0;player.yBodyRot=0;player.yBodyRotO=0;player.yHeadRot=0;player.yHeadRotO=0;
            player.setXRot(current.pitch);player.xRotO=current.pitch;
            if(phase==0&&ticks%20==0){report.put("lastPreparation",preparationEvidence());write();}
            if(phase==0 && ready.isDone()&&ticks>=40&&mc.getSingleplayerServer().getTickCount()-preparedTick>30
                    && player.position().distanceToSqr(origin)<.1 && !player.isPassenger() && !serverMounted
                    && Math.abs(player.getScale()-(current.half?.5:1))<.001&&Math.abs(serverScale-(current.half?.5:1))<.001
                    && selectedPacketSent && player.getInventory().selected==0 && serverSelected==0 && wearableReady()
                    && ExpandedCombatActions.ready(player,expected(),ready,ticks) && NativeWorldReadiness.ready()) {
                report.put("nativeWorldReadiness",NativeWorldReadiness.evidence());
                prepared=true;
            }
            if(ticks>260) throw new IllegalStateException("Timed out in "+current.name()+" phase="+phase+" clientScale="+player.getScale()+" serverScale="+serverScale);
        }
        void startAction() {
            if(current.combat) {
                var before=((CombatMinecraftAccess)mc).emfattachments$ongoingSwing();
                attackSwing=ExpandedCombatActions.startAttack(mc,0);
                nativeAttack=attackSwing.attackHand().attack().animation();attackAttempts++;
                try {attackSampleAges=armVisibilityMatrix?NativeAttackSampleAges.matrix(attackSwing.duration()):ATTACK_SAMPLE_AGES.clone();}
                catch(IllegalArgumentException tooShort){report.put("samplingFailureClassification",tooShort.getMessage().startsWith("TOO_SHORT_NATIVE_WINDOW")?"TOO_SHORT_NATIVE_WINDOW":"INVALID_NATIVE_DURATION");
                    report.put("samplingFailureDuration",attackSwing.duration());report.put("samplingFailureCase",current.name());throw tooShort;}
                lastAcceptedAttackAge=-1;
                var attempt=new LinkedHashMap<String,Object>();attempt.put("case",current.name());attempt.put("camera",mc.options.getCameraType().name());
                attempt.put("attempt",attackAttempts);attempt.put("startedAt",attackSwing.startedAt());attempt.put("clientTick",player.tickCount);
                attempt.put("duration",attackSwing.duration());attempt.put("upswingTicks",attackSwing.upswingTicks());
                attempt.put("nativeSampleTargets",Arrays.stream(attackSampleAges).boxed().toList());
                attempt.put("samplingPolicy",armVisibilityMatrix?"native-duration-scaled-reference-fractions-strict-five-positive-ages":"fixed-baseline-ages");
                attempt.put("freshSwing",attackSwing!=before);attempt.put("activeImmediately",ExpandedCombatActions.attackActive(player));attackAttemptsLog.add(attempt);
                if(attackSwing==before||attackSwing.startedAt()!=player.tickCount||!ExpandedCombatActions.attackActive(player))
                    throw new AssertionError("Native attack did not start a fresh active animation: "+attempt);
                attackSampleIndex=0;attemptCaptureStart=captures.size();attackRetryPending=false;
            }
            else {
                if(!mc.gameMode.useItem(player,InteractionHand.MAIN_HAND).consumesAction()) throw new AssertionError("Native bow use rejected");
                mc.options.keyUse.setDown(true);
            }
        }
        void next(int value) {phase=value;ticks=0;report.put("phase",phase);report.put("status","RUNNING");write();}
        void frame() throws Exception {
            long frameNow=System.nanoTime();currentFrameIntervalMs=lastFrameNanos==0?0:(frameNow-lastFrameNanos)/1_000_000.0;lastFrameNanos=frameNow;
            // Start immediately after a rendered frame, not in a catch-up tick batch.
            // The next sample is measured against BC's real player-tick timestamp.
            if(phase==0&&prepared) {BowVisibilityControls.begin(current.mode);startAction();next(1);return;}
            if(phase==1 && (current.combat?attackCaptureReady():player.isUsingItem()&&player.getTicksUsingItem()>=60&&serverUseElapsed>=55)) {
                capture(current.combat?"active_firstperson_age"+attackSampleAges[attackSampleIndex]:"active_firstperson",true);
                if(current.combat) {if(++attackSampleIndex==attackSampleAges.length) {completeAttackSamples();next(2);}}
                else {mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);next(3);}
            } else if(phase==2 && ticks>=55&&!ExpandedCombatActions.attackActive(player)) {
                capture("recovered_firstperson",false);mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);attackAttempts=0;startAction();next(3);
            } else if(phase==3 && (current.combat?attackCaptureReady():ticks>=12)) {
                capture(current.combat?"active_f5_front_age"+attackSampleAges[attackSampleIndex]:"active_f5_front",true);
                if(current.combat) {if(++attackSampleIndex==attackSampleAges.length) {completeAttackSamples();next(4);}}
                else {mc.options.setCameraType(CameraType.FIRST_PERSON);next(5);}
            } else if(phase==4 && ticks>=55&&!ExpandedCombatActions.attackActive(player)) {
                BowVisibilityControls.end();mc.options.setCameraType(CameraType.FIRST_PERSON);next(6);
            } else if(phase==5 && ticks>=12) {
                capture("active_return_firstperson",true);mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(player);BowVisibilityControls.end();next(6);
            } else if(phase==6 && ticks>=35&&!player.isUsingItem()&&!ExpandedCombatActions.attackActive(player)) {
                capture("idle_return_firstperson",false);BowVisibilityControls.end();index++;
                if(index==cases.size()) finish("CAPTURED","Native actions and all scoped A/B controls captured; visual comparison remains required.");else prepare();
            }
        }
        boolean attackCaptureReady() {
            int age=player.tickCount-attackSwing.startedAt();
            int target=attackSampleAges[attackSampleIndex];
            if(age<target || armVisibilityMatrix&&age<=lastAcceptedAttackAge) return false;
            if(!attackRetryPending&&age<=target+1&&(!armVisibilityMatrix||age<attackSwing.duration())&&ExpandedCombatActions.attackActive(player)) return true;
            // Preserve partial evidence if a render gap skips a requested pose. A
            // complete accepted sequence contains every target in one native swing.
            attackRetryPending=true;
            var attempt=attackAttemptsLog.getLast();attempt.putIfAbsent("missedActiveFrameAtNativeAge",age);
            attempt.putIfAbsent("missedRequestedAge",target);
            if(age<55||!ExpandedCombatActions.ready(player,expected(),ready,ticks)) return false;
            if(attackAttempts>=3) throw new AssertionError("No rendered active native attack after three attempts: "+attempt);
            missedAttackCaptures.addAll(captures.subList(attemptCaptureStart,captures.size()));
            captures.subList(attemptCaptureStart,captures.size()).clear();
            startAction();ticks=0;write();return false;
        }
        void completeAttackSamples() {
            if(armVisibilityMatrix){int previous=-1;var accepted=captures.subList(attemptCaptureStart,captures.size());
                if(accepted.size()!=5)throw new AssertionError("Expected five actual native samples in one swing");
                for(var sample:accepted){int age=((Number)sample.get("nativeAttackAge")).intValue();
                    if(age<=previous||age>=attackSwing.duration())throw new AssertionError("Native sample ages are not strictly ascending within actual duration: "+accepted);previous=age;}}
            attackAttemptsLog.getLast().put("allRequestedAgesCapturedInOneSwing",true);
            for(var row:captures.subList(attemptCaptureStart,captures.size())) row.put("nativeSequenceComplete",true);
        }
        void capture(String suffix,boolean action) throws Exception {
            long captureStarted=System.nanoTime();
            if(armVisibilityMatrix&&!wearableReady())throw new AssertionError("Native glove state changed: "+preparationEvidence());
            if(armored&&!expectedArmor().equals(armorIds(player))) throw new AssertionError("Native client armor did not synchronize: "+armorIds(player));
            if(armored&&!expectedArmor().equals(serverArmor)) throw new AssertionError("Native server armor incorrect: "+serverArmor);
            if(current.combat&&ExpandedCombatActions.attackActive(player)!=action) throw new AssertionError("Native attack state incorrect at "+suffix);
            if(!current.combat&&player.isUsingItem()!=action) throw new AssertionError("Native bow use state incorrect at "+suffix);
            if(!current.combat&&action&&(!serverUsing||serverUseElapsed<50)) throw new AssertionError("Native server bow charge absent");
            boolean fp=mc.options.getCameraType().isFirstPerson();
            var controls=BowVisibilityControls.snapshot();var counters=CombatRenderCounters.snapshot();
            if(fp&&!nativePa&&(CombatRenderCounters.bodyPasses!=1||CombatRenderCounters.bodyItems!=1||CombatRenderCounters.vanillaItems!=0||CombatRenderCounters.otherItems!=0))
                throw new AssertionError("Expected one actual body/item pass: "+counters);
            if(fp&&nativePa) {
                if(FirstPersonAPI.isEnabled()||CompatConfig.combatCompatibility())throw new AssertionError("Native PA still has FPM ownership");
                if(CombatRenderCounters.bodyPasses!=0||CombatRenderCounters.bodyItems!=0
                        ||(action?(CombatRenderCounters.otherItems!=1||CombatRenderCounters.vanillaItems!=0):(CombatRenderCounters.otherItems!=0||CombatRenderCounters.vanillaItems!=1)))
                    throw new AssertionError("Expected native PA active item or recovered vanilla item: "+counters);
                if(action&&((IAnimatedPlayer)player).playerAnimator_getAnimation().getFirstPersonMode()!=FirstPersonMode.THIRD_PERSON_MODEL)
                    throw new AssertionError("Native PA first-person mode not selected");
            }
            if(!fp&&(CombatRenderCounters.bodyPasses!=0||CombatRenderCounters.bodyItems!=0||CombatRenderCounters.vanillaItems!=0||CombatRenderCounters.otherItems!=1))
                throw new AssertionError("Expected one ordinary F5 item pass: "+counters);
            if(!fp) for(String key:List.of("pivotApplications","nativeArmApplications","vanillaModelDecisions","offsetApplications","nativeCombatDecisions"))
                if(((Number)controls.get(key)).intValue()!=0) throw new AssertionError("Test override leaked into F5: "+controls);
            if(fp&&action) {
                if(current.mode==Mode.NATIVE_PIVOTS&&((Number)controls.get("pivotApplications")).intValue()==0) throw new AssertionError("Pivot control not applied");
                if(current.mode.vanillaBow()&&((Number)controls.get("nativeArmApplications")).intValue()==0) throw new AssertionError("Native BOW control not applied");
                if(current.mode.vanillaModel()&&((Number)controls.get("vanillaModelDecisions")).intValue()==0) throw new AssertionError("Official vanilla-model control not selected");
                if(current.mode.zeroOffset()&&((Number)controls.get("offsetApplications")).intValue()==0) throw new AssertionError("Offset control not applied");
                if(current.combat&&current.mode.nativeCombat()&&((Number)controls.get("nativeCombatDecisions")).intValue()==0) throw new AssertionError("Native combat-transform control not applied");
            }
            var nativeAim=NativeFirstPersonCombatControl.snapshot();
            if(!Boolean.TRUE.equals(nativeAim.get("threadLocalRestored"))||Boolean.TRUE.equals(nativeAim.get("firstPersonPassAtCapture")))
                throw new AssertionError("Native selector pass flag leaked outside pure source call: "+nativeAim);
            if(!fp&&((Number)nativeAim.get("calls")).intValue()!=0)throw new AssertionError("Native FP selector changed F5: "+nativeAim);
            if(fp&&action&&(current.mode==Mode.NATIVE_FIRST_PERSON_COMBAT||current.mode==Mode.KEYFRAME_AIM_SEPARATED||current.mode==Mode.HEAD_ANCHORED_KEYFRAMES||current.mode==Mode.EYE_ANCHORED_KEYFRAMES||current.mode==Mode.QUATERNION_EYE_ANCHORED)) {
                if(((Number)nativeAim.get("calls")).intValue()==0)throw new AssertionError("Native FP selector was not evaluated");
                NativeFirstPersonCombatControl.assertExactNativeAim(player.getXRot());
            }
            if(current.mode==Mode.HEAD_ANCHORED_KEYFRAMES||current.mode==Mode.EYE_ANCHORED_KEYFRAMES||current.mode==Mode.QUATERNION_EYE_ANCHORED) BowHeadAnchorControl.assertFrame(fp&&action);
            if(current.mode==Mode.QUATERNION_EYE_ANCHORED)NativeFirstPersonCombatControl.assertQuaternionFrame(fp&&action,player.getXRot());
            if(nativePa&&(((Number)nativeAim.get("calls")).intValue()!=0||((Number)controls.get("nativeCombatDecisions")).intValue()!=0))
                throw new AssertionError("Compatibility transforms changed native PA baseline");
            if(armVisibilityMatrix)ArmVisibilityMatrixProbe.assertFrame(fp&&action,!fp||action,Boolean.TRUE.equals(current.nativeArms),current.wearable);
            var row=new LinkedHashMap<String,Object>();row.put("caseNumber",index+1);row.put("case",current.name());row.put("phase",suffix);
            row.put("firstPersonApiEnabled",FirstPersonAPI.isEnabled());row.put("combatCompatibilityEnabled",CompatConfig.combatCompatibility());
            row.put("nativeArmsEnabled",BetterCombatClientMod.config.isShowingArmsInFirstPerson);row.put("nativeFirstPersonModeSetting",BetterCombatClientMod.config.firstPersonAnimations.name());
            row.put("actualNativeFirstPersonMode",((IAnimatedPlayer)player).playerAnimator_getAnimation().getFirstPersonMode().name());
            row.put("mode",current.mode.name());row.put("half",current.half);row.put("pitch",current.pitch);row.put("crouch",current.crouch);
            row.put("equippedItem",BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());
            row.put("camera",mc.options.getCameraType().name());row.put("clientScale",player.getScale());row.put("serverScale",serverScale);
            row.put("useElapsed",player.getTicksUsingItem());row.put("serverUseElapsed",serverUseElapsed);row.put("nativeAttackActive",ExpandedCombatActions.attackActive(player));
            row.put("nativeAttackAnimation",current.combat?nativeAttack:"");
            if(current.combat) {
                row.put("nativeAttackAge",player.tickCount-attackSwing.startedAt());row.put("nativeAttackDuration",attackSwing.duration());row.put("nativeAttackUpswingTicks",attackSwing.upswingTicks());
                row.put("renderPartialTick",mc.getTimer().getGameTimeDeltaPartialTick(false));row.put("nativeAttackStartedAt",attackSwing.startedAt());row.put("clientTick",player.tickCount);row.put("attackAttempt",attackAttempts);
                if(action) {row.put("nativeSampleTarget",attackSampleAges[attackSampleIndex]);row.put("nativeSequenceComplete",false);
                    row.put("nativeSampleTargets",Arrays.stream(attackSampleAges).boxed().toList());
                    if(armVisibilityMatrix){int actualAge=player.tickCount-attackSwing.startedAt();if(actualAge<=lastAcceptedAttackAge)throw new AssertionError("Repeated native tick sample");lastAcceptedAttackAge=actualAge;}}
                if(ExpandedCombatActions.attackLayer(player) instanceof ModifierLayer<?> layer&&layer.getAnimation() instanceof KeyframeAnimationPlayer keyframe)
                    row.put("nativeKeyframe",Map.of("tick",keyframe.getTick(),"endTick",keyframe.getData().endTick,"stopTick",keyframe.getData().stopTick));
            }
            row.put("bodyYaw",player.yBodyRot);row.put("headYaw",player.yHeadRot);row.put("aimYaw",player.getYRot());
            row.put("firstPersonBowPoseEnabled",CompatConfig.FIRST_PERSON_BOW_POSE.get());
            row.put("firstPersonPoseCacheEnabled",CompatConfig.FIRST_PERSON_POSES.get());
            row.put("armor",armorIds(player));row.put("serverArmor",serverArmor);
            if(armVisibilityMatrix){row.put("armGeometry",ArmVisibilityMatrixProbe.snapshot());row.put("wearable",current.wearable);row.put("accessories",accessoryIds(player));row.put("serverAccessories",serverAccessories);
                row.put("requestedNativeArms",current.nativeArms);row.put("actualSkinModel",player.getSkin().model().id());
                var cached=dev.caleb.emfattachments.client.NativeAttackArmVisibility.from(((dev.caleb.emfattachments.compat.BetterCombatAttackAccess)player).emfattachments$attackStack(),mc.getTimer().getGameTimeDeltaPartialTick(false));
                row.put("cachedNativeAttackVisibility",cached);}
            row.put("clientSelected",player.getInventory().selected);row.put("serverSelected",serverSelected);
            row.put("controls",controls);row.put("renderCounts",counters);row.put("nativePose",NeaProjectilePoseProbe.snapshot(player.getUUID()));
            var offset=FirstPersonModelCore.instance.getLogicHandler().getOffset();row.put("cachedFpmOffset",List.of(offset.x,offset.y,offset.z));row.put("actualFinalBodyOffset",nativePa?List.of(0D,0D,0D):List.of(offset.x,offset.y,offset.z));row.put("fpmOffsetApplied",!nativePa);
            row.put("cameraPosition",mc.gameRenderer.getMainCamera().getPosition().toString());row.put("playerPosition",player.position().toString());
            String filename=String.format(Locale.ROOT,"%03d-%s-%s.png",++imageSerial,current.name(),suffix);
            long readbackStarted=System.nanoTime();
            row.put("captureChecksAndSnapshotMs",(readbackStarted-captureStarted)/1_000_000.0);
            row.put("renderFrameIntervalMs",currentFrameIntervalMs);
            var image=Screenshot.takeScreenshot(mc.getMainRenderTarget());
            row.put("screenshotReadbackMs",(System.nanoTime()-readbackStarted)/1_000_000.0);
            // GPU readback remains on the render thread; PNG compression must not
            // consume the next native animation sampling window.
            screenshotWrites.add(CompletableFuture.runAsync(()-> {
                try(image) {image.writeToFile(output.resolve(filename));}
                catch(Exception error) {throw new java.util.concurrent.CompletionException(error);}
            }));
            row.put("screenshot",filename);captures.add(row);write();
        }
        static Map<String,String> armorIds(LivingEntity entity) {
            var result=new LinkedHashMap<String,String>();
            for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))
                result.put(slot.getName(),BuiltInRegistries.ITEM.getKey(entity.getItemBySlot(slot).getItem()).toString());
            return result;
        }
        static Map<String,String> expectedArmor() {
            return Map.of("head","minecraft:diamond_helmet","chest","minecraft:diamond_chestplate","legs","minecraft:diamond_leggings","feet","minecraft:diamond_boots");
        }
        void finish(String status,String detail) {
            if(finishing) {report.put("status","RESTORE_FAILURE");report.put("restoreError",detail);write();BowVisibilityControls.end();active=null;return;}
            finishing=true;finalStatus=status;finalDetail=detail;ticks=0;
            BowVisibilityControls.end();ArmVisibilityMatrixProbe.end();CombatRenderCounters.active=false;keys.keySet().forEach(k->k.setDown(false));
            mc.gameMode.releaseUsingItem(player);NEABaseMod.config.bowAnimation=originalBow;NEAnimationsLoader.INSTANCE.animationProvider.refreshEnabledAnimations();
            CompatConfig.FIRST_PERSON_BOW_POSE.set(originalBowCompat);
            CompatConfig.FIRST_PERSON_POSES.set(originalPoseCache);
            CompatConfig.COMBAT_COMPATIBILITY.set(originalCombatCompatibility);
            BetterCombatClientMod.config.firstPersonAnimations=originalNativeFirstPersonMode;
            BetterCombatClientMod.config.isShowingArmsInFirstPerson=originalNativeArms;
            ready=new CompletableFuture<>();mc.getSingleplayerServer().execute(()-> {
                try {
                    var server=sp();ExpandedRagdollRecovery.release(server);ExpandedReactionTransition.begin(server);server.stopUsingItem();
                    if(inventory!=null) {
                        for(int i=0;i<inventory.size();i++)server.getInventory().setItem(i,inventory.get(i).copy());
                        server.getInventory().selected=originalServerSelected;
                        var cap=Objects.requireNonNull(AccessoriesCapability.get(server));accessories.forEach((name,saved)-> {
                            var container=Objects.requireNonNull(cap.getContainers().get(name));
                            if(container.getSize()!=saved.items.size()) throw new IllegalStateException("Accessory shape changed");
                            for(int i=0;i<container.getSize();i++) {container.getAccessories().setItem(i,saved.items.get(i).copy());container.getCosmeticAccessories().setItem(i,saved.cosmetics.get(i).copy());container.renderOptions().set(i,saved.renders.get(i));}
                        });
                        for(var entity:server.serverLevel().getAllEntities()) if(entity instanceof Projectile projectile&&projectile.getOwner()==server&&entity.distanceToSqr(origin)<16384) entity.discard();
                        blocks.forEach((pos,state)->server.level().setBlock(pos,state,3));server.serverLevel().setDayTime(dayTime);
                        server.setHealth(health);server.getFoodData().setFoodLevel(food);server.getFoodData().setSaturation(saturation);server.getAbilities().flying=serverFlying;server.onUpdateAbilities();
                        server.teleportTo(serverPosition.x,serverPosition.y,serverPosition.z);server.setDeltaMovement(Vec3.ZERO);server.fallDistance=0;ExpandedReactionTransition.displaced(server);
                        server.inventoryMenu.broadcastChanges();
                    }
                    restoreTick=mc.getSingleplayerServer().getTickCount();ready.complete(null);
                } catch(Throwable error){ready.completeExceptionally(error);}
            });
        }
        void restoreClient() {
            if(ready.isCompletedExceptionally()) ready.join();
            for(var pending:screenshotWrites) {if(pending.isCompletedExceptionally()) pending.join();if(!pending.isDone()) return;}
            if(!ready.isDone()||mc.getSingleplayerServer().getTickCount()-restoreTick<35||ticks<35) return;
            if(player.isPassenger()||serverMounted) throw new IllegalStateException("Native seat remained after fixture restoration");
            if(!restoreSelectionSent){player.getInventory().selected=originalServerSelected;
                player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(originalServerSelected));
                restoreSelectionPacketTick=mc.getSingleplayerServer().getTickCount();restoreSelectionSent=true;
                report.put("restoreSelectionPacketTarget",originalServerSelected);write();return;}
            if(mc.getSingleplayerServer().getTickCount()-restoreSelectionPacketTick<3)return;
            if(player.getInventory().selected!=originalServerSelected||serverSelected!=originalServerSelected)
                throw new AssertionError("Authoritative selected slot failed post-packet restoration: client="+player.getInventory().selected+", server="+serverSelected+", expected="+originalServerSelected);
            player.setYRot(yaw);player.yRotO=yaw;player.setXRot(pitch);player.xRotO=pitch;
            player.yBodyRot=bodyYaw;player.yBodyRotO=bodyYaw;player.yHeadRot=headYaw;player.yHeadRotO=headYaw;player.setDeltaMovement(motion);player.getAbilities().flying=originalFlying;
            // Both sides intentionally return to the original authoritative server selection.
            // A pre-existing client/server discrepancy is recorded, never silently reported as exact client restoration.
            mc.options.setCameraType(originalCamera);mc.options.hideGui=originalGui;FirstPersonAPI.setEnabled(originalFpm);keys.forEach(KeyMapping::setDown);
            report.put("originalServerSelected",originalServerSelected);report.put("clientSelectedRestored",player.getInventory().selected==originalClientSelected);report.put("serverSelectedRestored",serverSelected==originalServerSelected);
            report.put("selectionReconciledToOriginalServer",player.getInventory().selected==originalServerSelected&&serverSelected==originalServerSelected);
            report.put("originalSelectedSidesMatched",originalClientSelected==originalServerSelected);
            report.put("selectionAfterNativePacket",Map.of("client",player.getInventory().selected,"server",serverSelected,
                    "observedServerTicksAfterPacket",mc.getSingleplayerServer().getTickCount()-restoreSelectionPacketTick));
            report.put("inventoryAndAccessoriesRestored",inventory!=null);report.put("arenaAndTimeRestored",inventory!=null);report.put("clientSettingsRestored",true);
            report.put("firstPersonBowPoseRestored",CompatConfig.FIRST_PERSON_BOW_POSE.get()==originalBowCompat);
            if(CompatConfig.FIRST_PERSON_BOW_POSE.get()!=originalBowCompat) throw new AssertionError("Bow compatibility option was not restored");
            report.put("firstPersonPoseCacheRestored",CompatConfig.FIRST_PERSON_POSES.get()==originalPoseCache);
            if(CompatConfig.FIRST_PERSON_POSES.get()!=originalPoseCache) throw new AssertionError("Pose-cache compatibility option was not restored");
            report.put("nativeCombatSettingsRestored",CompatConfig.COMBAT_COMPATIBILITY.get()==originalCombatCompatibility
                    &&BetterCombatClientMod.config.firstPersonAnimations==originalNativeFirstPersonMode&&BetterCombatClientMod.config.isShowingArmsInFirstPerson==originalNativeArms);
            if(!Boolean.TRUE.equals(report.get("nativeCombatSettingsRestored")))throw new AssertionError("Native combat settings not restored");
            report.put("bowConfigurationRestored",NEABaseMod.config.bowAnimation==originalBow);report.put("completedCases",index);report.put("status",finalStatus);report.put("detail",finalDetail);write();active=null;
        }
        void write() {
            // A native swing keeps ticking while the render thread serializes JSON.
            // Defer the whole growing report during its five-sample window, including
            // next(1)/next(3) and retry-start writes. Keep all rows in memory; the
            // complete-swing transition, idle/recovery, or failure flush persists them.
            if(!finishing&&current!=null&&current.combat&&(phase==1||phase==3)&&ExpandedCombatActions.attackActive(player)){
                deferredReportWrites++;return;
            }
            report.put("reportIo",Map.of("completedEarlierWrites",reportWrites,"deferredWhileNativeSampling",deferredReportWrites,
                    "lastCompletedWriteMs",lastReportWriteNanos/1_000_000.0,"maxCompletedWriteMs",maxReportWriteNanos/1_000_000.0,
                    "totalCompletedWriteMs",totalReportWriteNanos/1_000_000.0,
                    "scope","Synchronous JSON serialization plus file write; current in-progress write is not included in these prior-write timings."));
            long started=System.nanoTime();
            try {Files.writeString(output.resolve("bow-visibility-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}
            catch(Exception error){throw new RuntimeException(error);}
            lastReportWriteNanos=System.nanoTime()-started;maxReportWriteNanos=Math.max(maxReportWriteNanos,lastReportWriteNanos);
            totalReportWriteNanos+=lastReportWriteNanos;reportWrites++;
        }
    }
}
