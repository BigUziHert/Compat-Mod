package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.wispforest.accessories.api.AccessoriesCapability;
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
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Staged developer fixture: native held shield plus Zombie.doHurtTarget, never direct shield activation. */
public final class ExpandedShieldDefenseSequence {
    private static Session active;
    private static volatile long serverTicks;
    private static final String[] ITEMS = {"cataclysm:azure_sea_shield", "cataclysm:black_steel_targe", "cataclysm:bulwark_of_the_flame"};
    private ExpandedShieldDefenseSequence() {}
    public static void register() {
        if (!ModList.get().isLoaded("cataclysm")) return;
        NeoForge.EVENT_BUS.addListener(ExpandedShieldDefenseSequence::tick);
        NeoForge.EVENT_BUS.addListener(ExpandedShieldDefenseSequence::preFrame);
        NeoForge.EVENT_BUS.addListener(ExpandedShieldDefenseSequence::postFrame);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, true, ExpandedShieldDefenseSequence::observeBlock);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> serverTicks++);
    }
    private static void observeBlock(net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent event) {
        Session s=active;
        if(s==null || !s.observingAttack || event.getEntity()!=s.attackedPlayer
                || event.getDamageSource().getEntity()!=s.attacker || event.getEntity().level().isClientSide()) return;
        s.observedBlock=event;
    }
    private static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (active != null) { active.tick(); return; }
            if (mc.player == null || mc.screen != null || CombatRenderCounters.active || ExpandedLiveWearableSequence.isActive()) return;
            Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-shield-defense.txt");
            if (!Files.isRegularFile(trigger)) return;
            if (!NativeWorldReadiness.ready()) return;
            if (!mc.hasSingleplayerServer() || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client")))
                throw new IllegalStateException("Disposable integrated .local/client only");
            String label = Files.readString(trigger).trim().replaceAll("[^a-zA-Z0-9._-]", "_");
            Files.delete(trigger); active = new Session(mc, label); active.initialize();
        } catch (Throwable failure) {
            if (active != null) active.finish("FAIL", failure.toString());
            else LogUtils.getLogger().error("Shield defense fixture startup failed", failure);
        }
    }
    private static void preFrame(RenderFrameEvent.Pre e) { if (active != null && !active.finishing) CombatRenderCounters.reset(); }
    private static void postFrame(RenderFrameEvent.Post e) {
        if (active != null && !active.finishing) try { active.frame(); } catch (Throwable failure) { active.finish("FAIL", failure.toString()); }
    }
    private record Case(String item, boolean blocking, float pitch, boolean crouch) {}
    private record Accessories(List<ItemStack> normal, List<ItemStack> cosmetic, List<Boolean> renders) {}
    private static final class Session {
        final Minecraft mc; final UUID uuid; final Path output; final CameraType camera; final boolean gui;
        final Map<KeyMapping, Boolean> keys = new LinkedHashMap<>();
        final List<Case> cases = new ArrayList<>(); final List<Map<String, Object>> rows = new ArrayList<>();
        final List<String> failures = new ArrayList<>(); final Map<String, Object> report = new LinkedHashMap<>();
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        Snapshot original; Zombie attacker; Vec3 origin; CompletableFuture<?> pending;
        ServerPlayer attackedPlayer; boolean observingAttack;
        net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent observedBlock;
        Map<String, Object> row, attack; String phase = "INITIALIZING", finalStatus, detail; int index, ticks, stable;
        long readyAfter, restoreAfter; boolean finishing, restored; String capture; float clientYaw, clientPitch;
        Session(Minecraft mc, String label) throws Exception {
            this.mc = mc; uuid = mc.player.getUUID();
            if (mc.player.isPassenger() || mc.player.isUsingItem()) throw new IllegalStateException("Start recovered and idle");
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label.isBlank() ? "shield-defense" : label);
            Files.createDirectories(output); camera = mc.options.getCameraType(); gui = mc.options.hideGui;
            clientYaw = mc.player.getYRot(); clientPitch = mc.player.getXRot();
            for (var key : List.of(mc.options.keyUse, mc.options.keyAttack, mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
                    mc.options.keyRight, mc.options.keyJump, mc.options.keyShift, mc.options.keySprint)) { keys.put(key, key.isDown()); key.setDown(false); }
            for (String item : ITEMS) {
                cases.add(new Case(item, false, 0, false));
                for (float pitch : new float[]{0, 55}) for (boolean crouch : new boolean[]{false, true}) cases.add(new Case(item, true, pitch, crouch));
            }
            report.put("scope", "Three native shields; lowered control and raised level/down standing/crouched defense. Real client use key and gameMode.useItem; authoritative Zombie.doHurtTarget invokes the normal mob damage/shield/stat path. The attacker AI is disabled so exactly one native hit is delivered. Not an AI navigation or arrow-reflection test. Crouched guards stand before release to isolate defense from Bulwark's separate crouch-release charge ability.");
            report.put("label", label); report.put("firstPersonInstalled", ModList.get().isLoaded("firstperson"));
            report.put("firstPersonEnabled", ModList.get().isLoaded("firstperson") && Fpm.enabled());
            report.put("caseCount", cases.size()); report.put("cases", rows); report.put("failures", failures);
            mc.options.hideGui = false; mc.options.setCameraType(CameraType.FIRST_PERSON); CombatRenderCounters.active = true;
        }
        <T> CompletableFuture<T> server(Function<ServerPlayer, T> action) {
            var future = new CompletableFuture<T>(); mc.getSingleplayerServer().execute(() -> {
                try { var p = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid); if (p == null) throw new IllegalStateException("Missing player"); future.complete(action.apply(p)); }
                catch (Throwable failure) { future.completeExceptionally(failure); }
            }); return future;
        }
        void initialize() {
            pending = server(p -> {
                original = new Snapshot(p); int y = p.serverLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getBlockX()+96, p.getBlockZ()+96)+16;
                var base = new BlockPos(p.getBlockX()+96, y, p.getBlockZ()+96); origin = new Vec3(base.getX()+.5, y+1, base.getZ()+.5);
                for (int x=-5; x<=5; x++) for (int z=-5; z<=5; z++) for (int dy=0; dy<=5; dy++) {
                    var pos=base.offset(x,dy,z); if (!p.serverLevel().getBlockState(pos).isAir() || p.serverLevel().getBlockEntity(pos)!=null) throw new IllegalStateException("Arena must be empty: "+pos);
                    if (dy==0) blocks.put(pos, p.serverLevel().getBlockState(pos));
                }
                blocks.keySet().forEach(pos -> p.serverLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 3));
                p.getServer().setDifficulty(Difficulty.NORMAL, true); p.serverLevel().setDayTime(6000); return null;
            }); write("PREPARING", "");
        }
        void prepare() {
            Case c=cases.get(index); row=new LinkedHashMap<>(); row.put("item",c.item); row.put("blocking",c.blocking); row.put("pitch",c.pitch); row.put("crouch",c.crouch); row.put("presentationSamples",new ArrayList<Map<String,Object>>()); row.put("equipHeightTimeline",new ArrayList<Map<String,Object>>()); rows.add(row);
            ticks=stable=0; phase="PREPARING"; pending=server(p -> {
                if (attacker!=null) attacker.discard(); ExpandedRagdollRecovery.release(p); p.stopRiding(); p.stopUsingItem();
                ExpandedReactionTransition.begin(p); readyAfter=serverTicks+30;
                p.getInventory().clearContent(); AccessoriesCapability.get(p).getContainers().values().forEach(a -> { a.getAccessories().clearContent(); a.getCosmeticAccessories().clearContent(); });
                p.getInventory().selected=0; p.setItemInHand(InteractionHand.MAIN_HAND, BuiltInRegistries.ITEM.get(ResourceLocation.parse(c.item)).getDefaultInstance());
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(0));
                p.setGameMode(GameType.SURVIVAL); p.getAbilities().flying=false; p.onUpdateAbilities(); p.setNoGravity(false);
                p.removeAllEffects(); p.setAbsorptionAmount(0); p.setHealth(p.getMaxHealth()); p.getFoodData().setFoodLevel(20);
                p.teleportTo(p.serverLevel(),origin.x,origin.y,origin.z,0,c.pitch); p.setDeltaMovement(Vec3.ZERO); p.fallDistance=0;
                ExpandedReactionTransition.displaced(p);
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p)); p.inventoryMenu.broadcastChanges();
                attacker=EntityType.ZOMBIE.create(p.serverLevel()); if(attacker==null)throw new IllegalStateException("Zombie creation failed");
                attacker.setNoAi(true); attacker.setPersistenceRequired(); attacker.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
                attacker.moveTo(origin.x,origin.y,origin.z+1.6,180,0); p.serverLevel().addFreshEntity(attacker); return null;
            }); write("RUNNING", "");
        }
        void tick() {
            if (pending!=null) {
                if (!pending.isDone()) return;
                Object value;
                try { value=pending.join(); }
                catch (Throwable failure) {
                    if(!finishing)throw new IllegalStateException(failure);
                    finalStatus="FAIL";detail+="; restoration failed: "+failure;report.put("restorationFailed",true);value=null;
                }
                pending=null;
                if(finishing&&restored&&value instanceof Map<?,?> state) {
                    state.forEach((key,v)->report.put(String.valueOf(key),v));
                    report.put("stateRestored",state.values().stream().allMatch(Boolean.TRUE::equals));
                }
                if (phase.equals("ATTACKING")) { @SuppressWarnings("unchecked") Map<String,Object> state=(Map<String,Object>)value; attack=state; row.put("nativeAttack",state); write("RUNNING", ""); if(!Boolean.TRUE.equals(state.get("defensiveOutcomeValid")))throw new IllegalStateException("Native shield outcome mismatch: "+state); phase="CAPTURING";ticks=0; }
                else if (phase.equals("GUARD_READY_CHECK")) {
                    @SuppressWarnings("unchecked") Map<String,Object> state=(Map<String,Object>)value;
                    row.put("nativeGuardReadiness",state);
                    if(Boolean.TRUE.equals(state.get("ready"))) {phase="PREHIT";capture="pre_hit";}
                    else phase="GUARDING";
                }
                else if (phase.equals("RELEASE_WAIT")) {
                    if (Boolean.TRUE.equals(value)) { mc.options.keyUse.setDown(false); if(mc.player.isUsingItem())mc.gameMode.releaseUsingItem(mc.player); phase="RELEASED";ticks=0; }
                }
            }
            if (finishing) {
                if (!restored) { if(serverTicks<restoreAfter)return; restored=true; pending=server(this::restore); return; }
                if(++ticks<20)return;
                mc.options.setCameraType(camera);mc.options.hideGui=gui; mc.player.setYRot(clientYaw);mc.player.setXRot(clientPitch);keys.forEach(KeyMapping::setDown);
                CombatRenderCounters.active=false; report.put("inputRestored",keys.entrySet().stream().allMatch(e->e.getKey().isDown()==e.getValue()));
                report.put("nativeClientSeatReleased",ExpandedRagdollRecovery.clientRestored(mc.player));write(finalStatus,detail);active=null;return;
            }
            if(mc.player==null||mc.screen!=null||!mc.player.isAlive())throw new IllegalStateException("Player/world changed");
            if(phase.equals("INITIALIZING")){prepare();return;}
            Case c=cases.get(index);ticks++;
            if(phase.equals("GUARDING")||phase.equals("ATTACKING")||phase.equals("CAPTURING")||phase.equals("RECOVERY_HOLD")) {
                @SuppressWarnings("unchecked") var timeline=(List<Map<String,Object>>)row.get("equipHeightTimeline");
                if(timeline.size()<100) {var sample=ShieldHandPresentationProbe.snapshot(false);sample.put("phase",phase);timeline.add(sample);}
            }
            mc.options.setCameraType(CameraType.FIRST_PERSON);mc.player.setYRot(0);mc.player.setXRot(c.pitch);
            if(phase.equals("PREPARING")) {
                mc.options.keyShift.setDown(c.crouch); boolean ready=serverTicks>=readyAfter&&mc.player.onGround()&&!mc.player.isPassenger()
                    &&mc.player.position().distanceTo(origin)<.7&&mc.player.getMainHandItem().is(BuiltInRegistries.ITEM.get(ResourceLocation.parse(c.item)));
                row.put("nativeWorldReadiness",NativeWorldReadiness.evidence());
                ready=ready&&NativeWorldReadiness.ready();
                stable=ready?stable+1:0;
                row.put("readiness",Map.of("position",mc.player.position().toString(),"origin",origin.toString(),
                    "distance",mc.player.position().distanceTo(origin),"grounded",mc.player.onGround(),
                    "passenger",mc.player.isPassenger(),"selectedSlot",mc.player.getInventory().selected,
                    "heldItem",BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString(),
                    "serverTick",serverTicks,"readyAfter",readyAfter,"clientSeatReleased",ExpandedRagdollRecovery.clientRestored(mc.player)));
                if(ticks>200)throw new IllegalStateException("Shield synchronization timeout "+c+": "+row.get("readiness"));if(stable<8)return;
                if(c.blocking){mc.options.keyUse.setDown(true);mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);if(!mc.player.isUsingItem())throw new IllegalStateException("Native shield use failed");}
                phase="GUARDING";ticks=0;return;
            }
            if(phase.equals("GUARDING")&&ticks>=14) {
                if(ticks>140)throw new IllegalStateException("Authoritative native guard did not become ready: "+row.get("nativeGuardReadiness"));
                phase="GUARD_READY_CHECK";
                pending=server(p -> Map.of("ready",p.isShiftKeyDown()==c.crouch && p.isBlocking()==c.blocking
                                && (!c.blocking || p.getTicksUsingItem()>=10),
                        "serverBlocking",p.isBlocking(),"serverUseElapsed",p.getTicksUsingItem(),
                        "serverCrouch",p.isShiftKeyDown(),"serverTick",serverTicks));
                return;
            }
            if(phase.equals("CAPTURING")&&ticks>=3)capture="post_hit";
            if(phase.equals("RECOVERY_HOLD")&&ticks>=20) {
                var hand=ShieldHandPresentationProbe.snapshot(false);
                boolean settled=((Number)hand.get("mainHandHeight")).floatValue()>=.98F
                        &&Boolean.TRUE.equals(hand.get("sameItemComponents"));
                if(settled) {capture="post_recovery";phase="RECOVERY_CAPTURE";}
                else if(ticks>80)throw new IllegalStateException("Native equip height did not recover: "+hand);
            }
            if(phase.equals("RELEASE_WAIT")) { mc.options.keyShift.setDown(false); if(ticks>100)throw new IllegalStateException("Standing release synchronization failed"); if(pending==null)pending=server(p->!p.isShiftKeyDown()); }
            if(phase.equals("RELEASED")&&ticks>=12) {
                if(mc.player.isUsingItem())throw new IllegalStateException("Shield use remained active after native release");row.put("nativeUseReleased",true);row.put("status","CAPTURED");write("RUNNING", "");
                if(++index==cases.size())finish(failures.isEmpty()?"PASS":"FAIL","Native blocking and lowered controls captured; inspect screenshots separately");else prepare();
            }
        }
        Map<String,Object> hit(ServerPlayer p,Case c) {
            if(p.gameMode.getGameModeForPlayer()!=GameType.SURVIVAL||p.getAbilities().invulnerable)throw new IllegalStateException("Incoming hit must reach Survival damage hooks");
            if(p.isShiftKeyDown()!=c.crouch||p.isBlocking()!=c.blocking)throw new IllegalStateException("Native guard precondition mismatch: "+p.isBlocking()+" crouch="+p.isShiftKeyDown());
            if(c.blocking&&p.getTicksUsingItem()<10)throw new IllegalStateException("Native shield blocking delay not elapsed");
            float health=p.getHealth(),absorption=p.getAbsorptionAmount();int stat=p.getStats().getValue(Stats.CUSTOM.get(Stats.DAMAGE_BLOCKED_BY_SHIELD));
            var state=new LinkedHashMap<String,Object>();state.put("serverUseElapsed",p.getTicksUsingItem());state.put("serverBlocking",p.isBlocking());state.put("serverCrouch",p.isShiftKeyDown());state.put("healthBefore",health);state.put("attackerPosition",attacker.position().toString());
            int durabilityBefore=p.getMainHandItem().getDamageValue();
            observedBlock=null;attackedPlayer=p;observingAttack=true;
            boolean accepted;
            try {accepted=attacker.doHurtTarget(p);} finally {observingAttack=false;attackedPlayer=null;}
            // Read the retained event after all listeners and native damage processing return.
            Map<String,Object> blockEvent=observedBlock==null?Map.of():Map.of("originalBlocked",observedBlock.getOriginalBlock(),"blocked",observedBlock.getBlocked(),
                    "canceled",observedBlock.isCanceled(),"blockedDamage",observedBlock.getDamageContainer().getBlockedDamage(),
                    "originalDamage",observedBlock.getDamageContainer().getOriginalDamage(),"shieldDamage",observedBlock.getDamageContainer().getShieldDamage());
            float loss=health-p.getHealth();int blocked=p.getStats().getValue(Stats.CUSTOM.get(Stats.DAMAGE_BLOCKED_BY_SHIELD))-stat;
            state.put("nativeMobAttackReturn",accepted);state.put("healthLoss",loss);state.put("blockedDamageStatIncrease",blocked);state.put("absorptionLoss",absorption-p.getAbsorptionAmount());state.put("nativeRagdoll",ExpandedRagdollRecovery.active(p));
            state.put("nativeShieldBlockEvent",blockEvent);state.put("shieldDurabilityLoss",p.getMainHandItem().getDamageValue()-durabilityBefore);
            // The authoritative native block event proves defense independently of
            // whether another mod records the optional statistic. Never synthesize it.
            boolean nativeBlocked=Boolean.TRUE.equals(blockEvent.get("originalBlocked"))&&Boolean.TRUE.equals(blockEvent.get("blocked"))
                    &&Boolean.FALSE.equals(blockEvent.get("canceled"))&&blockEvent.get("blockedDamage") instanceof Number amount&&amount.floatValue()>0;
            boolean valid=c.blocking?loss==0&&nativeBlocked:loss>0&&!nativeBlocked;state.put("defensiveOutcomeValid",valid);
            return state;
        }
        void frame() throws Exception {
            if(capture==null)return;String samplePhase=capture;capture=null;Case c=cases.get(index);String file=String.format(Locale.ROOT,"%02d-%s-%s-%d-%s.png",index+1,c.item.replace(':','_'),c.blocking?"guard":"lowered",(int)c.pitch,c.crouch?"crouch":"stand");
            if(!samplePhase.equals("post_hit")) file=file.replace(".png","-"+samplePhase+".png");
            try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve(file));}
            var presentation=new LinkedHashMap<String,Object>();presentation.put("phase",samplePhase);presentation.put("screenshot",file);
            presentation.put("hand",ShieldHandPresentationProbe.snapshot(true));presentation.put("renderCounts",CombatRenderCounters.snapshot());
            presentation.put("usingItem",mc.player.isUsingItem());presentation.put("useElapsed",mc.player.getTicksUsingItem());
            @SuppressWarnings("unchecked") var samples=(List<Map<String,Object>>)row.get("presentationSamples");samples.add(presentation);
            if(samplePhase.equals("pre_hit")) {phase="ATTACKING";pending=server(p -> hit(p,c));write("RUNNING", "");return;}
            if(samplePhase.equals("post_recovery")) {row.put("postHitEquipRecovered",true);mc.options.keyShift.setDown(false);phase="RELEASE_WAIT";ticks=0;write("RUNNING", "");return;}
            row.put("screenshot",file);row.put("renderCounts",CombatRenderCounters.snapshot());row.put("clientUsing",mc.player.isUsingItem());row.put("clientUseElapsed",mc.player.getTicksUsingItem());
            row.put("nativePose",NeaProjectilePoseProbe.snapshot(uuid));row.put("camera",mc.options.getCameraType().name());row.put("visibleAssessment","PENDING_HUMAN_REVIEW");
            // Keep the native guard held until hand presentation recovers; then stand before ordinary release.
            phase="RECOVERY_HOLD";ticks=0;write("RUNNING", "");
        }
        void finish(String status,String message) {
            if(finishing)return;finishing=true;finalStatus=status;detail=message;if(!status.equals("PASS"))failures.add(message);keys.keySet().forEach(k->k.setDown(false));
            // Failure cleanup must not accidentally trigger Bulwark crouch-release.
            // Successful cases already exercised normal release after standing.
            if(mc.player!=null&&mc.player.isUsingItem()){report.put("abortedUseDuringCleanup",true);mc.player.stopUsingItem();}ticks=0;phase="RESTORING";
            pending=server(p->{p.setShiftKeyDown(false);p.stopUsingItem();ExpandedRagdollRecovery.release(p);ExpandedReactionTransition.begin(p);restoreAfter=serverTicks+30;return null;});write("RESTORING",message);
        }
        Map<String,Object> restore(ServerPlayer p) {
            if(attacker!=null)attacker.discard();ExpandedRagdollRecovery.release(p);p.stopRiding();p.stopUsingItem();
            if(original!=null)original.restore(p);blocks.forEach((pos,state)->p.serverLevel().setBlock(pos,state,3));
            var result=Map.<String,Object>of("inventoryRestored",original==null||original.inventory.equals(p.getInventory().save(new ListTag())),"blocksRestored",blocks.entrySet().stream().allMatch(e->p.serverLevel().getBlockState(e.getKey()).equals(e.getValue())),"difficultyRestored",original==null||p.serverLevel().getDifficulty()==original.difficulty,"healthRestored",original==null||p.getHealth()==original.health);
            // Publish only through a completed future; no server thread mutates the report map.
            return result;
        }
        void write(String status,String message) {
            try {report.put("status",status);report.put("detail",message);report.put("completedCases",rows.stream().filter(r->r.containsKey("status")).count());Files.writeString(output.resolve("shield-defense-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}
            catch(Exception failure){throw new IllegalStateException(failure);}
        }
    }
    private static final class Fpm { static boolean enabled(){return dev.tr7zw.firstperson.api.FirstPersonAPI.isEnabled();} }
    private static final class Snapshot {
        final ListTag inventory;final int selected;final Vec3 position,motion;final float yaw,pitch,health,absorption,fall;
        final GameType mode;final Difficulty difficulty;final long day;final boolean flying,noGravity,difficultyLocked;final CompoundTag food=new CompoundTag();final int invulnerableTime,hurtTime,hurtDuration;
        final List<MobEffectInstance> effects;final Map<String,Accessories> accessories=new LinkedHashMap<>();final Map<ResourceLocation,Integer> stats=new LinkedHashMap<>();final Map<String,Integer> useStats=new LinkedHashMap<>();
        Snapshot(ServerPlayer p){inventory=p.getInventory().save(new ListTag());selected=p.getInventory().selected;position=p.position();motion=p.getDeltaMovement();yaw=p.getYRot();pitch=p.getXRot();health=p.getHealth();absorption=p.getAbsorptionAmount();fall=p.fallDistance;mode=p.gameMode.getGameModeForPlayer();difficulty=p.serverLevel().getDifficulty();difficultyLocked=p.getServer().getWorldData().isDifficultyLocked();day=p.serverLevel().getDayTime();flying=p.getAbilities().flying;noGravity=p.isNoGravity();invulnerableTime=p.invulnerableTime;hurtTime=p.hurtTime;hurtDuration=p.hurtDuration;p.getFoodData().addAdditionalSaveData(food);effects=p.getActiveEffects().stream().map(MobEffectInstance::new).toList();
            for(var stat:List.of(Stats.DAMAGE_BLOCKED_BY_SHIELD,Stats.DAMAGE_TAKEN))stats.put(stat,p.getStats().getValue(Stats.CUSTOM.get(stat)));
            for(String id:ITEMS)useStats.put(id,p.getStats().getValue(Stats.ITEM_USED.get(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)))));
            AccessoriesCapability.get(p).getContainers().forEach((name,c)->{var normal=new ArrayList<ItemStack>();var cosmetic=new ArrayList<ItemStack>();for(int i=0;i<c.getSize();i++){normal.add(c.getAccessories().getItem(i).copy());cosmetic.add(c.getCosmeticAccessories().getItem(i).copy());}accessories.put(name,new Accessories(normal,cosmetic,new ArrayList<>(c.renderOptions())));});
        }
        void restore(ServerPlayer p){p.getInventory().load(inventory);p.getInventory().selected=selected;p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(selected));var cap=AccessoriesCapability.get(p);accessories.forEach((name,s)->{var c=cap.getContainers().get(name);for(int i=0;i<s.normal.size();i++){c.getAccessories().setItem(i,s.normal.get(i));c.getCosmeticAccessories().setItem(i,s.cosmetic.get(i));c.renderOptions().set(i,s.renders.get(i));}});p.setGameMode(mode);p.getAbilities().flying=flying;p.onUpdateAbilities();p.setNoGravity(noGravity);p.removeAllEffects();effects.forEach(p::addEffect);p.setHealth(health);p.setAbsorptionAmount(absorption);p.invulnerableTime=invulnerableTime;p.hurtTime=hurtTime;p.hurtDuration=hurtDuration;p.getFoodData().readAdditionalSaveData(food);p.getServer().setDifficulty(difficulty,true);p.getServer().getWorldData().setDifficultyLocked(difficultyLocked);p.serverLevel().setDayTime(day);stats.forEach((stat,value)->p.getStats().setValue(p,Stats.CUSTOM.get(stat),value));useStats.forEach((id,value)->p.getStats().setValue(p,Stats.ITEM_USED.get(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))),value));p.teleportTo(p.serverLevel(),position.x,position.y,position.z,yaw,pitch);p.setDeltaMovement(motion);p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));p.fallDistance=fall;ExpandedReactionTransition.displaced(p);p.inventoryMenu.broadcastChanges();}
    }
}
