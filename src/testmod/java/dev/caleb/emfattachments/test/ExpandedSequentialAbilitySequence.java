package dev.caleb.emfattachments.test;

import com.aetherteam.aether.attachment.AetherDataAttachments;
import com.aetherteam.aether.client.event.hooks.CapabilityClientHooks;
import com.aetherteam.aether.entity.miscellaneous.CloudMinion;
import com.aetherteam.aether.entity.projectile.crystal.CloudCrystal;
import com.github.L_Ender.cataclysm.entity.projectile.Player_Ceraunus_Entity;
import com.github.L_Ender.cataclysm.init.ModDataComponents;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.sweenus.simplyswords.api.AwakeningApi;
import net.sweenus.simplyswords.entity.SoulstalkerStrideEntity;
import net.sweenus.simplyswords.item.component.StoredChargeComponent;
import net.sweenus.simplyswords.registry.ComponentTypeRegistry;
import net.sweenus.simplyswords.registry.EffectRegistry;
import net.sweenus.simplyswords.world.Phase10WeaponManager;
import net.sweenus.simplyswords.world.IonboundStormscaleAbilityManager;

/** Opt-in dependent ability actions on the same native stack, only in the disposable client. */
public final class ExpandedSequentialAbilitySequence {
    private static volatile Session active;
    private ExpandedSequentialAbilitySequence() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ExpandedSequentialAbilitySequence::tick);
        NeoForge.EVENT_BUS.addListener(ExpandedSequentialAbilitySequence::preFrame);
        NeoForge.EVENT_BUS.addListener(ExpandedSequentialAbilitySequence::postFrame);
        NeoForge.EVENT_BUS.addListener(ExpandedSequentialAbilitySequence::serverTick);
        NeoForge.EVENT_BUS.addListener(ExpandedSequentialAbilitySequence::entityAdded);
        NeoForge.EVENT_BUS.addListener(ExpandedSequentialAbilitySequence::damageApplied);
        net.bettercombat.api.client.BetterCombatClientEvents.ATTACK_HIT.register((player,hand,targets,cursor)-> {
            var s=active;
            if(s!=null && s.observing && !s.finishing && (s.test.name.equals("stormbringer") || s.test.name.equals("bulwark_melee") || s.test.name.equals("bloodwake") || s.test.name.equals("molten_edge") || s.test.name.equals("soulrender") || s.test.name.equals("tempest")) && player==s.player) {
                var sample=new LinkedHashMap<String,Object>();sample.put("animation",hand.attack().animation());sample.put("clientTick",player.tickCount);
                sample.put("cursor",cursor==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(cursor.getType())+":"+cursor.getUUID());
                sample.put("targets",targets.stream().map(e->Map.of("id",e.getId(),"uuid",e.getUUID().toString(),"type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),"designated",s.target!=null && e.getUUID().equals(s.target.getUUID()))).toList());
                s.nativeAttackHit=sample; if(s.test.name.equals("bloodwake") || s.test.name.equals("molten_edge") || s.test.name.equals("soulrender") || s.test.name.equals("tempest"))s.bloodHitEvents.add(sample);
            }
        });
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) {
            if (active.finishing) return;
            if (mc.player != active.player || mc.level == null || mc.screen != null || !mc.player.isAlive()) {
                active.finish("ABORTED", "Player/world changed, death, or screen opened"); return;
            }
            try { active.tick(); } catch (Throwable e) { active.fail(e); }
            return;
        }
        if (mc.player == null || mc.screen != null || CombatRenderCounters.active) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-sequential-abilities.txt");
        if (!Files.isRegularFile(trigger)) return;
        if (!NativeWorldReadiness.ready()) return;
        try {
            if (!mc.hasSingleplayerServer() || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client")))
                throw new IllegalStateException("Requires disposable .local/client integrated world");
            String[] lines = Files.readString(trigger).trim().split("\\R"); Files.delete(trigger);
            active = new Session(mc, lines[0].replaceAll("[^a-zA-Z0-9._-]", "_"), lines.length > 1 ? lines[1] : "all",
                lines.length > 2 ? lines[2] : "both",Arrays.stream(lines).anyMatch(s->s.trim().equals("fpm_disabled")));
            CombatRenderCounters.active = true; active.next();
        } catch (Throwable e) {
            LogUtils.getLogger().error("Sequential ability startup", e);
            if (active != null) active.finish("FAIL", e.toString());
        }
    }
    private static void preFrame(RenderFrameEvent.Pre event) { if (active != null && !active.finishing) CombatRenderCounters.reset(); }
    private static void postFrame(RenderFrameEvent.Post event) {
        if (active != null && !active.finishing) try { active.frame(); } catch (Throwable e) { active.fail(e); }
    }
    private static void serverTick(ServerTickEvent.Post event) {
        if (active != null && active.observing && !active.finishing) active.observe();
    }
    private static void entityAdded(EntityJoinLevelEvent event) {
        Session s = active;
        if (s != null && s.observing && !s.finishing && !event.getLevel().isClientSide()
            && event.getEntity().distanceToSqr(s.origin) < 4096 && !(event.getEntity() instanceof net.minecraft.world.entity.player.Player)) {
            s.spawned.add(event.getEntity().getUUID());
            s.types.merge(BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).toString(), 1, Integer::sum);
            if(event.getEntity() instanceof Player_Ceraunus_Entity anchor) {
                s.projectileCount++; s.ceraProjectiles.put(anchor.getUUID(),anchor);
                s.ceraSpawnTicks.put(anchor.getUUID(),(long)anchor.getServer().getTickCount());
            }
            if(s.test.name.equals("bloodwake") && event.getEntity().getTags().contains("simplyswords_bloodwake_wave_visual")) s.bloodWaveSpawns++;
            if(event.getEntity() instanceof CloudCrystal) s.cloudCrystals++;
            if(event.getEntity() instanceof com.github.L_Ender.cataclysm.entity.effect.Void_Vortex_Entity vortex && vortex.getOwner()==s.sp()) s.nativeVortex=vortex;
        }
    }
    private static void damageApplied(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        var s=active;
        if(s==null || !s.observing || s.finishing || (!s.test.name.equals("stormbringer") && !s.test.name.equals("bulwark_melee") && !s.test.name.equals("bloodwake") && !s.test.name.equals("ionbound") && !s.test.name.equals("molten_edge") && !s.test.name.equals("soulrender") && !s.test.name.equals("tempest") && !s.test.name.equals("soulkeeper")) || event.getEntity().level().isClientSide())return;
        var source=event.getSource().getEntity();if(source==null || !source.getUUID().equals(s.player.getUUID()))return;
        s.nativeDamageEvents.add(Map.of("target",event.getEntity().getUUID().toString(),"type",BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).toString(),"designated",s.target!=null&&event.getEntity().getUUID().equals(s.target.getUUID()),"secondary",s.chainTarget!=null&&event.getEntity().getUUID().equals(s.chainTarget.getUUID()),"damage",event.getNewDamage(),"damageSource",event.getSource().getMsgId(),"serverTick",event.getEntity().getServer().getTickCount()));
    }
    private record Test(String name, String item, float pitch, boolean crouch, int combo) {
        Test(String name,String item,float pitch) { this(name,item,pitch,false,-1); }
        Test(String name,String item,float pitch,boolean crouch) { this(name,item,pitch,crouch,-1); }
    }
    private enum Phase { PREP, CLOUD_SUMMON, CLOUD_SHOOT, CLOUD_DISMISS, CERA_CHARGE, CERA_OUTBOUND, CERA_RETURN, CERA_RECHARGE, CERA_RETHROW, DREAD_WAIT, DREAD_EFFECT, SOUL_MOUNT, SOUL_CLIMB, SOUL_REAR, SOUL_DISMOUNT, STORM_GUARD, STORM_EARNED, STORM_ATTACK, STORM_DRAIN, GAUNTLET_HOLD, GAUNTLET_RELEASE, GAUNTLET_VORTEX, BULWARK_ATTACK, BLOOD_ZERO, BLOOD_HIT_READY, BLOOD_HIT, BLOOD_CAST_READY, BLOOD_CAST, BLOOD_EXPIRE, MOLTEN_ZERO, MOLTEN_HIT_READY, MOLTEN_HIT, MOLTEN_CAST_READY, MOLTEN_CAST, MOLTEN_EXPIRE, RELIC_USE, ION_ZERO, ION_RECHARGE, ION_READY, ION_CORRIDOR, ION_FOLLOWUP, ION_BEAM, ION_EXPIRE, SOUL_ZERO, SOUL_HIT_READY, SOUL_HIT, SOUL_CAST_READY, SOUL_CAST, SOUL_EXPIRE, LANTERN_BASE, LANTERN_READY, LANTERN_ACTIVE, LANTERN_EXPIRE, TEMPEST_ZERO, TEMPEST_HIT_READY, TEMPEST_HIT, TEMPEST_CAST_READY, TEMPEST_CAST, TEMPEST_ACTIVE, TEMPEST_EXPIRE, DONE }

    private static final class Session {
        final Minecraft mc; final LocalPlayer player; final Path output;
        final int originalClientSelected; volatile int originalServerSelected;
        boolean fixtureSelectedPacketSent;
        final CameraType camera; final boolean gui, attackKey, useKey, shiftKey, flying, sprinting, fpmPresent, fpmEnabled;
        final Vec3 position, motion, origin; final float yaw, pitch, bodyYaw, headYaw;
        final List<Test> tests = new ArrayList<>(); final List<Map<String,Object>> cases = new ArrayList<>();
        final Map<KeyMapping,Boolean> movementKeys = new LinkedHashMap<>();
        final Map<String,Object> report = new LinkedHashMap<>(); final Map<BlockPos,BlockState> blocks = new LinkedHashMap<>();
        final Set<UUID> spawned = ConcurrentHashMap.newKeySet(); final Map<String,Integer> types = new ConcurrentHashMap<>();
        final Map<UUID,Player_Ceraunus_Entity> ceraProjectiles = new ConcurrentHashMap<>();
        final Map<UUID,Long> ceraSpawnTicks = new ConcurrentHashMap<>();
        final Set<String> ceraDiagnosticImages = new HashSet<>();
        final boolean forceFpmDisabled;
        final List<Map<String,Object>> images = new ArrayList<>();
        volatile boolean observing, finishing; volatile Mob target; volatile Vec3 targetPosition;
        volatile int cloudCount, cloudAtShoulder, charge, projectileCount, cloudCrystals, maxCloudCount; volatile boolean hitting, cloak, assault, anchor, returned, serverUse, hittingObserved;
        volatile float damage, lastHealth; volatile String anchorId = ""; volatile int serverUseTicks,serverCurrentUseTicks;
        volatile List<ItemStack> inventory; volatile List<MobEffectInstance> effects;
        volatile Vec3 serverPosition; volatile float health, absorption, saturation; volatile int food; volatile boolean serverFlying;
        volatile net.minecraft.world.level.GameType originalGameMode;
        volatile Mob chainTarget; volatile Vec3 chainPosition;
        volatile int stormCharges, earnedStormCharges; volatile boolean incomingHitRequested, incomingHitComplete, nativeHitReturned, stormCleanupReady;
        volatile float playerHealthBeforeParry, playerHealthAfterParry, chainHealthAfterParry, primaryHealthAfterParry;
        volatile com.github.L_Ender.cataclysm.entity.effect.Void_Vortex_Entity nativeVortex;
        volatile Vec3 nativeTargetInitial; volatile double maxTargetTravel, maxTargetInwardMotion, targetDistanceAtStart, minimumTargetDistance;
        volatile int vortexAge, vortexLifespan; volatile boolean vortexRemoved, gauntletCooldown;
        volatile double vortexInitialTargetDistance, minimumVortexTargetDistance, maxPreExplosionTargetTravel;
        volatile double vortexInitialHorizontalDistance, minimumVortexHorizontalDistance, maxVortexHorizontalApproach, maxVortexHorizontalInwardVelocity;
        volatile boolean nativeVortexPullObserved;
        volatile Map<String,Object> gauntletAim;
        boolean vortexOpeningCaptured, vortexPullCaptured, vortexClosingCaptured;
        Map<String,Object> nativeAttackHit; final java.util.Queue<Map<String,Object>> nativeDamageEvents=new java.util.concurrent.ConcurrentLinkedQueue<>();
        long bulwarkInactiveServerTick;
        volatile int originalDamageDealt;
        CompletableFuture<Void> stormTargetPreparation;
        volatile Map<String,Object> stormTargetPlacement=Map.of();
        int stormTargetReadySamples;
        Phase earnedHitGeometryPhase;
        int earnedHitGeometryAttempt, earnedHitGeometryStable, earnedHitGeometryWait;
        CompletableFuture<Void> earnedHitGeometryPlacement;
        final List<Map<String,Object>> earnedHitGeometryEvidence=new ArrayList<>();
        volatile long stormCleanupStart; boolean stormAttackSeen; int stormInactiveTicks;
        volatile Difficulty originalDifficulty; volatile boolean originalDifficultyLocked;
        volatile Map<String,Object> preparationServerSnapshot=Map.of(), preparationServerBeforeEquip=Map.of();
        final List<Map<String,Object>> preparationTimeline=new ArrayList<>();
        CompletableFuture<Void> prepared; Test test; volatile Phase phase; int index = -1, ticks, readyTicks, shotsAtRecall, renderViolations;
        ItemStack expected; Map<String,Object> row; String pendingCapture;
        boolean attackReleased, rejectAttempted; int maxUseTicks;
        volatile int serverSurfaceMode; volatile double serverStrideRise; volatile boolean serverStridePassenger;
        boolean sawNativeWall;
        volatile ItemStack bloodNativeStack;
        volatile int bloodFrenzy,bloodBleed,bloodMaxBleed,bloodRite=1,bloodWaveSpawns,bloodActiveStains;
        volatile boolean bloodSameStack,bloodPlague,bloodServerCrouch;
        volatile long bloodLatestStainExpiry,bloodFirstChargeTick;
        int bloodHits,bloodInitialRite,bloodEarnedFrenzy,bloodHitsBefore,bloodDamageBefore;
        long bloodLastHitServerTick,bloodInactiveServerTick,bloodCastServerTick;
        float bloodHealthBefore;
        boolean bloodUseIssued,bloodCastComplete;
        String bloodCompletionStatus,bloodCompletionDetail;
        final java.util.Queue<Map<String,Object>> bloodHitEvents=new java.util.concurrent.ConcurrentLinkedQueue<>();
        final List<Map<String,Object>> bloodTimeline=new ArrayList<>();
        volatile Map<String,Object> relicEvidence=Map.of();
        volatile ItemStack soulNativeStack;
        volatile boolean soulSameStack,soulMarked,soulMarkActive,soulCrouch;
        volatile int soulWeakness,soulSlowness;
        volatile boolean tempestBoth,tempestActive,tempestSourceOwner,tempestSeen;
        volatile int tempestDuration,tempestAmplifier;
        volatile Map<String,Object> tempestState=Map.of();
        boolean tempestCaptured; long tempestConsumedTick; int tempestAfterConsumedDamageIndex;
        volatile Map<String,Object> soulState=Map.of();
        volatile net.sweenus.simplyswords.entity.SoulkeeperLanternVisualEntity lanternVisual;
        volatile int lanternCount;
        boolean lanternFourSeen,lanternFourCaptured;
        int soulCastDamageIndex;float soulHealthBeforeCast,lanternHealthBeforeCast;
        long soulCastTick,lanternActivationTick;
        int lanternInitialEntityId;
        volatile ItemStack moltenNativeStack;
        volatile int moltenHeat; volatile boolean moltenVenting,moltenSeenVenting,moltenActive,moltenSameStack,moltenServerCrouch;
        int moltenEarnedHeat,moltenHeatBefore,moltenCastDamageIndex; boolean moltenVentCaptured;
        long moltenCastTick;
        volatile Map<String,Object> ionState=Map.of();
        volatile ItemStack ionNativeStack;
        volatile boolean ionSameStack=true;
        volatile long ionEquippedTick;
        volatile int ionCubes,ionLastCubes=-1;
        volatile float ionSlamDamage,ionBeamDamage,ionSecondaryLastHealth;
        volatile boolean ionSlamObserved,ionBeamObserved,ionSlowObserved,ionParalysisObserved;
        final java.util.Queue<Map<String,Object>> ionChargeTimeline=new java.util.concurrent.ConcurrentLinkedQueue<>();
        boolean ionUseIssued,ionFirstCubeCaptured,ionCorridorCaptured,ionBeamCaptured;
        long ionBeamStartedTick;

        Session(Minecraft mc, String label, String mode, String angles,boolean forceFpmDisabled) throws Exception {
            this.mc=mc; player=mc.player; output=mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label); Files.createDirectories(output);
            this.forceFpmDisabled=forceFpmDisabled;originalClientSelected=player.getInventory().selected;
            if (!Set.of("all","cloud","ceraunus","dreadtide","soulstalker","stormbringer","gauntlets","guard","maelstrom","bulwark_melee","bloodwake","relic_unattuned","ionbound","molten_edge","soulrender","soulkeeper","tempest").contains(mode)) throw new IllegalArgumentException("Mode must be all/cloud/ceraunus/dreadtide/soulstalker/stormbringer/gauntlets/guard/maelstrom/bulwark_melee/bloodwake/relic_unattuned/ionbound/molten_edge/soulrender/soulkeeper/tempest");
            float[] pitches = switch(angles.trim()) { case "both" -> new float[]{0,55}; case "pitch=0" -> new float[]{0}; case "pitch=55" -> new float[]{55}; default -> throw new IllegalArgumentException("Use both, pitch=0 or pitch=55"); };
            for (float angle : pitches) {
                if(mode.equals("soulrender")||mode.equals("soulkeeper")||mode.equals("tempest")) {tests.add(new Test(mode,"simplyswords:"+mode,angle,false));tests.add(new Test(mode,"simplyswords:"+mode,angle,true));}
                if(mode.equals("molten_edge")) {tests.add(new Test("molten_edge","simplyswords:molten_edge",angle,false));tests.add(new Test("molten_edge","simplyswords:molten_edge",angle,true));}
                if(mode.equals("ionbound")) {tests.add(new Test("ionbound","simplyswords:ionbound_stormscale",angle,false));tests.add(new Test("ionbound","simplyswords:ionbound_stormscale",angle,true));}
                if(mode.equals("bloodwake")) {tests.add(new Test("bloodwake","simplyswords:bloodwake",angle,false));tests.add(new Test("bloodwake","simplyswords:bloodwake",angle,true));}
                if(mode.equals("relic_unattuned")) for(String relic:List.of("dormant_relic","decaying_relic")) tests.add(new Test("relic_unattuned","simplyswords:"+relic,angle));
                if (mode.equals("all") || mode.equals("cloud")) tests.add(new Test("cloud", "aether:cloud_staff", angle));
                if (mode.equals("all") || mode.equals("ceraunus")) tests.add(new Test("ceraunus", "cataclysm:ceraunus", angle));
                if ((mode.equals("all") || mode.equals("dreadtide")) && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("simplyswords:dreadtide")))
                    tests.add(new Test("dreadtide", "simplyswords:dreadtide", angle));
                if (mode.equals("soulstalker")) tests.add(new Test("soulstalker", "simplyswords:soulstalker", angle));
                if(mode.equals("bulwark_melee"))for(int combo=0;combo<3;combo++)tests.add(new Test("bulwark_melee","cataclysm:bulwark_of_the_flame",angle,false,combo));
                for(String name:List.of("guard","maelstrom")) if(mode.equals("gauntlets")||mode.equals(name)) {
                    tests.add(new Test(name,"cataclysm:gauntlet_of_"+name,angle,false));tests.add(new Test(name,"cataclysm:gauntlet_of_"+name,angle,true));
                }
                if (mode.equals("stormbringer")) {
                    tests.add(new Test("stormbringer", "simplyswords:stormbringer", angle,false));
                    tests.add(new Test("stormbringer", "simplyswords:stormbringer", angle,true));
                }
            }
            camera=mc.options.getCameraType(); gui=mc.options.hideGui; attackKey=mc.options.keyAttack.isDown(); useKey=mc.options.keyUse.isDown(); shiftKey=mc.options.keyShift.isDown();
            for(KeyMapping key:List.of(mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyJump,mc.options.keySprint)) movementKeys.put(key,key.isDown());
            flying=player.getAbilities().flying; sprinting=player.isSprinting(); position=player.position(); motion=player.getDeltaMovement();
            yaw=player.getYRot(); pitch=player.getXRot(); bodyYaw=player.yBodyRot; headYaw=player.yHeadRot;
            origin=new Vec3(192.5,(mode.equals("stormbringer")||mode.equals("bulwark_melee")||mode.equals("bloodwake")||mode.equals("relic_unattuned")||mode.equals("ionbound")||mode.equals("molten_edge")||mode.equals("soulrender")||mode.equals("tempest")||mode.equals("soulkeeper"))?Math.max(96,mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,192,192)+32):mc.level.getMinBuildHeight()+13,192.5);
            if(mode.equals("bulwark_melee")||mode.equals("bloodwake")||mode.equals("molten_edge")||mode.equals("soulrender")||mode.equals("tempest"))movementKeys.put(net.bettercombat.client.Keybindings.feintKeyBinding,net.bettercombat.client.Keybindings.feintKeyBinding.isDown());
            fpmPresent=ModList.get().isLoaded("firstperson"); fpmEnabled=fpmPresent && Fpm.enabled();
            report.put("label",label); report.put("mode",mode); report.put("firstPersonInstalled",fpmPresent); report.put("plannedCases",tests.size());
            report.put("temporarilyDisabledFirstPerson",forceFpmDisabled&&fpmPresent);report.put("originalFirstPersonEnabled",fpmEnabled);
            report.put("cases",cases); report.put("captures",images); report.put("resourcePacks",mc.getResourcePackRepository().getSelectedIds());
            if(!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("simplyswords:dreadtide")))
                report.put("unregisteredSourceOnlyBranches",List.of(Map.of("id","simplyswords:dreadtide","status","NOT_REGISTERED","detail","Class exists in installed jar, but no runtime item/ItemsRegistry registration exists. Dreadwhisper is a different registered weapon and is covered by the exhaustive item matrix.")));
            report.put("scope","Dependent native cloud-staff and Ceraunus actions retain the same stack/entities. Only actually registered items are scheduled. Native Soulstalker mount/wall-climb is an explicit separate mode; no ability-manager activation or forged stored charge. Stormbringer is an explicit native guard/parry/earned-charge/chain follow-up mode using ordinary hostile damage and a native Better Combat stab.");
            report.put("limitations",List.of("This opt-in sequence supplements the exhaustive independent item matrix; it does not certify chance-based, on-kill, every awakening node, ally, biome or weather branches.",
                "Source-only unregistered Dreadtide is never scheduled. Bloodwake covers naturally earned first-charge/tier-one behavior, not every optional tuning node or all five rites. Fresh Relic controls only certify unformed rejection; routed native aliases are separate cases."));
        }

        ServerPlayer sp() { return Objects.requireNonNull(mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID())); }
        void setPhase(Phase next) { phase=next; ticks=0; earnedHitGeometryPhase=null; }
        void next() {
            if (++index >= tests.size()) { finish("CAPTURED","Inspect per-case assertions and screenshots"); return; }
            test=tests.get(index); row=new LinkedHashMap<>(); cases.add(row);
            row.put("combo",test.combo);row.put("crouch",test.crouch); row.put("ability",test.name); row.put("item",test.item); row.put("pitch",test.pitch); row.put("status","RUNNING");
            types.clear(); ceraProjectiles.clear();ceraSpawnTicks.clear();ceraDiagnosticImages.clear(); maxCloudCount=cloudCount=cloudAtShoulder=charge=projectileCount=cloudCrystals=serverUseTicks=serverCurrentUseTicks=renderViolations=maxUseTicks=0;
            damage=0; cloak=assault=anchor=returned=serverUse=hitting=hittingObserved=attackReleased=rejectAttempted=false; anchorId="";
            nativeAttackHit=null;nativeDamageEvents.clear();bulwarkInactiveServerTick=0;nativeVortex=null;nativeTargetInitial=null;maxTargetTravel=maxTargetInwardMotion=0;targetDistanceAtStart=minimumTargetDistance=0;
            vortexInitialTargetDistance=minimumVortexTargetDistance=-1;maxPreExplosionTargetTravel=0;nativeVortexPullObserved=false;
            vortexInitialHorizontalDistance=minimumVortexHorizontalDistance=-1;maxVortexHorizontalApproach=maxVortexHorizontalInwardVelocity=0;
            vortexAge=vortexLifespan=0;vortexRemoved=gauntletCooldown=vortexOpeningCaptured=vortexPullCaptured=vortexClosingCaptured=false;gauntletAim=Map.of();
            stormCharges=earnedStormCharges=stormInactiveTicks=0; incomingHitRequested=incomingHitComplete=nativeHitReturned=stormCleanupReady=stormAttackSeen=false; chainTarget=null;
            serverSurfaceMode=0; serverStrideRise=0; serverStridePassenger=sawNativeWall=false;
            bloodNativeStack=null;bloodFrenzy=bloodBleed=bloodMaxBleed=bloodWaveSpawns=bloodActiveStains=bloodHits=0;bloodRite=1;
            bloodSameStack=true;bloodPlague=bloodServerCrouch=bloodUseIssued=bloodCastComplete=false;bloodLatestStainExpiry=bloodFirstChargeTick=0;
            bloodHitEvents.clear();bloodTimeline.clear();relicEvidence=Map.of();earnedHitGeometryEvidence.clear();
            ionState=Map.of();ionNativeStack=null;ionSameStack=true;ionCubes=0;ionLastCubes=-1;ionSlamDamage=ionBeamDamage=0;
            ionUseIssued=ionFirstCubeCaptured=ionCorridorCaptured=ionBeamCaptured=ionSlamObserved=ionBeamObserved=ionSlowObserved=ionParalysisObserved=false;
            ionChargeTimeline.clear();ionBeamStartedTick=0;
            moltenNativeStack=null;moltenHeat=moltenEarnedHeat=moltenHeatBefore=moltenCastDamageIndex=0;
            moltenVenting=moltenSeenVenting=moltenActive=moltenServerCrouch=moltenVentCaptured=false;moltenSameStack=true;moltenCastTick=0;
            soulNativeStack=null;soulSameStack=true;soulMarked=soulMarkActive=soulCrouch=false;soulWeakness=soulSlowness=-1;soulState=Map.of();
            stormTargetPreparation=null;stormTargetPlacement=Map.of();stormTargetReadySamples=0;
            tempestBoth=tempestActive=tempestSourceOwner=tempestSeen=tempestCaptured=false;tempestDuration=0;tempestAmplifier=-1;tempestState=Map.of();tempestConsumedTick=0;tempestAfterConsumedDamageIndex=0;
            lanternVisual=null;lanternCount=0;lanternFourSeen=lanternFourCaptured=false;lanternInitialEntityId=-1;soulCastDamageIndex=0;soulCastTick=lanternActivationTick=0;
            readyTicks=0; setPhase(Phase.PREP); pendingCapture=null; observing=false;
            preparationServerSnapshot=Map.of();preparationServerBeforeEquip=Map.of();preparationTimeline.clear();fixtureSelectedPacketSent=false;
            row.put("preEquipClient",preparationPlayerEvidence(player));row.put("preparationTimeline",List.of());row.put("fixtureSelectedSlot",0);
            mc.options.keyUse.setDown(false); mc.options.keyAttack.setDown(false); mc.options.keyShift.setDown(false); mc.gameMode.releaseUsingItem(player);
            movementKeys.keySet().forEach(key->key.setDown(false));
            expected=BuiltInRegistries.ITEM.get(ResourceLocation.parse(test.item)).getDefaultInstance();
            if (expected.isEmpty()) throw new IllegalStateException("Unregistered fixture item " + test.item);
            if (test.item.startsWith("simplyswords:")) AwakeningApi.initializeFullyAwakened(expected);
            prepared=new CompletableFuture<>();
            mc.getSingleplayerServer().execute(() -> {
                try {
                    ServerPlayer sp=sp();
                    if (inventory==null) {
                        if (!clouds(sp).isEmpty()) throw new IllegalStateException("Existing cloud minions must expire before this isolated sequence");
                        if (BuiltInRegistries.ITEM.getKey(sp.getMainHandItem().getItem()).toString().equals("simplyswords:dreadtide")) throw new IllegalStateException("Existing wielded Dreadtide state is outside this fixture");
                        originalServerSelected=sp.getInventory().selected;
                        inventory=new ArrayList<>(); for(int n=0;n<sp.getInventory().getContainerSize();n++) inventory.add(sp.getInventory().getItem(n).copy());
                        effects=sp.getActiveEffects().stream().map(MobEffectInstance::new).toList(); serverPosition=sp.position();
                        health=sp.getHealth(); absorption=sp.getAbsorptionAmount(); food=sp.getFoodData().getFoodLevel(); saturation=sp.getFoodData().getSaturationLevel(); serverFlying=sp.getAbilities().flying;
                        originalDamageDealt=sp.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DAMAGE_DEALT));originalGameMode=sp.gameMode.getGameModeForPlayer(); originalDifficulty=sp.getServer().getWorldData().getDifficulty(); originalDifficultyLocked=sp.getServer().getWorldData().isDifficultyLocked();
                    }
                    preparationServerBeforeEquip=preparationPlayerEvidence(sp);
                    cleanup(sp); prepareArena(sp); sp.getServer().setDifficulty(Difficulty.NORMAL,true);
                    if(test.name.equals("stormbringer")||test.name.equals("bulwark_melee")||test.name.equals("bloodwake")||test.name.equals("relic_unattuned")||test.name.equals("ionbound")||test.name.equals("molten_edge")||test.name.equals("soulrender")||test.name.equals("tempest")||test.name.equals("soulkeeper")) {
                        sp.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                        for(var slot:List.of(net.minecraft.world.entity.EquipmentSlot.HEAD,net.minecraft.world.entity.EquipmentSlot.CHEST,net.minecraft.world.entity.EquipmentSlot.LEGS,net.minecraft.world.entity.EquipmentSlot.FEET)) sp.setItemSlot(slot,ItemStack.EMPTY);
                    }
                    sp.removeAllEffects(); sp.setAbsorptionAmount(0); sp.setHealth(sp.getMaxHealth()); sp.getFoodData().setFoodLevel(20);
                    sp.getAbilities().flying=false; sp.onUpdateAbilities(); sp.teleportTo(origin.x,origin.y,origin.z); sp.setYRot(180); sp.setXRot(test.pitch); sp.setDeltaMovement(Vec3.ZERO); sp.fallDistance=0;
                    ExpandedReactionTransition.displaced(sp);
                    sp.getInventory().selected=0;
                    sp.setItemInHand(InteractionHand.MAIN_HAND,expected.copy()); sp.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY); sp.getCooldowns().removeCooldown(expected.getItem());
                    sp.inventoryMenu.broadcastChanges(); if(test.name.equals("bloodwake"))bloodNativeStack=sp.getMainHandItem();
                    if(test.name.equals("molten_edge"))moltenNativeStack=sp.getMainHandItem();
                    if(test.name.equals("soulrender")||test.name.equals("tempest")||test.name.equals("soulkeeper"))soulNativeStack=sp.getMainHandItem();
                    if(test.name.equals("ionbound")) {ionNativeStack=sp.getMainHandItem();ionEquippedTick=sp.serverLevel().getGameTime();}
                    target=EntityType.ZOMBIE.create(sp.serverLevel()); if(target==null) throw new IllegalStateException("Zombie factory failed");
                    target.setNoAi(true); target.setPersistenceRequired(); target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024); target.setHealth(1024); lastHealth=1024;
                    target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,Items.LEATHER_HELMET.getDefaultInstance());
                    targetPosition=test.name.equals("tempest")?origin.add(0,0,-1.05):test.name.equals("soulkeeper")?origin.add(0,0,-net.sweenus.simplyswords.config.Config.uniqueEffects.soulkeeper.orbitRadius):test.name.equals("ionbound")?origin.add(.6,0,-4):(test.name.equals("bloodwake")||test.name.equals("molten_edge")||test.name.equals("soulrender")||test.name.equals("tempest"))?origin.add(0,0,test.pitch==0?-1.5:-1.05):test.name.equals("soulstalker")?origin.add(4,0,-4):origin.add(0,0,test.name.equals("stormbringer")?-1.05:test.name.equals("bulwark_melee")?(test.pitch==0?-2:-1.05):test.pitch==0?-4:-1.05); target.setPos(targetPosition); sp.serverLevel().addFreshEntity(target); spawned.add(target.getUUID());
                    if(test.name.equals("stormbringer")) {
                        chainTarget=EntityType.ZOMBIE.create(sp.serverLevel());
                        if(chainTarget==null) throw new IllegalStateException("Chain hostile factory failed");
                        chainTarget.setNoAi(true); chainTarget.setPersistenceRequired(); chainTarget.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024); chainTarget.setHealth(1024);
                        chainTarget.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); chainTarget.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,Items.LEATHER_HELMET.getDefaultInstance());
                        chainPosition=targetPosition.add(4.5,0,0);chainTarget.setPos(chainPosition);sp.serverLevel().addFreshEntity(chainTarget);spawned.add(chainTarget.getUUID());
                    }
                    if(test.name.equals("stormbringer")||test.name.equals("bulwark_melee")||test.name.equals("bloodwake")||test.name.equals("relic_unattuned")||test.name.equals("ionbound")||test.name.equals("molten_edge")||test.name.equals("soulrender")||test.name.equals("tempest")||test.name.equals("soulkeeper")) {
                        var others=sp.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(origin.add(-6,-1,-6),origin.add(6,6,6)),e->e!=sp && e!=target && e!=chainTarget);
                        if(!others.isEmpty())throw new IllegalStateException("Elevated isolated melee arena contains a non-fixture entity: "+others);
                    }
                    if(test.name.equals("ionbound")) {
                        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0);
                        chainTarget=EntityType.ZOMBIE.create(sp.serverLevel());if(chainTarget==null)throw new IllegalStateException("Native beam target factory failed");
                        chainTarget.setNoAi(true);chainTarget.setPersistenceRequired();chainTarget.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);chainTarget.setHealth(1024);
                        chainTarget.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,Items.LEATHER_HELMET.getDefaultInstance());
                        chainPosition=origin.add(0,0,test.pitch==0?-2.5:-1.0);chainTarget.setPos(chainPosition);
                        sp.serverLevel().addFreshEntity(chainTarget);spawned.add(chainTarget.getUUID());ionSecondaryLastHealth=1024;
                    }
                    if(test.name.equals("guard")||test.name.equals("maelstrom")) {
                        // NoAI disables LivingEntity.travel through isControlledByLocalInstance.
                        // Keep native physics enabled; zero navigation speed prevents self-propelled walking.
                        // No position/velocity writes are made after this initial fixture placement.
                        target.setNoAi(false);target.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
                    }
                    if(test.name.equals("guard")) {targetPosition=origin.add(0,0,-4);target.setPos(targetPosition);}
                    if(test.name.equals("maelstrom")) {
                        var hit=sp.pick(32,1,true);
                        if(!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) || hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK)
                            throw new IllegalStateException("Native Maelstrom prerequisite: real forward block ray missing");
                        var block=blockHit.getBlockPos();double platformY=test.pitch==0?origin.y+2:origin.y;
                        targetPosition=new Vec3(block.getX()+2.5,platformY,block.getZ()+.5);target.setPos(targetPosition);
                        gauntletAim=Map.of("nativeRayType",hit.getType().name(),"block",block.toShortString(),"location",hit.getLocation().toString(),"face",blockHit.getDirection().name(),"distance",hit.getLocation().distanceTo(sp.getEyePosition()),"targetPosition",targetPosition.toString());
                    }
                    nativeTargetInitial=target.position();targetDistanceAtStart=minimumTargetDistance=target.distanceTo(sp);
                    observing=true; prepared.complete(null);
                } catch(Throwable e) { prepared.completeExceptionally(e); }
            });
            write("RUNNING","");
        }

        void prepareArena(ServerPlayer sp) {
            BlockPos floor=BlockPos.containing(origin).below();
            for(int x=-10;x<=10;x++) for(int z=-10;z<=10;z++) for(int y=0;y<=7;y++) {
                BlockPos at=floor.offset(x,y,z); BlockState wanted=y==0?Blocks.BEDROCK.defaultBlockState():Blocks.AIR.defaultBlockState();
                blocks.putIfAbsent(at,sp.serverLevel().getBlockState(at)); if(!sp.serverLevel().getBlockState(at).equals(wanted)) sp.serverLevel().setBlock(at,wanted,3);
            }
            if(test.name.equals("maelstrom") && test.pitch==0) {
                // A real raised platform intersects the horizontal native ray and supports
                // the untethered target at the vortex's native surface height.
                for(int x=-3;x<=3;x++)for(int z=-6;z<=-4;z++)for(int y=1;y<=2;y++)
                    sp.serverLevel().setBlock(floor.offset(x,y,z),Blocks.BEDROCK.defaultBlockState(),3);
            }
            // The wall guarantees a normal projectile collision if the narrow target is missed.
            for(int x=-3;x<=3;x++) for(int y=1;y<=4;y++) {
                BlockPos at=floor.offset(x,y,-9); sp.serverLevel().setBlock(at,Blocks.BEDROCK.defaultBlockState(),3);
            }
        }

        void observe() {
            ServerPlayer sp=sp();
            if(phase==Phase.STORM_DRAIN) {
                ExpandedRagdollRecovery.release(sp);sp.stopRiding();sp.setDeltaMovement(Vec3.ZERO);sp.fallDistance=0;ExpandedReactionTransition.displaced(sp);
                if(sp.getServer().getTickCount()-stormCleanupStart>=15 && !ExpandedRagdollRecovery.pending(sp)) stormCleanupReady=true;
                return;
            }
            if(phase==Phase.PREP) {
                var snapshot=new LinkedHashMap<String,Object>(preparationPlayerEvidence(sp));
                snapshot.put("serverTick",sp.getServer().getTickCount());
                snapshot.put("target",preparationTargetEvidence(target));
                preparationServerSnapshot=snapshot;
            }
            var clouds=clouds(sp);
            serverStridePassenger=sp.getVehicle() instanceof SoulstalkerStrideEntity;
            if(sp.getVehicle() instanceof SoulstalkerStrideEntity stride) { serverSurfaceMode=stride.getSurfaceMode(); serverStrideRise=stride.getY()-origin.y; }
            cloudCount=(int)clouds.stream().filter(Entity::isAlive).count(); maxCloudCount=Math.max(maxCloudCount,cloudCount);
            cloudAtShoulder=(int)clouds.stream().filter(c->c.isAlive() && c.atShoulder()).count(); hitting=(Boolean)aetherRead(sp,"isHitting"); hittingObserved|=hitting;
            var held=sp.getMainHandItem();
            if(test.name.equals("soulrender")||test.name.equals("soulkeeper")) {
                soulSameStack &= held==soulNativeStack;soulCrouch=sp.isShiftKeyDown();
                if(test.name.equals("soulrender")) {
                    var weakness=target.getEffect(net.minecraft.world.effect.MobEffects.WEAKNESS);
                    var slowness=target.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);
                    soulWeakness=weakness==null?-1:weakness.getAmplifier();soulSlowness=slowness==null?-1:slowness.getAmplifier();soulMarked=weakness!=null && slowness!=null;
                    soulState=soulMarkState(sp,target);soulMarkActive=Boolean.TRUE.equals(soulState.get("activeMarkVisual"));
                }else {
                    var visuals=sp.serverLevel().getEntitiesOfClass(net.sweenus.simplyswords.entity.SoulkeeperLanternVisualEntity.class,sp.getBoundingBox().inflate(16),v->player.getUUID().equals(v.getOwnerUuid()));
                    if(visuals.size()>1){soulState=Map.of("error","More than one owner lantern visual", "count",visuals.size());}
                    else {
                        lanternVisual=visuals.isEmpty()?null:visuals.getFirst();lanternCount=lanternVisual==null?0:lanternVisual.getLanternCount();
                        soulState=Map.of("nativeLanternCount",lanternCount,"nativeVisualEntityId",lanternVisual==null?-1:lanternVisual.getId(),"nativeWorldTick",sp.serverLevel().getGameTime(),"sameServerStack",soulSameStack,"serverCrouch",soulCrouch,"targetHealth",target.getHealth());
                    }
                }
                sp.inventoryMenu.broadcastChanges();
            }
            if(test.name.equals("tempest")) {
                soulSameStack &= held==soulNativeStack;soulCrouch=sp.isShiftKeyDown();
                var fire=target.getEffect(EffectRegistry.getReference(EffectRegistry.FIRE_VORTEX));
                var frost=target.getEffect(EffectRegistry.getReference(EffectRegistry.FROST_VORTEX));
                var activeVortex=sp.getEffect(EffectRegistry.getReference(EffectRegistry.ELEMENTAL_VORTEX));
                tempestBoth=fire!=null && frost!=null;tempestActive=activeVortex!=null;
                tempestDuration=activeVortex==null?0:activeVortex.getDuration();tempestAmplifier=activeVortex==null?-1:activeVortex.getAmplifier();
                tempestSourceOwner=activeVortex instanceof net.sweenus.simplyswords.effect.instance.SimplySwordsStatusEffectInstance nativeEffect && nativeEffect.getSourceEntity()==sp;
                if(tempestActive)tempestSeen=true;
                var state=new LinkedHashMap<String,Object>();state.put("fireAmplifier",fire==null?-1:fire.getAmplifier());state.put("fireDuration",fire==null?0:fire.getDuration());
                state.put("frostAmplifier",frost==null?-1:frost.getAmplifier());state.put("frostDuration",frost==null?0:frost.getDuration());state.put("bothNativeMarks",tempestBoth);
                state.put("elementalVortex",tempestActive);state.put("vortexDuration",tempestDuration);state.put("vortexAmplifier",tempestAmplifier);state.put("nativeSourceIsPlayer",tempestSourceOwner);
                state.put("sameServerStack",soulSameStack);state.put("serverCrouch",soulCrouch);state.put("targetHealth",target.getHealth());state.put("nativeWorldTick",sp.serverLevel().getGameTime());
                tempestState=state;sp.inventoryMenu.broadcastChanges();
            }
            if(test.name.equals("ionbound")) {
                ionSameStack &= held==ionNativeStack;ionCubes=IonboundStormscaleAbilityManager.getCubes(held).cubes();
                ionState=ionNativeState(sp,held,ionEquippedTick);
                if(ionCubes!=ionLastCubes) {ionChargeTimeline.add(ionState);ionLastCubes=ionCubes;}
                boolean slammed=Boolean.TRUE.equals(ionState.get("slammed")),beam=Boolean.TRUE.equals(ionState.get("beam"));
                ionSlamObserved|=slammed;ionBeamObserved|=beam;ionSlowObserved|=Boolean.TRUE.equals(ionState.get("movementSlow"));
                if(target!=null && slammed && !beam)ionSlamDamage=Math.max(ionSlamDamage,1024-target.getHealth());
                if(chainTarget!=null) {
                    if(beam && chainTarget.getHealth()<ionSecondaryLastHealth)ionBeamDamage+=ionSecondaryLastHealth-chainTarget.getHealth();
                    ionSecondaryLastHealth=chainTarget.getHealth();
                    ionParalysisObserved|=chainTarget.hasEffect(EffectRegistry.getReference(EffectRegistry.ION_PARALYSIS));
                }
                sp.inventoryMenu.broadcastChanges();
            }
            if(test.name.equals("molten_edge")) {
                moltenSameStack &= held==moltenNativeStack;
                moltenHeat=net.sweenus.simplyswords.world.MoltenEdgeAbilityManager.getHeat(sp,held);
                moltenVenting=net.sweenus.simplyswords.world.MoltenEdgeAbilityManager.isVenting(sp,held);
                moltenSeenVenting|=moltenVenting;moltenActive=net.sweenus.simplyswords.world.MoltenEdgeAbilityManager.hasActive(sp.serverLevel());
                moltenServerCrouch=sp.isShiftKeyDown();sp.inventoryMenu.broadcastChanges();
            }
            if(test.name.equals("bloodwake")) {
                bloodSameStack &= held==bloodNativeStack;
                bloodFrenzy=net.sweenus.simplyswords.world.BloodwakeAbilityManager.getFrenzy(held);
                bloodRite=selectedBloodRite(held);bloodServerCrouch=sp.isShiftKeyDown();
                if(target!=null) {bloodBleed=net.sweenus.simplyswords.util.BleedHelper.getStacks(target);bloodMaxBleed=Math.max(bloodMaxBleed,bloodBleed);
                    bloodPlague|=target.hasEffect(EffectRegistry.getReference(EffectRegistry.BLOOD_PLAGUE));}
                if(bloodFrenzy>0 && bloodFirstChargeTick==0)bloodFirstChargeTick=sp.getServer().getTickCount();
                var stains=bloodStains(sp);bloodActiveStains=((Number)stains.get("active")).intValue();bloodLatestStainExpiry=((Number)stains.get("latestExpiry")).longValue();
                sp.inventoryMenu.broadcastChanges();
            }
            if(test.name.equals("relic_unattuned")) {
                var route=AwakeningApi.getFormRoute(held);var standard=sp.blockPosition().above(4).relative(sp.getMotionDirection(),3);
                relicEvidence=Map.of("nativeItem",BuiltInRegistries.ITEM.getKey(held.getItem()).toString(),"level",AwakeningApi.getLevel(held),"route",route.map(Object::toString).orElse(""),"standardPosition",standard.toShortString(),"standardPositionAir",sp.serverLevel().getBlockState(standard).isAir(),"nativeCooldown",sp.getCooldowns().isOnCooldown(held.getItem()),"serverUsing",sp.isUsingItem());
            }
            if(test.name.equals("stormbringer")) {
                stormCharges=held.getOrDefault(ComponentTypeRegistry.PARRY.get(),net.sweenus.simplyswords.item.component.ParryComponent.DEFAULT).stormCharges();
                if(phase==Phase.STORM_GUARD && sp.isUsingItem() && sp.getTicksUsingItem()>=6 && !incomingHitRequested) {
                    incomingHitRequested=true;playerHealthBeforeParry=sp.getHealth();
                    // Ordinary hostile melee invokes the installed damage hooks. Never call the parry manager or write PARRY.
                    nativeHitReturned=target.doHurtTarget(sp);playerHealthAfterParry=sp.getHealth();
                    earnedStormCharges=held.getOrDefault(ComponentTypeRegistry.PARRY.get(),net.sweenus.simplyswords.item.component.ParryComponent.DEFAULT).stormCharges();
                    stormCharges=earnedStormCharges;primaryHealthAfterParry=target.getHealth();chainHealthAfterParry=chainTarget.getHealth();
                    sp.inventoryMenu.broadcastChanges();incomingHitComplete=true;
                }
                if(chainTarget!=null && chainTarget.isAlive()) {chainTarget.setPos(chainPosition);chainTarget.setDeltaMovement(Vec3.ZERO);}
            }
            UUID anchorUuid=held.get(ModDataComponents.THROWN_ANCHOR); anchor=anchorUuid!=null;
            if(anchorUuid!=null) { anchorId=anchorUuid.toString(); Entity e=sp.serverLevel().getEntity(anchorUuid); if(e instanceof Player_Ceraunus_Entity c) returned|=c.getReturn(); }
            // Native tick can set Return and discard within the same tick when a close
            // downward hit is already within its 3-block return radius. Retain the
            // actual spawned instance so that transition is still observed after removal.
            for(var projectile:ceraProjectiles.values()) returned|=projectile.getReturn();
            charge=held.getOrDefault(ComponentTypeRegistry.STORED_BONUS.get(),StoredChargeComponent.DEFAULT).charge();
            cloak=sp.hasEffect(EffectRegistry.getReference(EffectRegistry.VOIDCLOAK));
            serverUse|=sp.isUsingItem(); serverCurrentUseTicks=sp.getTicksUsingItem(); serverUseTicks=Math.max(serverUseTicks,serverCurrentUseTicks);
            if(test.name.equals("maelstrom") && sp.isUsingItem()) {
                var hit=sp.pick(32,1,true);
                if(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit)
                    gauntletAim=Map.of("nativeRayType",hit.getType().name(),"block",blockHit.getBlockPos().toShortString(),"location",hit.getLocation().toString(),"face",blockHit.getDirection().name(),"distance",hit.getLocation().distanceTo(sp.getEyePosition()),"serverCrouch",sp.isShiftKeyDown(),"serverPitch",sp.getXRot());
            }
            if(nativeVortex!=null){vortexAge=nativeVortex.tickCount;vortexLifespan=nativeVortex.getLifespan();vortexRemoved=nativeVortex.isRemoved();}
            gauntletCooldown|=sp.getCooldowns().isOnCooldown(held.getItem());
            Mob mob=target;
            if(mob!=null) {
                assault|=mob.hasEffect(EffectRegistry.getReference(EffectRegistry.VOIDASSAULT));
                if(mob.getHealth()<lastHealth) damage+=lastHealth-mob.getHealth(); lastHealth=mob.getHealth();
                if(mob.isAlive()) {
                    if(test.name.equals("guard")||test.name.equals("maelstrom")||test.name.equals("ionbound")) {
                        if(nativeTargetInitial!=null) maxTargetTravel=Math.max(maxTargetTravel,mob.position().distanceTo(nativeTargetInitial));
                        minimumTargetDistance=Math.min(minimumTargetDistance,mob.distanceTo(sp));
                        Vec3 pullTo=test.name.equals("guard")?sp.position():nativeVortex==null?mob.position():nativeVortex.position();
                        maxTargetInwardMotion=Math.max(maxTargetInwardMotion,mob.getDeltaMovement().dot(pullTo.subtract(mob.position()).normalize()));
                        if(test.name.equals("maelstrom") && nativeVortex!=null && !nativeVortex.isRemoved()) {
                            if(vortexInitialTargetDistance<0) {
                                vortexInitialTargetDistance=nativeTargetInitial.distanceTo(nativeVortex.position());
                                minimumVortexTargetDistance=vortexInitialTargetDistance;
                                vortexInitialHorizontalDistance=nativeTargetInitial.subtract(nativeVortex.position()).horizontalDistance();
                                minimumVortexHorizontalDistance=vortexInitialHorizontalDistance;
                            }
                            // Measure the native pull before closing/explosion. Post-explosion knockback is not pull evidence.
                            if(vortexAge>=16 && vortexLifespan>=16) {
                                minimumVortexTargetDistance=Math.min(minimumVortexTargetDistance,mob.position().distanceTo(nativeVortex.position()));
                                maxPreExplosionTargetTravel=Math.max(maxPreExplosionTargetTravel,mob.position().distanceTo(nativeTargetInitial));
                                // The grounded target may be 9 blocks above the vortex. Its floor blocks
                                // vertical travel: a complete 2-block horizontal approach then reduces
                                // 3D distance by at most sqrt(85)-9=0.2195, not 0.4.
                                // Require real pre-explosion approach along the native horizontal
                                // pull direction, positive inward velocity, and positive 3D approach.
                                Vec3 initialHorizontal=nativeVortex.position().subtract(nativeTargetInitial).multiply(1,0,1).normalize();
                                Vec3 currentHorizontal=nativeVortex.position().subtract(mob.position()).multiply(1,0,1);
                                minimumVortexHorizontalDistance=Math.min(minimumVortexHorizontalDistance,currentHorizontal.length());
                                maxVortexHorizontalApproach=Math.max(maxVortexHorizontalApproach,mob.position().subtract(nativeTargetInitial).dot(initialHorizontal));
                                maxVortexHorizontalInwardVelocity=Math.max(maxVortexHorizontalInwardVelocity,mob.getDeltaMovement().dot(currentHorizontal.normalize()));
                                nativeVortexPullObserved|=vortexInitialHorizontalDistance-minimumVortexHorizontalDistance>.4
                                        && maxVortexHorizontalApproach>.4 && maxVortexHorizontalInwardVelocity>1.0e-4
                                        && vortexInitialTargetDistance-minimumVortexTargetDistance>.01 && maxPreExplosionTargetTravel>.4;
                            }
                        }
                    } else { mob.setPos(targetPosition); mob.setDeltaMovement(Vec3.ZERO); }
                }
            }
        }

        Map<String,Object> bulwarkTargets() { return nativeTargets(test.combo); }
        /** Prepare a real, grounded hostile inside the native attack volume, only between attacks. */
        boolean earnedHitTargetReady() { return nativeHitTargetReady(0); }
        boolean nativeHitTargetReady(int combo) {
            if(earnedHitGeometryPhase!=phase) {
                earnedHitGeometryPhase=phase;earnedHitGeometryAttempt=earnedHitGeometryStable=earnedHitGeometryWait=0;
                earnedHitGeometryPlacement=null;
            }
            if(target==null || !target.isAlive())throw new IllegalStateException("Earned-hit target is absent/dead");
            if(earnedHitGeometryPlacement!=null) {
                if(earnedHitGeometryPlacement.isCompletedExceptionally())earnedHitGeometryPlacement.join();
                if(!earnedHitGeometryPlacement.isDone())return false;
            }
            var clientTarget=mc.level.getEntity(target.getId());
            if(clientTarget==null || clientTarget.position().distanceTo(targetPosition)>.05)return false;
            var targeting=nativeTargets(combo);
            targeting.put("requestedCombo",combo);
            boolean selected=((List<?>)targeting.get("nativeSelectedTargets")).stream().anyMatch(e->Boolean.TRUE.equals(((Map<?,?>)e).get("designated")));
            if(selected) {
                if(++earnedHitGeometryStable<3)return false;
                var evidence=new LinkedHashMap<String,Object>();evidence.put("phase",phase.name());evidence.put("hit",bloodHits+1);
                evidence.put("result","NATIVE_SELECTED_BEFORE_ATTACK");evidence.put("placementAttempts",earnedHitGeometryAttempt);
                evidence.put("clientTarget",clientTarget.position().toString());evidence.put("serverTarget",target.position().toString());evidence.put("nativeTargeting",targeting);
                earnedHitGeometryEvidence.add(evidence);row.put("nativeEarnedHitGeometry",List.copyOf(earnedHitGeometryEvidence));
                return true;
            }
            earnedHitGeometryStable=0;
            if(++earnedHitGeometryWait<5)return false;
            earnedHitGeometryWait=0;
            // Grounded, non-overlapping placements are bounded by the real native weapon range.
            // A scalar distance alone is insufficient for the pitch-rotated OBB/radial filters.
            double[] distances=test.pitch==0?new double[]{1.5,1.2,.9,.75,1.8,2.1}:new double[]{.75,.9,1.2,1.5,1.8,2.1};
            if(earnedHitGeometryAttempt>=distances.length)throw new IllegalStateException("Native target finder rejected every bounded grounded fixture placement: "+targeting);
            double distance=distances[earnedHitGeometryAttempt++];
            var evidence=new LinkedHashMap<String,Object>();evidence.put("phase",phase.name());evidence.put("hit",bloodHits+1);evidence.put("result","REJECTED_BEFORE_ATTACK");evidence.put("nativeTargeting",targeting);evidence.put("nextGroundedDistance",distance);
            earnedHitGeometryEvidence.add(evidence);row.put("nativeEarnedHitGeometry",List.copyOf(earnedHitGeometryEvidence));
            earnedHitGeometryPlacement=new CompletableFuture<>();
            var placement=earnedHitGeometryPlacement;
            mc.getSingleplayerServer().execute(()->{
                try {
                    var actor=mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID());
                    if(actor==null || actor.isPassenger() || !actor.onGround() || target==null || !target.isAlive())throw new IllegalStateException("Grounded native actor/target changed during earned-hit preparation");
                    Vec3 forward=Vec3.directionFromRotation(0,actor.getYRot());
                    targetPosition=actor.position().add(forward.scale(distance));target.setPos(targetPosition);target.setDeltaMovement(Vec3.ZERO);
                    placement.complete(null);
                }catch(Throwable failure){placement.completeExceptionally(failure);}
            });
            return false;
        }
        Map<String,Object> nativeTargets(int combo) {
            var hand=net.bettercombat.logic.PlayerAttackHelper.getCurrentAttack(player,combo);
            if(hand==null)throw new IllegalStateException("Native combo unavailable");
            double range=net.bettercombat.logic.PlayerAttackHelper.getRangeForItem(player,hand.itemStack())*hand.attack().rangeMultiplier();
            var result=net.bettercombat.client.collision.TargetFinder.findAttackTargetResult(player,mc.crosshairPickEntity,hand.attack(),range);
            var initial=net.bettercombat.client.collision.TargetFinder.getInitialTargets(player,mc.crosshairPickEntity,range);
            var map=new LinkedHashMap<String,Object>();map.put("nativeRange",range);map.put("angle",hand.attack().angle());map.put("hitbox",hand.attack().hitbox().name());
            map.put("origin",net.bettercombat.client.collision.TargetFinder.getInitialTracingPoint(player).toString());map.put("clientPitch",player.getXRot());map.put("clientYaw",player.getYRot());
            map.put("initialTargets",initial.stream().map(e->Map.of("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),"uuid",e.getUUID().toString(),"bounds",e.getBoundingBox().toString())).toList());
            map.put("nativeSelectedTargets",result.entities.stream().map(e->Map.of("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),"uuid",e.getUUID().toString(),"designated",target!=null&&e.getUUID().equals(target.getUUID()),"secondary",chainTarget!=null&&e.getUUID().equals(chainTarget.getUUID()))).toList());
            map.put("cursor",mc.crosshairPickEntity==null?"":mc.crosshairPickEntity.getUUID().toString());map.put("rayHitType",mc.hitResult==null?"":mc.hitResult.getType().name());return map;
        }
        Map<String,Object> gauntletEvidence() {
            var result=new LinkedHashMap<String,Object>();result.put("targetInitial",String.valueOf(nativeTargetInitial));
            result.put("targetCurrent",target==null?"":target.position().toString());result.put("targetDistanceAtStart",targetDistanceAtStart);
            result.put("minimumTargetDistance",minimumTargetDistance);result.put("maxTargetTravel",maxTargetTravel);result.put("maxNativeInwardVelocity",maxTargetInwardMotion);
            result.put("nativeTargetNoAi",target!=null && target.isNoAi());result.put("nativeTargetMovementSpeed",target==null?0:target.getAttributeValue(Attributes.MOVEMENT_SPEED));
            result.put("vortexInitialTargetDistance",vortexInitialTargetDistance);result.put("minimumVortexTargetDistance",minimumVortexTargetDistance);
            result.put("maxPreExplosionTargetTravel",maxPreExplosionTargetTravel);result.put("nativeVortexPullBeforeExplosion",nativeVortexPullObserved);
            result.put("vortexInitialHorizontalDistance",vortexInitialHorizontalDistance);result.put("minimumVortexHorizontalDistance",minimumVortexHorizontalDistance);
            result.put("maxVortexHorizontalApproach",maxVortexHorizontalApproach);result.put("maxVortexHorizontalInwardVelocity",maxVortexHorizontalInwardVelocity);
            result.put("vortexPullMeasurementWindow","Native age>=16 and remaining lifespan>=16; excludes explosion/closing. Positive horizontal approach, inward velocity, and 3D approach on an initially stationary grounded target; no target movement writes.");
            result.put("nativeRay",gauntletAim);result.put("nativeVortexSpawned",nativeVortex!=null);result.put("nativeVortexAge",vortexAge);
            result.put("nativeVortexLifespan",vortexLifespan);result.put("nativeVortexRemoved",vortexRemoved);result.put("nativeCooldownObserved",gauntletCooldown);
            var clientTarget=target==null?null:mc.level.getEntity(target.getId());result.put("clientTargetPosition",clientTarget==null?"":clientTarget.position().toString());return result;
        }
        Map<String,Object> moltenEvidence() {
            return Map.of("heat",moltenHeat,"earnedHeat",moltenEarnedHeat,"venting",moltenVenting,"nativeManagerActive",moltenActive,"sameServerStack",moltenSameStack,"crouch",moltenServerCrouch,"nativeHits",bloodHits,"clientHeat",net.sweenus.simplyswords.world.MoltenEdgeAbilityManager.getHeat(player,player.getMainHandItem()));
        }
        /** Read-only snapshots, collected only at five bounded preparation milestones. */
        Map<String,Object> preparationPlayerEvidence(net.minecraft.world.entity.player.Player actor) {
            var value=new LinkedHashMap<String,Object>();
            value.put("position",actor.position().toString());value.put("velocity",actor.getDeltaMovement().toString());
            value.put("onGround",actor.onGround());value.put("passenger",actor.isPassenger());
            value.put("vehicle",actor.getVehicle()==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(actor.getVehicle().getType()).toString());
            value.put("selectedSlot",actor.getInventory().selected);
            value.put("heldItem",BuiltInRegistries.ITEM.getKey(actor.getMainHandItem().getItem()).toString());
            value.put("usingItem",actor.isUsingItem());value.put("alive",actor.isAlive());
            value.put("scale",actor.getScale());value.put("flying",actor.getAbilities().flying);
            value.put("dimension",actor.level().dimension().location().toString());
            return value;
        }
        Map<String,Object> preparationTargetEvidence(Entity entity) {
            if(entity==null)return Map.of("present",false);
            return Map.of("present",true,"id",entity.getId(),"alive",entity.isAlive(),"removed",entity.isRemoved(),
                "position",entity.position().toString(),"uuid",entity.getUUID().toString());
        }
        void capturePreparationEvidence() {
            var snapshot=new LinkedHashMap<String,Object>();snapshot.put("readyTicks",readyTicks);
            snapshot.put("futureDone",prepared.isDone());snapshot.put("futureExceptional",prepared.isCompletedExceptionally());
            snapshot.put("client",preparationPlayerEvidence(player));snapshot.put("server",preparationServerSnapshot);
            snapshot.put("serverBeforeEquip",preparationServerBeforeEquip);snapshot.put("expectedItem",BuiltInRegistries.ITEM.getKey(expected.getItem()).toString());
            snapshot.put("clientHasExpectedItem",player.getMainHandItem().is(expected.getItem()));
            snapshot.put("clientTarget",preparationTargetEvidence(target==null?null:mc.level.getEntity(target.getId())));
            snapshot.put("nativeWorldReadiness",NativeWorldReadiness.evidence());preparationTimeline.add(snapshot);
            // Each completed row retains its own snapshots when the next case clears the working list.
            row.put("preparationTimeline",List.copyOf(preparationTimeline));
        }
        void tick() {
            mc.options.hideGui=false;
            if(fpmPresent) {if(forceFpmDisabled)Fpm.restore(false);else Fpm.enable();} player.getAbilities().flying=false; player.setSprinting(false);
            // A native Sable seat owns its camera and orientation. Forcing FIRST_PERSON
            // while useFirstPersonCamera=false is an unsupported fixture combination.
            // The ordinary Soulstalker mount is not a Sable seat and retains its explicit views.
            if(ExpandedRagdollRecovery.clientRestored(player)) {
                mc.options.setCameraType(phase==Phase.SOUL_REAR || phase==Phase.SOUL_DISMOUNT?CameraType.THIRD_PERSON_BACK:CameraType.FIRST_PERSON);
                player.setYRot(180); player.yRotO=180; player.yBodyRot=player.yBodyRotO=180; player.yHeadRot=player.yHeadRotO=180;
                player.setXRot(test.pitch); player.xRotO=test.pitch;
            }
            ticks++; maxUseTicks=Math.max(maxUseTicks,player.getTicksUsingItem());
            if(phase==Phase.PREP) {
                movementKeys.keySet().forEach(key->key.setDown(false));
                if(prepared.isCompletedExceptionally()) prepared.join();
                if(prepared.isDone() && !fixtureSelectedPacketSent) {
                    player.getInventory().selected=0;
                    player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(0));
                    fixtureSelectedPacketSent=true;
                }
                ++readyTicks;
                if(readyTicks==1 || readyTicks==20 || readyTicks==100 || readyTicks==200 || readyTicks==201)
                    capturePreparationEvidence();
                if(readyTicks>200) throw new IllegalStateException("Authoritative equipment/ground synchronization timed out");
                if(prepared.isDone() && (target==null || !target.isAlive() || target.isRemoved())) throw new IllegalStateException("Native target disappeared before action; difficulty="+mc.level.getDifficulty());
                if(!prepared.isDone() || readyTicks<16 || !fixtureSelectedPacketSent || player.getInventory().selected!=0
                        || !Integer.valueOf(0).equals(preparationServerSnapshot.get("selectedSlot"))
                        || !player.onGround() || player.isPassenger() || !player.getMainHandItem().is(expected.getItem())) return;
                if(mc.level.getEntity(target.getId())==null || !mc.level.getEntity(target.getId()).isAlive()) return;
                row.put("nativeWorldReadiness",NativeWorldReadiness.evidence());
                if(!NativeWorldReadiness.ready()) return;
                row.put("nativeGrounded",true); row.put("initialServerCharge",charge);
                row.put("nativeDifficulty",mc.level.getDifficulty().name()); row.put("targetHealthAtStart",target.getHealth());
                if(test.name.equals("cloud")) { use(false); setPhase(Phase.CLOUD_SUMMON); }
                else if(test.name.equals("ceraunus")) { use(false); setPhase(Phase.CERA_CHARGE); }
                else if(test.name.equals("soulstalker")) { use(false); setPhase(Phase.SOUL_MOUNT); }
                else if(test.name.equals("tempest")) {
                    if(tempestBoth || tempestActive)throw new IllegalStateException("Fresh Tempest fixture already has native marks/vortex");
                    row.put("initialTempestState",tempestState);row.put("configuredNativeVortexDuration",net.sweenus.simplyswords.config.Config.uniqueEffects.tempest.duration);
                    bloodUseIssued=false;mc.options.keyShift.setDown(test.crouch);player.setShiftKeyDown(test.crouch);setPhase(Phase.TEMPEST_ZERO);
                }
                else if(test.name.equals("soulrender")) {
                    if(soulMarked || soulMarkActive)throw new IllegalStateException("Fresh Soulrender target already marked");
                    row.put("nativeMarkChance",net.sweenus.simplyswords.config.Config.uniqueEffects.soulrender.chance);row.put("initialSoulState",soulEvidence());
                    mc.options.keyShift.setDown(test.crouch);player.setShiftKeyDown(test.crouch);setPhase(Phase.SOUL_ZERO);
                }
                else if(test.name.equals("soulkeeper")) {
                    mc.options.keyShift.setDown(test.crouch);player.setShiftKeyDown(test.crouch);setPhase(Phase.LANTERN_BASE);
                }
                else if(test.name.equals("ionbound")) {
                    if(ionCubes!=0)throw new IllegalStateException("Fresh Ionbound already contains a cube before zero-charge control");
                    row.put("nativeRechargeInterval",net.sweenus.simplyswords.config.Config.uniqueEffects.ionbound_stormscale.rechargeInterval);
                    row.put("initialIonState",ionState);mc.options.keyShift.setDown(test.crouch);player.setShiftKeyDown(test.crouch);setPhase(Phase.ION_ZERO);
                }
                else if(test.name.equals("molten_edge")) {
                    row.put("nativeGameMode",mc.gameMode.getPlayerMode().name());row.put("initialHeat",moltenHeat);
                    if(moltenHeat!=0 || moltenActive || moltenVenting)throw new IllegalStateException("Molten fixture must begin with fresh zero heat and no preexisting native Molten manager");
                    mc.options.keyShift.setDown(test.crouch);player.setShiftKeyDown(test.crouch);setPhase(Phase.MOLTEN_ZERO);
                }
                else if(test.name.equals("bloodwake")) {
                    row.put("nativeGameMode",mc.gameMode.getPlayerMode().name());row.put("initialFrenzy",bloodFrenzy);row.put("initialBleed",bloodBleed);
                    if(bloodFrenzy!=0 || bloodBleed!=0)throw new IllegalStateException("Fresh Bloodwake fixture already charged or target bleeding");
                    bloodInitialRite=bloodRite;mc.options.keyShift.setDown(test.crouch);player.setShiftKeyDown(test.crouch);setPhase(Phase.BLOOD_ZERO);
                }
                else if(test.name.equals("relic_unattuned")) {
                    row.put("beforeNativeUse",relicEvidence);use(false);setPhase(Phase.RELIC_USE);
                }
                else if(test.name.equals("bulwark_melee")) {
                    if(player.getAttackStrengthScale(0)<1 || ExpandedCombatActions.attackActive(player) || !nativeHitTargetReady(test.combo)) return;
                    row.put("preAttackTargetSelection",bulwarkTargets());row.put("nativeFeintInput",net.bettercombat.client.Keybindings.feintKeyBinding.isDown());
                    var swing=ExpandedCombatActions.startAttack(mc,test.combo);row.put("nativeFollowupAnimation",swing.attackHand().attack().animation());
                    row.put("nativeDuration",swing.duration());setPhase(Phase.BULWARK_ATTACK);
                }
                else if(test.name.equals("guard")||test.name.equals("maelstrom")) {
                    row.put("nativeRay",gauntletAim);row.put("preUsePhysicsControl",gauntletEvidence());
                    if(target==null || target.isNoAi() || target.getAttributeValue(Attributes.MOVEMENT_SPEED)!=0 || maxTargetTravel>.05)
                        throw new IllegalStateException("Native gauntlet physics target was not stationary before use: "+gauntletEvidence());
                    use(test.crouch);setPhase(Phase.GAUNTLET_HOLD);
                }
                else if(test.name.equals("stormbringer")) {
                    row.put("nativeGameMode",mc.gameMode.getPlayerMode().name());row.put("initialStormCharges",stormCharges);
                    if(stormCharges!=0) throw new IllegalStateException("Fresh native stack unexpectedly contains charges");
                    use(test.crouch);setPhase(Phase.STORM_GUARD);
                }
                else setPhase(Phase.DREAD_WAIT);
                return;
            }
            switch(phase) {
                case CLOUD_SUMMON -> {
                    if(cloudCount==2 && cloudAtShoulder==2 && ticks>=12) { pendingCapture="summoned"; row.put("summonedMinions",cloudCount); attackInput(true); KeyMapping.click(mc.options.keyAttack.getKey()); setPhase(Phase.CLOUD_SHOOT); }
                    else if(ticks>120) fail(new IllegalStateException("Two native minions did not reach shoulders"));
                }
                case CLOUD_SHOOT -> {
                    if(ticks>=3 && !attackReleased) { attackInput(false); attackReleased=true; }
                    if(cloudCrystals>=2 && ticks>=12) {
                        pendingCapture="fired"; row.put("cloudCrystals",cloudCrystals);
                        mc.options.keyShift.setDown(true); player.setShiftKeyDown(true); setPhase(Phase.CLOUD_DISMISS);
                    } else if(ticks>100) fail(new IllegalStateException("Native attack input did not spawn both cloud crystals"));
                }
                case CLOUD_DISMISS -> {
                    // Let the normal player command packet deliver crouch before the use packet.
                    if(ticks==3) use(true);
                    if(cloudCount==0 && ticks>=12) { pendingCapture="dismissed"; complete("PASS","Summon, actual input-triggered firing and crouch dismissal observed"); }
                    else if(ticks>100) fail(new IllegalStateException("Native crouch use did not dismiss minions"));
                }
                case CERA_CHARGE, CERA_RECHARGE -> {
                    if(ticks==20) pendingCapture=phase==Phase.CERA_CHARGE?"charged":"recharged";
                    if(ticks>=28 && player.isUsingItem() && serverCurrentUseTicks>=28) { mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(player); setPhase(phase==Phase.CERA_CHARGE?Phase.CERA_OUTBOUND:Phase.CERA_RETHROW); }
                    else if(ticks>100) fail(new IllegalStateException("Native Ceraunus charge did not advance on both client and server"));
                }
                case CERA_OUTBOUND -> {
                    if(anchor && player.getMainHandItem().get(ModDataComponents.THROWN_ANCHOR)!=null && !rejectAttempted) {
                        row.put("firstAnchor",anchorId); shotsAtRecall=projectileCount;
                        row.put("repeatUseWhileThrown",mc.gameMode.useItem(player,InteractionHand.MAIN_HAND).toString()); rejectAttempted=true;
                    }
                    if((anchor || returned) && ticks>=2) { pendingCapture="outbound"; setPhase(Phase.CERA_RETURN); }
                    else if(ticks>100) fail(new IllegalStateException("Native thrown anchor was not observed"));
                }
                case CERA_RETURN -> {
                    if(returned && !anchor && player.getMainHandItem().get(ModDataComponents.THROWN_ANCHOR)==null && ticks>=8) {
                        row.put("nativeReturnObserved",true); row.put("anchorCleared",true); row.put("throwsBeforeRearm",projectileCount);
                        row.put("clientAnchorClearedBeforeRearm",true);
                        row.put("outboundRejectionCoverage",rejectAttempted?"Native client use attempted while synchronized anchor existed":"Return completed before a synchronized client frame; blocking branch is covered by the longer level throw");
                        if(projectileCount!=1) throw new IllegalStateException("A repeated use spawned an extra anchor while thrown: "+projectileCount);
                        pendingCapture="returned"; use(false); setPhase(Phase.CERA_RECHARGE);
                    } else if(ticks>200) fail(new IllegalStateException("Native collision/return did not clear thrown anchor"));
                }
                case CERA_RETHROW -> {
                    if(projectileCount>=2 && ticks>=8) { pendingCapture="thrown_again"; complete("PASS","Native charge, throw, return, component cleanup and second throw observed"); }
                    else if(ticks>120) fail(new IllegalStateException("Same stack could not throw after return"));
                }
                case SOUL_MOUNT -> {
                    if(player.getVehicle() instanceof SoulstalkerStrideEntity && serverStridePassenger && ticks>=8) {
                        pendingCapture="stride_mounted"; mc.options.keyUp.setDown(true); setPhase(Phase.SOUL_CLIMB);
                    } else if(ticks>120) fail(new IllegalStateException("Native Soulstalker use did not create and board Stride"));
                }
                case SOUL_CLIMB -> {
                    mc.options.keyUp.setDown(true);
                    if(player.getVehicle() instanceof SoulstalkerStrideEntity stride && stride.getSurfaceMode()==2 && serverSurfaceMode==2
                        && stride.getY()-origin.y>1 && serverStrideRise>1) {
                        sawNativeWall=true; pendingCapture="wall_climbing_firstperson"; setPhase(Phase.SOUL_REAR);
                    } else if(ticks>180) fail(new IllegalStateException("Native Stride did not enter wall mode and rise on both sides"));
                }
                case SOUL_REAR -> {
                    mc.options.keyUp.setDown(true);
                    if(ticks>=4) { pendingCapture="wall_climbing_rear"; mc.options.keyUp.setDown(false); mc.options.keyShift.setDown(true); player.setShiftKeyDown(true); setPhase(Phase.SOUL_DISMOUNT); }
                }
                case SOUL_DISMOUNT -> {
                    if(!player.isPassenger() && !serverStridePassenger && ticks>=8) {
                        pendingCapture="native_dismounted"; complete("PASS","Native use boarded Stride; forward input caused actual wall surface mode and rise on client/server; native crouch dismounted");
                    } else if(ticks>100) fail(new IllegalStateException("Native crouch did not dismount Stride"));
                }
                case ION_ZERO -> {
                    ionCheck();
                    if(!ionUseIssued && ticks>=4 && Boolean.TRUE.equals(ionState.get("crouch"))==test.crouch) {use(test.crouch);ionUseIssued=true;}
                    if(ionUseIssued && ticks>=16) {
                        row.put("zeroCubeUse",ionState);pendingCapture="zero_cube_rejection";
                        if(Boolean.TRUE.equals(ionState.get("corridor"))||Boolean.TRUE.equals(ionState.get("beam"))||ionCubes!=0)throw new IllegalStateException("Zero-cube native use created an ability or unexpectedly completed recharge");
                        setPhase(Phase.ION_RECHARGE);
                    }else if(ticks>80)throw new IllegalStateException("Ionbound crouch did not synchronize");
                }
                case ION_RECHARGE -> {
                    ionCheck();
                    if(ionCubes>=1 && !ionFirstCubeCaptured) {pendingCapture="first_naturally_earned_cube";ionFirstCubeCaptured=true;}
                    if(ionCubes==2 && IonboundStormscaleAbilityManager.getCubes(player.getMainHandItem()).cubes()==2 && !player.getCooldowns().isOnCooldown(player.getMainHandItem().getItem())) {
                        row.put("earnedCubes",ionState);row.put("nativeCubeTimeline",List.copyOf(ionChargeTimeline));pendingCapture="two_naturally_earned_cubes";
                        setPhase(Phase.ION_READY);
                    } else if(ticks>800)throw new IllegalStateException("Two cubes were not earned through ordinary held-stack ticks");
                }
                case ION_READY -> {
                    ionCheck();
                    if(ticks>=3) {use(test.crouch);setPhase(Phase.ION_CORRIDOR);}
                }
                case ION_CORRIDOR -> {
                    ionCheck();
                    if(Boolean.TRUE.equals(ionState.get("corridor")) && !ionCorridorCaptured) {pendingCapture="native_corridor_materializing";ionCorridorCaptured=true;}
                    if(ionSlamObserved) {
                        row.put("nativeSlam",ionEvidence());pendingCapture="native_corridor_slam";
                        if(ionCubes!=1 || ionSlamDamage<=0)throw new IllegalStateException("Native corridor did not consume one cube and damage its untethered target");
                        setPhase(Phase.ION_FOLLOWUP);
                    } else if(ticks>100)throw new IllegalStateException("Native corridor failed to reach its slam");
                }
                case ION_FOLLOWUP -> {
                    ionCheck();
                    if(ticks>=2 && IonboundStormscaleAbilityManager.getCubes(player.getMainHandItem()).cubes()==1 && !player.getCooldowns().isOnCooldown(player.getMainHandItem().getItem())) {
                        if(!Boolean.TRUE.equals(ionState.get("pendingFollowup")))throw new IllegalStateException("Native follow-up window expired before synchronized input");
                        row.put("beforeNativeFollowup",ionState);use(test.crouch);setPhase(Phase.ION_BEAM);
                    }else if(ticks>18)throw new IllegalStateException("Native one-cube follow-up did not synchronize within its real window");
                }
                case ION_BEAM -> {
                    ionCheck();
                    if(Boolean.TRUE.equals(ionState.get("beam"))) {
                        if(ionBeamStartedTick==0)ionBeamStartedTick=((Number)ionState.get("beamStartedAt")).longValue();
                        if(!ionBeamCaptured && ((Number)ionState.get("worldTick")).longValue()-ionBeamStartedTick>=8) {pendingCapture="native_followup_beam";ionBeamCaptured=true;row.put("activeNativeBeam",ionEvidence());}
                    }
                    if(ionBeamObserved && !Boolean.TRUE.equals(ionState.get("beam")) && ticks>=10) {
                        row.put("nativeBeamExpired",ionEvidence());row.put("nativeCubeTimeline",List.copyOf(ionChargeTimeline));pendingCapture="native_beam_expired";
                        boolean ownerSlamDamage=nativeDamageEvents.stream().anyMatch(e->Objects.equals(e.get("target"),target.getUUID().toString()) && ((Number)e.get("damage")).doubleValue()>0);
                        boolean ownerBeamDamage=nativeDamageEvents.stream().anyMatch(e->Objects.equals(e.get("target"),chainTarget.getUUID().toString()) && ((Number)e.get("damage")).doubleValue()>0);
                        row.put("ownerAttributedSlamDamage",ownerSlamDamage);row.put("ownerAttributedBeamDamage",ownerBeamDamage);
                        boolean passed=ionCubes==0 && ionSlamDamage>0 && ionBeamDamage>0 && ownerSlamDamage && ownerBeamDamage && ionParalysisObserved && ionSlowObserved && !Boolean.TRUE.equals(ionState.get("movementSlow")) && !Boolean.TRUE.equals(ionState.get("corridor"));
                        complete(passed?"PASS":"ACTION_FAILURE","Two cubes accrued through ordinary native held-stack ticks. Ordinary use consumed one for corridor/slam, then the real follow-up window accepted the second for a naturally expiring beam. Targets remained untethered; owner-attributed damage, status and movement-modifier cleanup are recorded.");
                    } else if(ticks>140)throw new IllegalStateException("Native follow-up beam did not start and naturally expire");
                }
                case ION_EXPIRE -> {
                    if(!Boolean.TRUE.equals(ionState.get("corridor")) && !Boolean.TRUE.equals(ionState.get("beam")) && !Boolean.TRUE.equals(ionState.get("movementSlow")) && ticks>=5) {
                        row.put("nativeAbilityStateExpired",true);row.put("nativeServerDamageEvents",List.copyOf(nativeDamageEvents));
                        stormCleanupStart=mc.getSingleplayerServer().getTickCount();stormCleanupReady=false;setPhase(Phase.STORM_DRAIN);
                    } else if(ticks>200)finish("RESTORE_FAILURE","Ionbound native ability state did not expire; manager state was not reset");
                }
                case RELIC_USE -> {
                    if(ticks>=20) {
                        row.put("afterNativeUse",relicEvidence);pendingCapture="fresh_unattuned_rejection";
                        boolean expected=Objects.equals(relicEvidence.get("level"),8) && Objects.equals(relicEvidence.get("route"),"") && Boolean.TRUE.equals(relicEvidence.get("standardPositionAir"));
                        boolean noStandard=types.keySet().stream().noneMatch(id->id.contains("battlestandard")) && !Boolean.TRUE.equals(relicEvidence.get("nativeCooldown")) && !serverUse;
                        complete(expected&&noStandard?"PASS":"ACTION_FAILURE","Fresh fully awakened stack has no native SUN/HARBINGER route; ordinary use rejects without a standard. No route/component was forged; this is a prerequisite control, not a test of an attuned active ability.");
                    }
                }
                case TEMPEST_ZERO -> {
                    tempestCheck();
                    if(!bloodUseIssued && ticks>=4 && soulCrouch==test.crouch){use(test.crouch);bloodUseIssued=true;}
                    if(bloodUseIssued && ticks>=16){
                        row.put("unmarkedUseControl",tempestState);pendingCapture="unmarked_vortex_rejection";
                        if(tempestBoth || tempestActive || damage>0 || gauntletCooldown)throw new IllegalStateException("Fresh unmarked Tempest unexpectedly activated");
                        mc.options.keyShift.setDown(false);player.setShiftKeyDown(false);setPhase(Phase.TEMPEST_HIT_READY);
                    }else if(ticks>100)throw new IllegalStateException("Tempest unmarked control did not synchronize");
                }
                case TEMPEST_HIT_READY -> {
                    tempestCheck();
                    if(ticks>=4 && !soulCrouch && !player.isPassenger() && player.onGround() && player.getAttackStrengthScale(0)>=1 && !ExpandedCombatActions.attackActive(player) && earnedHitTargetReady()){
                        if(++bloodHits>16){complete("UNMET_PRECONDITION","Sixteen actual native hits did not retain both randomly selected vortex marks; no effect or random seed was forged");break;}
                        bloodHealthBefore=target.getHealth();bloodHitsBefore=bloodHitEvents.size();bloodDamageBefore=nativeDamageEvents.size();bloodInactiveServerTick=0;stormAttackSeen=false;stormInactiveTicks=0;
                        var swing=ExpandedCombatActions.startAttack(mc,0);row.put("nativeMarkAnimation",swing.attackHand().attack().animation());setPhase(Phase.TEMPEST_HIT);
                    }else if(ticks>180)throw new IllegalStateException("Tempest native hit readiness failed");
                }
                case TEMPEST_HIT -> {
                    tempestCheck();boolean attacking=ExpandedCombatActions.attackActive(player);stormAttackSeen|=attacking;
                    if(ticks==3 && bloodHits==1)pendingCapture="native_vortex_mark_earning_hit";
                    if(attacking){stormInactiveTicks=0;bloodInactiveServerTick=0;}else{stormInactiveTicks++;if(bloodInactiveServerTick==0)bloodInactiveServerTick=mc.getSingleplayerServer().getTickCount();}
                    if(stormAttackSeen && stormInactiveTicks>=4 && mc.getSingleplayerServer().getTickCount()-bloodInactiveServerTick>=4){
                        var events=new ArrayList<>(bloodHitEvents);boolean selected=events.subList(Math.min(bloodHitsBefore,events.size()),events.size()).stream().anyMatch(e->((List<?>)e.get("targets")).stream().anyMatch(v->Boolean.TRUE.equals(((Map<?,?>)v).get("designated"))));
                        var damages=new ArrayList<>(nativeDamageEvents);var sample=new LinkedHashMap<String,Object>(tempestState);sample.put("hit",bloodHits);sample.put("nativeSelectedTarget",selected);sample.put("healthLoss",bloodHealthBefore-target.getHealth());sample.put("nativeDamageEvents",damages.subList(Math.min(bloodDamageBefore,damages.size()),damages.size()));bloodTimeline.add(sample);row.put("nativeMarkAttempts",List.copyOf(bloodTimeline));
                        if(!selected || bloodHealthBefore<=target.getHealth())throw new IllegalStateException("Actual Tempest hit did not select/damage retained target");
                        if(tempestBoth){pendingCapture="native_fire_and_frost_marks";mc.options.keyShift.setDown(test.crouch);player.setShiftKeyDown(test.crouch);setPhase(Phase.TEMPEST_CAST_READY);}
                        else setPhase(Phase.TEMPEST_HIT_READY);
                    }else if(ticks>160)throw new IllegalStateException("Native Tempest attack did not naturally finish");
                }
                case TEMPEST_CAST_READY -> {
                    tempestCheck();if(!tempestBoth)throw new IllegalStateException("Earned native vortex marks expired before activation");
                    if(ticks>=6 && soulCrouch==test.crouch && !player.getCooldowns().isOnCooldown(player.getMainHandItem().getItem())){
                        row.put("beforeNativeVortexUse",tempestState);soulCastTick=mc.getSingleplayerServer().getTickCount();use(test.crouch);setPhase(Phase.TEMPEST_CAST);
                    }else if(ticks>100)throw new IllegalStateException("Tempest native activation input did not synchronize");
                }
                case TEMPEST_CAST -> {
                    tempestCheck();
                    if(tempestActive && tempestSourceOwner && ((Number)tempestState.getOrDefault("fireDuration",-1)).intValue()==0 && ((Number)tempestState.getOrDefault("frostDuration",-1)).intValue()==0){
                        row.put("nativeMarksConsumedIntoVortex",tempestState);tempestConsumedTick=mc.getSingleplayerServer().getTickCount();tempestAfterConsumedDamageIndex=nativeDamageEvents.size();soulHealthBeforeCast=target.getHealth();pendingCapture="native_elemental_vortex_activated";setPhase(Phase.TEMPEST_ACTIVE);
                    }else if(ticks>80)throw new IllegalStateException("Native Tempest use did not consume both target marks into the owner-sourced vortex");
                }
                case TEMPEST_ACTIVE -> {
                    tempestCheck();
                    if(!tempestCaptured && ticks>=24){pendingCapture="native_elemental_vortex_damage";row.put("activeVortexState",tempestState);tempestCaptured=true;}
                    if(tempestActive && !tempestSourceOwner)throw new IllegalStateException("Native vortex source changed away from player");
                    if(tempestSeen && !tempestActive && ticks>=30){
                        var events=new ArrayList<>(nativeDamageEvents);var after=events.subList(Math.min(tempestAfterConsumedDamageIndex,events.size()),events.size());
                        boolean attributed=after.stream().anyMatch(e->Boolean.TRUE.equals(e.get("designated")) && "indirectMagic".equals(e.get("damageSource")) && ((Number)e.get("damage")).doubleValue()>0);
                        row.put("nativeVortexDamageEventsAfterMarkConsumption",after);row.put("expiredVortexState",tempestState);row.put("nativeVortexElapsedServerTicks",mc.getSingleplayerServer().getTickCount()-soulCastTick);pendingCapture="native_elemental_vortex_expired";
                        complete(attributed && target.getHealth()<soulHealthBeforeCast && tempestCaptured?"PASS":"ACTION_FAILURE","Actual native hits earned both random target marks; native use consumed them into the owner-sourced elemental vortex. Post-consumption indirect-magic damage and natural effect expiry were observed without injected marks, RNG or manager activation.");
                    }else if(ticks>Math.max(1800,net.sweenus.simplyswords.config.Config.uniqueEffects.tempest.duration+300))throw new IllegalStateException("Native elemental vortex did not naturally expire");
                }
                case TEMPEST_EXPIRE -> {
                    if(!tempestActive && ticks>=8){row.put("nativeElementalVortexExpired",true);stormCleanupStart=mc.getSingleplayerServer().getTickCount();stormCleanupReady=false;setPhase(Phase.STORM_DRAIN);}
                    else if(ticks>Math.max(1800,net.sweenus.simplyswords.config.Config.uniqueEffects.tempest.duration+300))finish("RESTORE_FAILURE","Native elemental vortex did not naturally expire; effect was not removed");
                }
                case SOUL_ZERO -> {
                    soulCheck();
                    if(!bloodUseIssued && ticks>=4 && soulCrouch==test.crouch) {use(test.crouch);bloodUseIssued=true;}
                    if(bloodUseIssued && ticks>=16) {
                        row.put("unmarkedUseControl",soulEvidence());pendingCapture="unmarked_use_rejection";
                        if(soulMarked || soulMarkActive || damage>0 || gauntletCooldown)throw new IllegalStateException("Unmarked Soulrender use unexpectedly activated");
                        mc.options.keyShift.setDown(false);player.setShiftKeyDown(false);setPhase(Phase.SOUL_HIT_READY);
                    }else if(ticks>100)throw new IllegalStateException("Soulrender crouch control did not synchronize");
                }
                case SOUL_HIT_READY -> {
                    soulCheck();
                    if(ticks>=4 && !soulCrouch && !player.isPassenger() && player.onGround() && player.getAttackStrengthScale(0)>=1 && !ExpandedCombatActions.attackActive(player) && earnedHitTargetReady()) {
                        if(++bloodHits>12){complete("UNMET_PRECONDITION","Twelve actual native hits did not produce the chance-based mark; no effect, chance or random seed was forged");break;}
                        bloodHealthBefore=target.getHealth();bloodHitsBefore=bloodHitEvents.size();bloodDamageBefore=nativeDamageEvents.size();
                        bloodLastHitServerTick=mc.getSingleplayerServer().getTickCount();bloodInactiveServerTick=0;stormAttackSeen=false;stormInactiveTicks=0;
                        var swing=ExpandedCombatActions.startAttack(mc,0);row.put("nativeMarkAnimation",swing.attackHand().attack().animation());setPhase(Phase.SOUL_HIT);
                    }else if(ticks>180)throw new IllegalStateException("Native Soulrender hit readiness failed");
                }
                case SOUL_HIT -> {
                    soulCheck();boolean attacking=ExpandedCombatActions.attackActive(player);stormAttackSeen|=attacking;
                    if(ticks==3 && bloodHits==1)pendingCapture="native_mark_earning_hit";
                    if(attacking){stormInactiveTicks=0;bloodInactiveServerTick=0;}else{stormInactiveTicks++;if(bloodInactiveServerTick==0)bloodInactiveServerTick=mc.getSingleplayerServer().getTickCount();}
                    if(stormAttackSeen && stormInactiveTicks>=4 && mc.getSingleplayerServer().getTickCount()-bloodInactiveServerTick>=4) {
                        var events=new ArrayList<>(bloodHitEvents);boolean selected=events.subList(Math.min(bloodHitsBefore,events.size()),events.size()).stream().anyMatch(e->((List<?>)e.get("targets")).stream().anyMatch(v->Boolean.TRUE.equals(((Map<?,?>)v).get("designated"))));
                        var damages=new ArrayList<>(nativeDamageEvents);var sample=new LinkedHashMap<String,Object>(soulEvidence());sample.put("hit",bloodHits);sample.put("nativeSelectedTarget",selected);sample.put("healthLoss",bloodHealthBefore-target.getHealth());sample.put("nativeDamageEvents",damages.subList(Math.min(bloodDamageBefore,damages.size()),damages.size()));bloodTimeline.add(sample);row.put("nativeMarkAttempts",List.copyOf(bloodTimeline));
                        if(!selected || bloodHealthBefore<=target.getHealth())throw new IllegalStateException("Actual Soulrender hit did not select/damage retained target");
                        if(soulMarked) {pendingCapture="naturally_marked_target";mc.options.keyShift.setDown(test.crouch);player.setShiftKeyDown(test.crouch);setPhase(Phase.SOUL_CAST_READY);}
                        else setPhase(Phase.SOUL_HIT_READY);
                    }else if(ticks>160)throw new IllegalStateException("Native Soulrender hit did not naturally finish");
                }
                case SOUL_CAST_READY -> {
                    soulCheck();
                    if(!soulMarked)throw new IllegalStateException("Earned mark expired before native reap");
                    if(ticks>=6 && soulCrouch==test.crouch && !player.getCooldowns().isOnCooldown(player.getMainHandItem().getItem())) {
                        soulHealthBeforeCast=target.getHealth();soulCastDamageIndex=nativeDamageEvents.size();soulCastTick=mc.getSingleplayerServer().getTickCount();row.put("beforeNativeReap",soulEvidence());use(test.crouch);setPhase(Phase.SOUL_CAST);
                    }else if(ticks>100)throw new IllegalStateException("Soulrender native reap input did not synchronize");
                }
                case SOUL_CAST -> {
                    soulCheck();if(ticks==4)pendingCapture="native_reap";
                    if(ticks>=25 && mc.getSingleplayerServer().getTickCount()-soulCastTick>=25 && !soulMarkActive) {
                        var events=new ArrayList<>(nativeDamageEvents);var after=events.subList(Math.min(soulCastDamageIndex,events.size()),events.size());
                        boolean attributed=after.stream().anyMatch(e->Boolean.TRUE.equals(e.get("designated")) && "indirectMagic".equals(e.get("damageSource")) && ((Number)e.get("damage")).doubleValue()>0);
                        row.put("nativeReapDamageEvents",after);row.put("afterNativeReap",soulEvidence());pendingCapture="native_reap_expired";
                        complete(!soulMarked && soulWeakness==-1 && soulSlowness==-1 && target.getHealth()<soulHealthBeforeCast && attributed?"PASS":"ACTION_FAILURE","Retained-target ordinary hits earned both native mark effects. Native use consumed them, applied owner-attributed indirect-magic reap damage, and let the native mark visual expire. Chance, tuning, effects and random seed were not fabricated.");
                    }else if(ticks>120)throw new IllegalStateException("Native Soulrender reap did not consume/expire its mark");
                }
                case SOUL_EXPIRE -> {
                    if(!soulMarkActive && ticks>=8) {row.put("nativeMarkVisualExpired",true);stormCleanupStart=mc.getSingleplayerServer().getTickCount();stormCleanupReady=false;setPhase(Phase.STORM_DRAIN);}
                    else if(ticks>650)finish("RESTORE_FAILURE","Native Soulrender mark did not naturally expire; manager was not reset");
                }
                case LANTERN_BASE -> {
                    soulCheck();
                    if(ticks>=8 && soulCrouch==test.crouch && lanternCount==2 && clientLanterns()==2) {
                        lanternInitialEntityId=lanternVisual.getId();row.put("passiveLanternBaseline",soulEvidence());pendingCapture="native_two_passive_lanterns";setPhase(Phase.LANTERN_READY);
                    }else if(ticks>100)throw new IllegalStateException("Native Soulkeeper baseline did not synchronize two lanterns");
                }
                case LANTERN_READY -> {
                    soulCheck();
                    if(ticks>=4){lanternHealthBeforeCast=target.getHealth();soulCastDamageIndex=nativeDamageEvents.size();lanternActivationTick=mc.getSingleplayerServer().getTickCount();use(test.crouch);setPhase(Phase.LANTERN_ACTIVE);}
                }
                case LANTERN_ACTIVE -> {
                    soulCheck();
                    if(lanternCount==4 && clientLanterns()==4) {
                        lanternFourSeen=true;
                        if(!lanternFourCaptured && ticks>=8){pendingCapture="native_four_active_lanterns";row.put("activeLanternState",soulEvidence());lanternFourCaptured=true;}
                    }
                    if(lanternFourSeen && lanternCount==2 && clientLanterns()==2 && mc.getSingleplayerServer().getTickCount()-lanternActivationTick>=20) {
                        var events=new ArrayList<>(nativeDamageEvents);var after=events.subList(Math.min(soulCastDamageIndex,events.size()),events.size());
                        boolean attributed=after.stream().anyMatch(e->Boolean.TRUE.equals(e.get("designated")) && ((Number)e.get("damage")).doubleValue()>0);
                        row.put("nativeLanternActionDamageEvents",after);row.put("nativeAmplificationElapsedServerTicks",mc.getSingleplayerServer().getTickCount()-lanternActivationTick);row.put("expiredLanternState",soulEvidence());pendingCapture="native_two_lanterns_restored";
                        complete(lanternVisual.getId()==lanternInitialEntityId && lanternFourCaptured && attributed && target.getHealth()<lanternHealthBeforeCast?"PASS":"ACTION_FAILURE","Native held-item baseline had two orbit lanterns; ordinary use changed the same visual to four on server and client, then naturally returned to two. Owner-attributed target damage occurred during the action; passive and amplified damage are not separately quantified.");
                    }else if(ticks>600)throw new IllegalStateException("Native Soulkeeper two-to-four-to-two lifecycle did not complete");
                }
                case LANTERN_EXPIRE -> {
                    if(lanternCount<=2 && ticks>=8) {row.put("nativeLanternAmplificationExpired",true);stormCleanupStart=mc.getSingleplayerServer().getTickCount();stormCleanupReady=false;setPhase(Phase.STORM_DRAIN);}
                    else if(ticks>600)finish("RESTORE_FAILURE","Native Soulkeeper amplification did not naturally expire; manager was not reset");
                }
                case MOLTEN_ZERO -> {
                    if(!bloodUseIssued && ticks>=4 && moltenServerCrouch==test.crouch) {use(test.crouch);bloodUseIssued=true;}
                    if(bloodUseIssued && ticks>=20) {
                        row.put("zeroHeatUse",moltenEvidence());pendingCapture="zero_heat_control";
                        if(moltenHeat!=0 || moltenVenting || moltenActive)throw new IllegalStateException("Zero-heat use unexpectedly activated native vent");
                        mc.options.keyShift.setDown(false);player.setShiftKeyDown(false);setPhase(Phase.MOLTEN_HIT_READY);
                    } else if(ticks>100)throw new IllegalStateException("Molten control crouch synchronization failed");
                }
                case MOLTEN_HIT_READY -> {
                    if(!moltenSameStack || target==null || !target.isAlive())throw new IllegalStateException("Molten accumulation lost its original held stack or target");
                    if(ticks>=4 && !moltenServerCrouch && !player.isPassenger() && player.onGround() && player.getAttackStrengthScale(0)>=1 && !ExpandedCombatActions.attackActive(player) && earnedHitTargetReady()) {
                        if(++bloodHits>20)throw new IllegalStateException("Native heat failed to reach30 after20 ordinary attacks");
                        bloodHealthBefore=target.getHealth();moltenHeatBefore=moltenHeat;bloodHitsBefore=bloodHitEvents.size();bloodDamageBefore=nativeDamageEvents.size();
                        bloodLastHitServerTick=mc.getSingleplayerServer().getTickCount();bloodInactiveServerTick=0;stormAttackSeen=false;stormInactiveTicks=0;
                        var swing=ExpandedCombatActions.startAttack(mc,0);row.put("nativeAccumulationAnimation",swing.attackHand().attack().animation());setPhase(Phase.MOLTEN_HIT);
                    } else if(ticks>180)throw new IllegalStateException("Native Molten hit readiness failed");
                }
                case MOLTEN_HIT -> {
                    boolean attacking=ExpandedCombatActions.attackActive(player);stormAttackSeen|=attacking;
                    if(ticks==3 && bloodHits==1)pendingCapture="native_heat_earning_hit";
                    if(attacking){stormInactiveTicks=0;bloodInactiveServerTick=0;}else{stormInactiveTicks++;if(bloodInactiveServerTick==0)bloodInactiveServerTick=mc.getSingleplayerServer().getTickCount();}
                    if(stormAttackSeen && stormInactiveTicks>=4 && mc.getSingleplayerServer().getTickCount()-bloodInactiveServerTick>=4) {
                        var events=new ArrayList<>(bloodHitEvents);boolean selected=events.subList(Math.min(bloodHitsBefore,events.size()),events.size()).stream().anyMatch(e->((List<?>)e.get("targets")).stream().anyMatch(v->Boolean.TRUE.equals(((Map<?,?>)v).get("designated"))));
                        var damages=new ArrayList<>(nativeDamageEvents);var sample=new LinkedHashMap<String,Object>();sample.put("hit",bloodHits);sample.put("nativeSelectedTarget",selected);sample.put("healthLoss",bloodHealthBefore-target.getHealth());sample.put("heatBefore",moltenHeatBefore);sample.put("heatAfter",moltenHeat);sample.put("sameServerStack",moltenSameStack);sample.put("nativeDamageEvents",damages.subList(Math.min(bloodDamageBefore,damages.size()),damages.size()));bloodTimeline.add(sample);row.put("nativeHeatAccumulation",List.copyOf(bloodTimeline));
                        if(!selected || bloodHealthBefore<=target.getHealth() || moltenHeat<=moltenHeatBefore || !moltenSameStack)throw new IllegalStateException("Native hit did not earn heat on the same actual stack: "+sample);
                        if(moltenHeat>=30) {
                            moltenEarnedHeat=moltenHeat;row.put("earnedNativeHeat",moltenEarnedHeat);row.put("nativeHitEvents",List.copyOf(bloodHitEvents));pendingCapture="native_heat_earned";
                            mc.options.keyShift.setDown(test.crouch);player.setShiftKeyDown(test.crouch);setPhase(Phase.MOLTEN_CAST_READY);
                        } else setPhase(Phase.MOLTEN_HIT_READY);
                    } else if(ticks>160)throw new IllegalStateException("Native Molten attack did not naturally end");
                }
                case MOLTEN_CAST_READY -> {
                    if(ticks>=5 && moltenServerCrouch==test.crouch && net.sweenus.simplyswords.world.MoltenEdgeAbilityManager.getHeat(player,player.getMainHandItem())==moltenEarnedHeat && !player.getCooldowns().isOnCooldown(player.getMainHandItem().getItem())) {
                        moltenCastTick=mc.getSingleplayerServer().getTickCount();moltenCastDamageIndex=nativeDamageEvents.size();use(test.crouch);setPhase(Phase.MOLTEN_CAST);
                    } else if(ticks>120)throw new IllegalStateException("Earned Molten heat/crouch/cooldown did not synchronize");
                }
                case MOLTEN_CAST -> {
                    if(moltenVenting && ticks>=2 && !moltenVentCaptured) {moltenVentCaptured=true;pendingCapture="native_heat_vent";row.put("nativeVentingObserved",moltenEvidence());}
                    if(moltenSeenVenting && !moltenVenting && !moltenActive && ticks>=20 && mc.getSingleplayerServer().getTickCount()-moltenCastTick>=20) {
                        var events=new ArrayList<>(nativeDamageEvents);var after=events.subList(Math.min(moltenCastDamageIndex,events.size()),events.size());
                        boolean shockwave=after.stream().anyMatch(e->Boolean.TRUE.equals(e.get("designated")) && "indirectMagic".equals(e.get("damageSource")) && ((Number)e.get("damage")).doubleValue()>0);
                        row.put("nativeVentDamageEvents",after);row.put("nativeShockwaveTargetDamage",shockwave);row.put("nativeVentingEnded",moltenEvidence());pendingCapture="native_vent_expired";
                        complete(moltenHeat<moltenEarnedHeat && moltenSameStack && shockwave && moltenVentCaptured?"PASS":"ACTION_FAILURE","Ordinary native hits earned heat on the same stack; normal use entered venting, drained heat and emitted its player-attributed indirect-magic shockwave, then native managers expired. No heat setters or direct manager activation/reset; optional tuning and rupture-while-venting are separate branches.");
                    } else if(ticks>240)throw new IllegalStateException("Native Molten vent did not activate or expire: "+moltenEvidence());
                }
                case MOLTEN_EXPIRE -> {
                    if(!moltenActive && !moltenVenting && ticks>=10) {row.put("nativeMoltenManagersExpired",true);stormCleanupStart=mc.getSingleplayerServer().getTickCount();stormCleanupReady=false;setPhase(Phase.STORM_DRAIN);}
                    else if(ticks>500)finish("RESTORE_FAILURE","Native Molten effects did not expire; manager state was not reset");
                }
                case BLOOD_ZERO -> {
                    if(!bloodUseIssued && ticks>=4 && bloodServerCrouch==test.crouch) {use(test.crouch);bloodUseIssued=true;}
                    if(bloodUseIssued && ticks>=20) {
                        row.put("zeroChargeUse",Map.of("crouch",test.crouch,"selectedRiteBefore",bloodInitialRite,"selectedRiteAfter",bloodRite,"frenzy",bloodFrenzy,"waves",bloodWaveSpawns));
                        pendingCapture="zero_charge_control";
                        if(bloodRite!=bloodInitialRite) {complete("UNMET_PRECONDITION","Installed optional rite-selector tuning changed the selected rite. This bounded fixture does not fabricate tuning or charges; the higher selected-rite branch needs a distinct earned-charge scenario.");break;}
                        if(bloodFrenzy!=0 || bloodWaveSpawns!=0)throw new IllegalStateException("Zero-charge native use unexpectedly created a rite");
                        mc.options.keyShift.setDown(false);player.setShiftKeyDown(false);setPhase(Phase.BLOOD_HIT_READY);
                    } else if(ticks>80)throw new IllegalStateException("Native crouch synchronization did not reach the Bloodwake control");
                }
                case BLOOD_HIT_READY -> {
                    if(!bloodSameStack || target==null || !target.isAlive())throw new IllegalStateException("Bloodwake accumulation lost its original native stack or living target");
                    if(ticks>=4 && !bloodServerCrouch && !player.isPassenger() && player.onGround() && player.getAttackStrengthScale(0)>=1 && !ExpandedCombatActions.attackActive(player) && earnedHitTargetReady()) {
                        if(++bloodHits>14)throw new IllegalStateException("No earned frenzy after 14 ordinary attacks");
                        bloodHealthBefore=target.getHealth();bloodHitsBefore=bloodHitEvents.size();bloodDamageBefore=nativeDamageEvents.size();
                        bloodLastHitServerTick=mc.getSingleplayerServer().getTickCount();bloodInactiveServerTick=0;stormAttackSeen=false;stormInactiveTicks=0;
                        var swing=ExpandedCombatActions.startAttack(mc,0);row.put("nativeAccumulationAnimation",swing.attackHand().attack().animation());setPhase(Phase.BLOOD_HIT);
                    } else if(ticks>180)throw new IllegalStateException("Native Bloodwake attack readiness failed between real hits");
                }
                case BLOOD_HIT -> {
                    boolean attacking=ExpandedCombatActions.attackActive(player);stormAttackSeen|=attacking;
                    if(ticks==3 && (bloodHits==1||bloodHits==10||bloodFrenzy>0))pendingCapture="native_hit_"+bloodHits;
                    if(attacking){stormInactiveTicks=0;bloodInactiveServerTick=0;}else{stormInactiveTicks++;if(bloodInactiveServerTick==0)bloodInactiveServerTick=mc.getSingleplayerServer().getTickCount();}
                    if(stormAttackSeen && stormInactiveTicks>=4 && mc.getSingleplayerServer().getTickCount()-bloodInactiveServerTick>=4) {
                        var eventList=new ArrayList<>(bloodHitEvents);boolean selected=eventList.subList(Math.min(bloodHitsBefore,eventList.size()),eventList.size()).stream().anyMatch(e->((List<?>)e.get("targets")).stream().anyMatch(v->Boolean.TRUE.equals(((Map<?,?>)v).get("designated"))));
                        var damageList=new ArrayList<>(nativeDamageEvents);var sample=new LinkedHashMap<String,Object>();sample.put("hit",bloodHits);sample.put("nativeSelectedTarget",selected);sample.put("healthLoss",bloodHealthBefore-target.getHealth());sample.put("bleed",bloodBleed);sample.put("maxBleed",bloodMaxBleed);sample.put("frenzy",bloodFrenzy);sample.put("sameServerStack",bloodSameStack);sample.put("elapsedServerTicks",mc.getSingleplayerServer().getTickCount()-bloodLastHitServerTick);sample.put("nativeDamageEvents",damageList.subList(Math.min(bloodDamageBefore,damageList.size()),damageList.size()));bloodTimeline.add(sample);row.put("nativeAccumulation",List.copyOf(bloodTimeline));
                        if(!selected || bloodHealthBefore<=target.getHealth() || !bloodSameStack)throw new IllegalStateException("Actual Bloodwake hit did not select/damage its designated target: "+sample);
                        if(bloodFrenzy>0) {
                            bloodEarnedFrenzy=bloodFrenzy;row.put("earnedFrenzy",bloodEarnedFrenzy);row.put("maximumNativeBleed",bloodMaxBleed);row.put("nativeHitEvents",List.copyOf(bloodHitEvents));pendingCapture="earned_frenzy";
                            if(bloodMaxBleed<10 || bloodEarnedFrenzy!=1)throw new IllegalStateException("Current tuning differs from the native 10-bleed then one-charge branch");
                            mc.options.keyShift.setDown(test.crouch);player.setShiftKeyDown(test.crouch);bloodUseIssued=false;setPhase(Phase.BLOOD_CAST_READY);
                        } else setPhase(Phase.BLOOD_HIT_READY);
                    } else if(ticks>150)throw new IllegalStateException("Native Bloodwake accumulation animation did not naturally end");
                }
                case BLOOD_CAST_READY -> {
                    if(ticks>=5 && bloodServerCrouch==test.crouch && net.sweenus.simplyswords.world.BloodwakeAbilityManager.getFrenzy(player.getMainHandItem())==bloodEarnedFrenzy && !player.getCooldowns().isOnCooldown(player.getMainHandItem().getItem())) {
                        bloodCastServerTick=mc.getSingleplayerServer().getTickCount();use(test.crouch);setPhase(Phase.BLOOD_CAST);
                    } else if(ticks>100)throw new IllegalStateException("Native earned charge/crouch/cooldown did not synchronize before use");
                }
                case BLOOD_CAST -> {
                    if(ticks==4)pendingCapture="native_earned_rite";
                    if(ticks>=30 && mc.getSingleplayerServer().getTickCount()-bloodCastServerTick>=30) {
                        row.put("nativeRiteEvidence",Map.of("earnedFrenzy",bloodEarnedFrenzy,"remainingFrenzy",bloodFrenzy,"selectedRite",bloodRite,"waveSpawns",bloodWaveSpawns,"targetBloodPlague",bloodPlague,"sameServerStack",bloodSameStack,"crouch",test.crouch));
                        pendingCapture="native_rite_recovery";
                        complete(bloodFrenzy==0 && bloodWaveSpawns>0 && bloodPlague && bloodSameStack?"PASS":"ACTION_FAILURE","Native hits accumulated ten bleed stacks; the next ordinary hit earned frenzy; native use consumed that earned charge and its actual blood wave applied Blood Plague. Optional selector tuning and higher rites are separate branches.");
                    }
                }
                case BLOOD_EXPIRE -> {
                    if(ticks%100==0){row.put("nativeStainsRemaining",bloodActiveStains);write("RUNNING","");}
                    if(bloodActiveStains==0 && ticks>=10) {
                        row.put("nativeStainsExpired",true);row.put("stainWaitClientTicks",ticks);row.put("nativeDamageEvents",List.copyOf(nativeDamageEvents));
                        stormCleanupStart=mc.getSingleplayerServer().getTickCount();stormCleanupReady=false;setPhase(Phase.STORM_DRAIN);
                    } else if(ticks>1000)finish("RESTORE_FAILURE","Native Bloodwake stains did not expire; manager state was not reset");
                }
                case BULWARK_ATTACK -> {
                    boolean activeAttack=ExpandedCombatActions.attackActive(player);stormAttackSeen|=activeAttack;
                    if(ticks==3){pendingCapture="active_combo_"+test.combo;row.put("activeTargetSelection",bulwarkTargets());}
                    if(activeAttack){stormInactiveTicks=0;bulwarkInactiveServerTick=0;}
                    else {stormInactiveTicks++;if(bulwarkInactiveServerTick==0)bulwarkInactiveServerTick=mc.getSingleplayerServer().getTickCount();}
                    if(stormAttackSeen && stormInactiveTicks>=8 && mc.getSingleplayerServer().getTickCount()-bulwarkInactiveServerTick>=8) {
                        row.put("nativeAttackHitEvent",nativeAttackHit);row.put("nativeServerDamageEvents",List.copyOf(nativeDamageEvents));row.put("nativeAttackCompleted",true);
                        pendingCapture="expired_combo_"+test.combo;
                        boolean designatedHit=nativeAttackHit!=null && ((List<?>)nativeAttackHit.get("targets")).stream().anyMatch(v->Boolean.TRUE.equals(((Map<?,?>)v).get("designated")));
                        row.put("designatedTargetSelectedAtNativeHit",designatedHit);
                        complete(designatedHit && damage>0?"PASS":"ACTION_UNCONFIRMED","Native target selection, actual ATTACK_HIT targets and authoritative damage attribution recorded through full animation plus eight server ticks");
                    } else if(ticks>160)throw new IllegalStateException("Native Bulwark melee did not finish");
                }
                case GAUNTLET_HOLD -> {
                    if(ticks==12) pendingCapture="native_held_ability";
                    if(ticks>=35 && player.isUsingItem() && serverCurrentUseTicks>=30) {
                        if(test.name.equals("guard")) {
                            row.put("heldPullEvidence",gauntletEvidence());pendingCapture="native_pull";
                            if(targetDistanceAtStart-minimumTargetDistance<.75 || maxTargetInwardMotion<=.001)
                                throw new IllegalStateException("Untethered hostile did not move toward native Guard user: "+gauntletEvidence());
                        }
                        mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(player);
                        setPhase(test.name.equals("guard")?Phase.GAUNTLET_RELEASE:Phase.GAUNTLET_VORTEX);
                    } else if(ticks>120) throw new IllegalStateException("Native gauntlet held use did not progress on both sides");
                }
                case GAUNTLET_RELEASE -> {
                    if(ticks>=12 && !player.isUsingItem() && serverCurrentUseTicks==0) {
                        pendingCapture="native_release";row.put("finalPullEvidence",gauntletEvidence());
                        complete("PASS","Native held Guard pulled an untethered hostile through ordinary physics; native release stopped use");
                    } else if(ticks>100) throw new IllegalStateException("Native Guard release did not end held use");
                }
                case GAUNTLET_VORTEX -> {
                    if(nativeVortex!=null && vortexAge>=3 && !vortexOpeningCaptured) {vortexOpeningCaptured=true;pendingCapture="native_vortex_open";}
                    if(vortexAge>=40 && !vortexPullCaptured) {vortexPullCaptured=true;pendingCapture="native_vortex_pull";row.put("vortexPullEvidence",gauntletEvidence());}
                    if(vortexLifespan<=16 && vortexAge>100 && !vortexClosingCaptured) {vortexClosingCaptured=true;pendingCapture="native_vortex_close";}
                    if(vortexRemoved && ticks>=155) {
                        pendingCapture="native_vortex_expired";row.put("finalVortexEvidence",gauntletEvidence());
                        complete(gauntletCooldown && vortexAge>=149 && nativeVortexPullObserved && maxTargetInwardMotion>.001?"PASS":"ACTION_FAILURE",
                            "Native block-ray release spawned its owned vortex; inward target travel was measured before closing/explosion, then natural lifespan/expiry followed. No target tether, direct movement or manager activation.");
                    } else if(ticks>220) throw new IllegalStateException("Native Maelstrom vortex lifecycle missing/incomplete: "+gauntletEvidence());
                }
                case STORM_GUARD -> {
                    if(ticks==2) pendingCapture="native_guard";
                    if(incomingHitComplete) {
                        mc.options.keyUse.setDown(false);
                        if(earnedStormCharges<=0 || playerHealthAfterParry<playerHealthBeforeParry) throw new IllegalStateException("Ordinary hostile damage did not produce a native successful parry");
                        row.put("ordinaryHostileAttack",true);row.put("nativeHostileHitReturned",nativeHitReturned);
                        row.put("playerHealthBeforeParry",playerHealthBeforeParry);row.put("playerHealthAfterParry",playerHealthAfterParry);
                        row.put("earnedStormCharges",earnedStormCharges);row.put("primaryHealthAfterCounterattack",primaryHealthAfterParry);row.put("chainHealthAfterCounterattack",chainHealthAfterParry);
                        pendingCapture="earned_charge";setPhase(Phase.STORM_EARNED);
                    } else if(ticks>70) throw new IllegalStateException("Native Stormbringer guard never admitted an actual incoming hostile hit");
                }
                case STORM_EARNED -> {
                    mc.options.keyUse.setDown(false);
                    if(ticks>=24 && !player.isUsingItem() && !player.isPassenger() && player.onGround() && player.getAttackStrengthScale(0)>=1 && !ExpandedCombatActions.attackActive(player)) {
                        int combo=ExpandedCombatActions.comboFor(player,"bettercombat:one_handed_stab");
                        if(stormTargetPreparation==null) {
                            row.put("nativeTargetingAfterParryBeforePlacement",nativeTargets(combo));
                            stormTargetPreparation=new CompletableFuture<>();
                            mc.getSingleplayerServer().execute(()->{
                                try {
                                    ServerPlayer sp=sp();var actor=sp.position();var forward=sp.getLookAngle().multiply(1,0,1).normalize();
                                    // The parry naturally recoils the actor. Place fixture targets relative to the
                                    // recovered actor before the next independent attack; never move the actor or force a hit.
                                    double spacing=test.pitch==0?2.4:.85;targetPosition=actor.add(forward.scale(spacing));chainPosition=targetPosition.add(4.5,0,0);
                                    target.setPos(targetPosition);target.setDeltaMovement(Vec3.ZERO);chainTarget.setPos(chainPosition);chainTarget.setDeltaMovement(Vec3.ZERO);
                                    stormTargetPlacement=Map.of("serverPlayerPosition",actor.toString(),"serverPitch",sp.getXRot(),"serverYaw",sp.getYRot(),"primaryPosition",targetPosition.toString(),"secondaryPosition",chainPosition.toString(),"spacing",spacing);
                                    stormTargetPreparation.complete(null);
                                }catch(Throwable error){stormTargetPreparation.completeExceptionally(error);}
                            });
                        }else {
                            if(stormTargetPreparation.isCompletedExceptionally())stormTargetPreparation.join();
                            var clientPrimary=mc.level.getEntity(target.getId());var clientSecondary=mc.level.getEntity(chainTarget.getId());
                            boolean synchronizedTargets=stormTargetPreparation.isDone() && clientPrimary!=null && clientSecondary!=null && clientPrimary.position().distanceTo(targetPosition)<.05 && clientSecondary.position().distanceTo(chainPosition)<.05;
                            if(synchronizedTargets) {
                                var targeting=nativeTargets(combo);row.put("nativeFollowupTargeting",targeting);row.put("authoritativeFollowupTargetPlacement",stormTargetPlacement);
                                var selected=(List<?>)targeting.get("nativeSelectedTargets");
                                boolean primary=selected.stream().anyMatch(e->Boolean.TRUE.equals(((Map<?,?>)e).get("designated")));
                                boolean secondary=selected.stream().anyMatch(e->Boolean.TRUE.equals(((Map<?,?>)e).get("secondary")));
                                stormTargetReadySamples=primary&&!secondary?stormTargetReadySamples+1:0;
                                double meleeRange=((Number)targeting.get("nativeRange")).doubleValue();double secondaryDistance=player.position().distanceTo(clientSecondary.position());
                                row.put("nativeMeleeRange",meleeRange);row.put("primaryDistanceBeforeChain",player.position().distanceTo(clientPrimary.position()));row.put("secondaryDistanceBeforeChain",secondaryDistance);
                                if(secondaryDistance-chainTarget.getBbWidth()/2<=meleeRange)throw new IllegalStateException("Secondary chain target overlaps native melee reach");
                                if(stormTargetReadySamples>=3) {
                                    row.put("nativeTargetReadySamples",stormTargetReadySamples);soulCastDamageIndex=nativeDamageEvents.size();nativeAttackHit=null;
                                    var swing=ExpandedCombatActions.startAttack(mc,combo);row.put("nativeFollowupAnimation",swing.attackHand().attack().animation());row.put("nativeFollowupCombo",combo);setPhase(Phase.STORM_ATTACK);
                                }
                            }
                        }
                        if(phase==Phase.STORM_EARNED && ticks>140)throw new IllegalStateException("Recovered Stormbringer fixture never selected primary and excluded secondary in native TargetFinder: "+row.get("nativeFollowupTargeting"));
                    } else if(ticks>140) throw new IllegalStateException("Native parry recovery did not leave player ready for an ordinary follow-up melee attack; vehicle="+player.getVehicle());
                }
                case STORM_ATTACK -> {
                    boolean activeAttack=ExpandedCombatActions.attackActive(player);stormAttackSeen|=activeAttack;
                    if(ticks==5) pendingCapture="chain_attack";
                    if(activeAttack)stormInactiveTicks=0;else stormInactiveTicks++;
                    if(stormAttackSeen && stormInactiveTicks>=4) {
                        float primaryFollowup=primaryHealthAfterParry-target.getHealth(), chainFollowup=chainHealthAfterParry-chainTarget.getHealth();
                        row.put("nativeChainPrimaryDamage",primaryFollowup);row.put("nativeChainSecondaryDamage",chainFollowup);row.put("stormChargesAfterFollowup",stormCharges);
                        row.put("nativeAttackCompleted",true);pendingCapture="chain_expired";
                        var events=new ArrayList<>(nativeDamageEvents);var after=events.subList(Math.min(soulCastDamageIndex,events.size()),events.size());
                        boolean primaryHit=nativeAttackHit!=null && ((List<?>)nativeAttackHit.get("targets")).stream().anyMatch(e->Boolean.TRUE.equals(((Map<?,?>)e).get("designated")));
                        boolean attributedPrimary=after.stream().anyMatch(e->Boolean.TRUE.equals(e.get("designated")) && ((Number)e.get("damage")).doubleValue()>0);
                        boolean attributedSecondary=after.stream().anyMatch(e->Boolean.TRUE.equals(e.get("secondary")) && ((Number)e.get("damage")).doubleValue()>0);
                        row.put("nativeFollowupHitEvent",nativeAttackHit==null?Map.of():nativeAttackHit);row.put("nativeFollowupDamageEvents",after);
                        complete(primaryFollowup>0 && chainFollowup>0 && stormCharges<earnedStormCharges && primaryHit && attributedPrimary && attributedSecondary?"PASS":"ACTION_FAILURE",
                            "Native guard/ordinary hostile parry earned charges. After natural recovery, authoritative fixture targets were placed relative to the recoiled actor and native TargetFinder selected primary while excluding secondary. Actual ATTACK_HIT, owner-attributed primary/secondary damage, charge consumption and full native fade were independently checked.");
                    } else if(ticks>160) throw new IllegalStateException("Native Stormbringer follow-up attack did not finish");
                }
                case STORM_DRAIN -> {
                    if(stormCleanupReady && !player.isPassenger() && ExpandedRagdollRecovery.clientRestored(player) && !player.isInvisible()) {
                        row.put("nativePendingPoseDrained",true);row.put("nativeClientSeatReleased",true);row.put("nativeClientVisibilityRestored",!player.isInvisible());
                        row.put("cleanupServerTicks",mc.getSingleplayerServer().getTickCount()-stormCleanupStart);observing=false;setPhase(Phase.DONE);
                    } else if(ticks>100) finish("RESTORE_FAILURE","Native sequential ability cleanup did not release seat/pending poses");
                }
                case DREAD_WAIT -> {
                    if(ticks%100==0) { row.put("nativeCorruption",charge); write("RUNNING",""); }
                    if(cloak) { row.put("nativeCorruptionAtActivation",charge); row.put("nativeCloakWaitTicks",ticks); pendingCapture="charged_cloak"; use(false); setPhase(Phase.DREAD_EFFECT); }
                    else if(ticks>1500) complete("UNMET_PRECONDITION","Installed native tuning did not produce voidcloak within 1500 ordinary ticks; stored charge was not fabricated");
                }
                case DREAD_EFFECT -> {
                    if(assault && damage>0 && ticks>=20) { pendingCapture="void_assault"; complete("PASS","Naturally acquired cloak activated native target assault and damage"); }
                    else if(ticks>160) complete("ACTION_FAILURE","Cloak existed but target assault/damage was not observed after native use");
                }
                default -> {}
            }
        }

        void tempestCheck() {
            if(!soulSameStack || target==null || !target.isAlive())throw new IllegalStateException("Tempest lost its original held stack/target");
        }
        void soulCheck() {
            if(soulState.containsKey("error"))throw new IllegalStateException("Native soul diagnostic failed: "+soulState);
            if(!soulSameStack || target==null || !target.isAlive())throw new IllegalStateException("Native soul sequence lost its original held stack/target");
        }
        int clientLanterns() {
            if(lanternVisual==null)return 0;
            var visual=mc.level.getEntity(lanternVisual.getId());return visual instanceof net.sweenus.simplyswords.entity.SoulkeeperLanternVisualEntity nativeVisual?nativeVisual.getLanternCount():0;
        }
        Map<String,Object> soulEvidence() {
            var evidence=new LinkedHashMap<String,Object>(soulState);evidence.put("sameServerStack",soulSameStack);evidence.put("weaknessAmplifier",soulWeakness);evidence.put("slownessAmplifier",soulSlowness);
            evidence.put("marked",soulMarked);evidence.put("nativeHits",bloodHits);evidence.put("serverCrouch",soulCrouch);evidence.put("clientLanternCount",clientLanterns());return evidence;
        }
        void ionCheck() {
            if(ionState.containsKey("error"))throw new IllegalStateException("Native Ionbound diagnostic failed: "+ionState);
            if(!ionSameStack || target==null || !target.isAlive() || chainTarget==null || !chainTarget.isAlive())throw new IllegalStateException("Ionbound lost its original native stack/targets");
            if(player.isPassenger() || !player.onGround())throw new IllegalStateException("Ionbound fixture left its grounded native action context");
        }
        Map<String,Object> ionEvidence() {
            var result=new LinkedHashMap<String,Object>(ionState);result.put("sameServerStack",ionSameStack);result.put("slamTargetDamage",ionSlamDamage);result.put("beamTargetDamage",ionBeamDamage);
            result.put("beamSeen",ionBeamObserved);result.put("movementSlowSeen",ionSlowObserved);result.put("beamTargetParalysisSeen",ionParalysisObserved);result.put("untetheredSlamTargetTravel",maxTargetTravel);
            result.put("slamTargetPosition",target==null?"":target.position().toString());result.put("beamTargetPosition",chainTarget==null?"":chainTarget.position().toString());
            result.put("nativeDamageEvents",List.copyOf(nativeDamageEvents));return result;
        }
        void use(boolean crouch) {
            mc.options.keyShift.setDown(crouch); player.setShiftKeyDown(crouch);
            var result=mc.gameMode.useItem(player,InteractionHand.MAIN_HAND);
            row.put("lastUseResult",result.toString());
            if(test.name.equals("ceraunus")&&!result.consumesAction()) throw new IllegalStateException("Native Ceraunus use rejected; client anchor="+player.getMainHandItem().get(ModDataComponents.THROWN_ANCHOR)+", server anchor="+anchor);
            mc.options.keyUse.setDown(player.isUsingItem());
        }
        void attackInput(boolean down) {
            mc.options.keyAttack.setDown(down);
            // The actual Aether input hook reads the key and sends its normal synchronized attachment packet.
            CapabilityClientHooks.AetherPlayerHooks.keyInput(mc.options.keyAttack.getKey().getValue());
            if(down) row.put("nativeAttackInputRequested",true);
        }
        void frame() throws Exception {
            if(phase!=Phase.PREP && CombatRenderCounters.bodyPasses>1) renderViolations++;
            if((test.name.equals("ionbound")||test.name.equals("molten_edge")||test.name.equals("soulrender")||test.name.equals("tempest")||test.name.equals("soulkeeper")) && phase!=Phase.PREP && phase!=Phase.STORM_DRAIN && !player.isPassenger()
                && CombatRenderCounters.bodyItems+CombatRenderCounters.vanillaItems+CombatRenderCounters.otherItems!=1) renderViolations++;
            if((test.name.equals("stormbringer")||test.name.equals("bulwark_melee")||test.name.equals("bloodwake")) && !player.isPassenger()
                && ((phase==Phase.STORM_GUARD && player.isUsingItem()) || ((phase==Phase.STORM_ATTACK||phase==Phase.BULWARK_ATTACK||phase==Phase.BLOOD_HIT) && ExpandedCombatActions.attackActive(player)))) {
                int itemPaths=CombatRenderCounters.bodyItems+CombatRenderCounters.vanillaItems+CombatRenderCounters.otherItems;
                if(itemPaths!=1) renderViolations++;
            }
            if(test.name.equals("ceraunus")) captureCeraunusTimeline();
            if(pendingCapture!=null) {
                String filename=String.format(Locale.ROOT,"%02d-%s-%d-%s.png",index+1,test.name,(int)test.pitch,pendingCapture);
                try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(filename)); }
                Map<String,Object> capture=new LinkedHashMap<>(CombatRenderCounters.snapshot());
                capture.put("item",test.item); capture.put("phase",pendingCapture); capture.put("screenshot",filename); capture.put("pitch",test.pitch);
                capture.put("usingItem",player.isUsingItem()); capture.put("useTicks",player.getTicksUsingItem()); capture.put("cloudMinions",cloudCount);
                capture.put("anchor",anchor); capture.put("serverAnchorId",anchorId); capture.put("nativeReturnObserved",returned); capture.put("storedCharge",charge);
                capture.put("serverStrideSurfaceMode",serverSurfaceMode); capture.put("serverStrideRise",serverStrideRise);
                if(player.getVehicle() instanceof SoulstalkerStrideEntity stride) { capture.put("clientStrideSurfaceMode",stride.getSurfaceMode()); capture.put("clientStrideRise",stride.getY()-origin.y); }
                capture.put("camera",mc.options.getCameraType().name());
                if(test.name.equals("stormbringer")) {capture.put("crouch",test.crouch);capture.put("earnedStormCharges",earnedStormCharges);capture.put("stormCharges",stormCharges);capture.put("attackActive",ExpandedCombatActions.attackActive(player));}
                capture.put("position",player.position().toString()); capture.put("nativeVehicle",player.getVehicle()==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString());
                if(test.name.equals("ceraunus")) capture.put("ceraunusGeometry",ceraunusGeometry());
                if(test.name.equals("bulwark_melee")){capture.put("nativeAttackHitEvent",nativeAttackHit);capture.put("nativeServerDamageEvents",List.copyOf(nativeDamageEvents));capture.put("targetSelection",bulwarkTargets());}
                if(test.name.equals("tempest"))capture.put("tempestSequence",tempestState);
                if(test.name.equals("soulrender")||test.name.equals("soulkeeper"))capture.put("soulSequence",soulEvidence());
                if(test.name.equals("molten_edge"))capture.put("moltenEdge",moltenEvidence());
                if(test.name.equals("ionbound"))capture.put("ionbound",ionEvidence());
                if(test.name.equals("bloodwake"))capture.put("bloodwake",Map.of("nativeHits",bloodHits,"frenzy",bloodFrenzy,"bleed",bloodBleed,"maximumBleed",bloodMaxBleed,"nativeWaveSpawns",bloodWaveSpawns,"targetBloodPlague",bloodPlague,"sameServerStack",bloodSameStack,"crouch",bloodServerCrouch));
                if(test.name.equals("guard")||test.name.equals("maelstrom"))capture.put("gauntletEvidence",gauntletEvidence());
                images.add(capture); pendingCapture=null; write("RUNNING","");
            }
            if(phase==Phase.DONE) next();
        }

        void captureCeraunusTimeline() throws Exception {
            for(var entry:ceraSpawnTicks.entrySet()) {
                long age=mc.getSingleplayerServer().getTickCount()-entry.getValue();
                String key=entry.getKey()+":"+age;
                if(age<3||age>5||!ceraDiagnosticImages.add(key))continue;
                String file=String.format(Locale.ROOT,"%02d-ceraunus-%d-projectile-%s-server-tick-%d.png",index+1,(int)test.pitch,entry.getKey().toString().substring(0,8),age);
                try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve(file));}
                var capture=new LinkedHashMap<String,Object>();capture.put("phase","native_projectile_geometry");capture.put("item",test.item);capture.put("pitch",test.pitch);
                capture.put("screenshot",file);capture.put("trackedProjectile",entry.getKey().toString());capture.put("nativeServerAge",age);
                capture.put("renderCounts",CombatRenderCounters.snapshot());capture.put("ceraunusGeometry",ceraunusGeometry());images.add(capture);write("RUNNING","");
            }
        }
        Map<String,Object> ceraunusGeometry() {
            var result=new LinkedHashMap<String,Object>();var camera=mc.gameRenderer.getMainCamera();float partial=mc.getTimer().getGameTimeDeltaPartialTick(false);
            Vec3 cameraPos=camera.getPosition();var stack=player.getMainHandItem();
            result.put("cameraPosition",cameraPos.toString());result.put("partialTick",partial);result.put("cameraType",mc.options.getCameraType().name());
            result.put("clientThrownAnchor",String.valueOf(stack.get(ModDataComponents.THROWN_ANCHOR)));
            var property=net.minecraft.client.renderer.item.ItemProperties.getProperty(stack,ResourceLocation.parse("throwing"));
            result.put("resolvedThrowingPredicate",property==null?null:property.call(stack,mc.level,player,0));
            var model=mc.getItemRenderer().getModel(stack,mc.level,player,0);
            for(var context:List.of(net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)) {
                var transform=model.getTransforms().getTransform(context);
                result.put(context.name(),Map.of("scale",List.of(transform.scale.x,transform.scale.y,transform.scale.z),"rotation",List.of(transform.rotation.x,transform.rotation.y,transform.rotation.z),"translation",List.of(transform.translation.x,transform.translation.y,transform.translation.z)));
            }
            List<Map<String,Object>> projectiles=new ArrayList<>();
            for(var entry:ceraProjectiles.entrySet()) {
                var nativeServer=entry.getValue();var projectile=mc.level.getEntity(nativeServer.getId());
                var item=new LinkedHashMap<String,Object>();item.put("uuid",entry.getKey().toString());item.put("nativeServerRemoved",nativeServer.isRemoved());item.put("nativeReturn",nativeServer.getReturn());
                item.put("nativeServerAge",mc.getSingleplayerServer().getTickCount()-ceraSpawnTicks.getOrDefault(entry.getKey(),0L));
                item.put("clientPresent",projectile!=null);
                if(projectile!=null) {
                    Vec3 interpolated=projectile.getPosition(partial);item.put("position",projectile.position().toString());item.put("interpolatedPosition",interpolated.toString());
                    item.put("cameraDistance",interpolated.distanceTo(cameraPos));item.put("cameraInsideEntityBounds",projectile.getBoundingBox().contains(cameraPos));
                    item.put("clientReturn",projectile instanceof Player_Ceraunus_Entity c&&c.getReturn());
                }
                projectiles.add(item);
            }
            result.put("projectiles",projectiles);result.put("neaPose",NeaProjectilePoseProbe.snapshot(player.getUUID()));return result;
        }
        void complete(String status,String detail) {
            observing=false; attackInput(false); mc.options.keyUse.setDown(false); mc.options.keyShift.setDown(false); player.setShiftKeyDown(false); mc.gameMode.releaseUsingItem(player);
            row.put("status",renderViolations>0?"RENDER_FAILURE":status); row.put("detail",detail); row.put("rendererViolations",renderViolations);
            row.put("spawnedEntityTypes",new TreeMap<>(types)); row.put("targetDamage",damage); row.put("serverUseObserved",serverUse); row.put("serverUseTicks",serverUseTicks); row.put("maxClientUseTicks",maxUseTicks);
            row.put("nativeReturnObserved",returned); row.put("maxCloudMinions",maxCloudCount); row.put("nativeAssaultObserved",assault); row.put("nativeHittingObserved",hittingObserved);
            row.put("blockedRepeatUseAttempted",rejectAttempted); row.put("nativeWallClimbObserved",sawNativeWall); row.put("serverStrideRise",serverStrideRise);
            mc.options.keyUp.setDown(false);
            if(test.name.equals("tempest")) {
                row.put("tempestFinal",tempestState);observing=true;setPhase(Phase.TEMPEST_EXPIRE);
            }else if(test.name.equals("soulrender")) {
                row.put("soulrenderFinal",soulEvidence());observing=true;setPhase(Phase.SOUL_EXPIRE);
            }else if(test.name.equals("soulkeeper")) {
                row.put("soulkeeperFinal",soulEvidence());observing=true;setPhase(Phase.LANTERN_EXPIRE);
            }else if(test.name.equals("molten_edge")) {
                row.put("moltenFinal",moltenEvidence());observing=true;setPhase(Phase.MOLTEN_EXPIRE);
            } else if(test.name.equals("ionbound")) {
                row.put("ionboundFinal",ionEvidence());observing=true;setPhase(Phase.ION_EXPIRE);
            } else if(test.name.equals("bloodwake")) {
                observing=true;setPhase(Phase.BLOOD_EXPIRE);
            } else if(test.name.equals("stormbringer")||test.name.equals("guard")||test.name.equals("maelstrom")||test.name.equals("bulwark_melee")||test.name.equals("relic_unattuned")) {
                stormCleanupStart=mc.getSingleplayerServer().getTickCount();stormCleanupReady=false;observing=true;setPhase(Phase.STORM_DRAIN);
            } else setPhase(Phase.DONE); write("RUNNING","");
        }
        void fail(Throwable failure) {
            LogUtils.getLogger().error("Sequential ability case",failure);
            if(row==null || phase==Phase.DONE) finish("FAIL",failure.toString()); else complete("CASE_FAILURE",failure.toString());
        }
        void cleanup(ServerPlayer sp) {
            observing=false; ExpandedRagdollRecovery.release(sp); sp.stopRiding(); sp.stopUsingItem();
            ItemStack prior=sp.getMainHandItem(); sp.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY); sp.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
            if(BuiltInRegistries.ITEM.getKey(prior.getItem()).toString().equals("simplyswords:dreadtide")) Phase10WeaponManager.tickDreadtide(sp,prior);
            for(UUID id:spawned) { Entity e=sp.serverLevel().getEntity(id); if(e!=null) e.discard(); } spawned.clear(); target=null;
            clouds(sp).removeIf(c->!c.isAlive());
        }
        void finish(String status,String detail) {
            if(finishing) return; finishing=true; observing=false; CombatRenderCounters.active=false;
            try {
                attackInput(attackKey); mc.options.keyUse.setDown(useKey); mc.options.keyShift.setDown(shiftKey); player.setShiftKeyDown(shiftKey); mc.gameMode.releaseUsingItem(player);
                movementKeys.forEach(KeyMapping::setDown);
                mc.options.hideGui=gui; mc.options.setCameraType(camera); if(fpmPresent) Fpm.restore(fpmEnabled);
                player.getAbilities().flying=flying; player.setSprinting(sprinting); player.setYRot(yaw); player.yRotO=yaw; player.setXRot(pitch); player.xRotO=pitch; player.yBodyRot=player.yBodyRotO=bodyYaw; player.yHeadRot=player.yHeadRotO=headYaw;
                player.setDeltaMovement(motion); report.put("clientSettingsRestored",true);
                if(inventory==null) { write(status,detail); active=null; return; }
                mc.getSingleplayerServer().execute(() -> {
                    try {
                        ServerPlayer sp=sp(); cleanup(sp); sp.removeAllEffects();
                        for(int n=0;n<inventory.size();n++) sp.getInventory().setItem(n,inventory.get(n).copy());
                        sp.getInventory().selected=originalServerSelected;
                        for(var effect:effects) sp.addEffect(new MobEffectInstance(effect));
                        sp.setHealth(health); sp.setAbsorptionAmount(absorption); sp.getFoodData().setFoodLevel(food); sp.getFoodData().setSaturation(saturation);
                        sp.getStats().setValue(sp,net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DAMAGE_DEALT),originalDamageDealt);sp.setGameMode(originalGameMode); sp.getAbilities().flying=serverFlying; sp.onUpdateAbilities();
                        sp.getServer().setDifficulty(originalDifficulty,true); sp.getServer().getWorldData().setDifficultyLocked(originalDifficultyLocked);
                        for(var entry:blocks.entrySet()) sp.serverLevel().setBlock(entry.getKey(),entry.getValue(),3);
                        ExpandedReactionTransition.displaced(sp);
                        sp.teleportTo(serverPosition.x,serverPosition.y,serverPosition.z); sp.setYRot(yaw); sp.setXRot(pitch); sp.setShiftKeyDown(shiftKey);
                        sp.setDeltaMovement(motion); sp.fallDistance=0; ExpandedReactionTransition.displaced(sp);
                        sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(sp)); sp.inventoryMenu.broadcastChanges();
                        boolean serverSelectionRestored=sp.getInventory().selected==originalServerSelected;
                        mc.execute(() -> {
                            // The incoming state can already be desynchronized. Restore the native server
                            // selection on both sides, not the stale client index that caused this fixture failure.
                            player.getInventory().selected=originalServerSelected;
                            player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(originalServerSelected));
                            report.put("originalClientSelected",originalClientSelected);report.put("originalServerSelected",originalServerSelected);
                            report.put("originalClientServerSelectionMatched",originalClientSelected==originalServerSelected);
                            report.put("selectionReconciledToOriginalServer",serverSelectionRestored && player.getInventory().selected==originalServerSelected);
                            report.put("clientOriginalSelectionRestored",player.getInventory().selected==originalClientSelected);
                            report.put("serverSelectedRestored",serverSelectionRestored);
                            report.put("serverInventoryRestored",true); report.put("gameModeRestored",originalGameMode.name()); report.put("arenaBlocksRestored",blocks.size()); report.put("difficultyRestored",originalDifficulty.name()); report.put("difficultyLockRestored",originalDifficultyLocked); write(status,detail); active=null;
                        });
                    } catch(Throwable e) { mc.execute(() -> { write("RESTORE_FAILURE",e.toString()); active=null; }); }
                });
            } catch(Throwable e) { write("RESTORE_FAILURE",e.toString()); active=null; }
        }
        void write(String status,String detail) {
            report.put("status",status); report.put("detail",detail);
            try { Files.writeString(output.resolve("sequential-ability-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch(Exception e) { LogUtils.getLogger().error("Sequential ability report write",e); }
        }
    }
    /** Read-only exact target visual lookup; does not apply or clear mark effects. */
    private static Map<String,Object> soulMarkState(ServerPlayer player,Mob target) {
        try {
            var field=net.sweenus.simplyswords.world.SoulrenderMarkVisualManager.class.getDeclaredField("ACTIVE_MARKS");field.setAccessible(true);
            Object world=((Map<?,?>)field.get(null)).get(player.serverLevel());boolean exists=world instanceof Map<?,?> map && map.containsKey(target.getUUID());
            return Map.of("activeMarkVisual",exists,"targetUuid",target.getUUID().toString(),"nativeWorldTick",player.serverLevel().getGameTime());
        }catch(ReflectiveOperationException error){return Map.of("error",error.toString());}
    }
    /** Read-only native owner state. No manager activation, reset or component writes. */
    private static Map<String,Object> ionNativeState(ServerPlayer player,ItemStack stack,long equippedAt) {
        var result=new LinkedHashMap<String,Object>();
        try {
            Class<?> manager=IonboundStormscaleAbilityManager.class;
            Object corridor=ionOwnerState(manager,"CORRIDORS",player),beam=ionOwnerState(manager,"BEAMS",player);
            result.put("worldTick",player.serverLevel().getGameTime());result.put("nativeHeldTicks",player.serverLevel().getGameTime()-equippedAt);
            result.put("cubes",IonboundStormscaleAbilityManager.getCubes(stack).cubes());result.put("crouch",player.isShiftKeyDown());result.put("pitch",player.getXRot());result.put("grounded",player.onGround());
            result.put("corridor",corridor!=null);result.put("beam",beam!=null);result.put("slammed",corridor!=null && ionField(corridor,"slammed").equals(true));
            result.put("pendingFollowup",IonboundStormscaleAbilityManager.hasPendingFollowup(player.serverLevel(),player));
            result.put("movementSlow",player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(ResourceLocation.parse("simplyswords:ion_beam_channel_slow"))!=null);
            if(corridor!=null) {result.put("corridorStartedAt",ionField(corridor,"startedAt"));result.put("slamAt",ionField(corridor,"slamAt"));result.put("followupEnds",ionField(corridor,"followupEnds"));}
            if(beam!=null) {result.put("beamStartedAt",ionField(beam,"startedAt"));result.put("beamEndsAt",ionField(beam,"endsAt"));}
        }catch(ReflectiveOperationException error){result.put("error",error.toString());}
        return result;
    }
    private static Object ionOwnerState(Class<?> manager,String name,ServerPlayer player)throws ReflectiveOperationException {
        var field=manager.getDeclaredField(name);field.setAccessible(true);Object world=((Map<?,?>)field.get(null)).get(player.serverLevel());
        return world instanceof Map<?,?> map?map.get(player.getUUID()):null;
    }
    private static Object ionField(Object owner,String name)throws ReflectiveOperationException {var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static int selectedBloodRite(ItemStack stack) {
        int value=stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getInt("simplyswords_bloodwake_selected_rite");
        return Math.clamp(value==0?1:value,1,5);
    }
    /** Read-only native lifetime inspection: no calls to manager activation/reset or stain removal. */
    private static Map<String,Object> bloodStains(ServerPlayer player) {
        try {
            var field=net.sweenus.simplyswords.world.BloodStainManager.class.getDeclaredField("ACTIVE");field.setAccessible(true);
            var active=(Map<?,?>)field.get(null);Object entries=active.get(player.serverLevel());int count=0;long latest=0;
            if(entries instanceof List<?> list)for(Object stain:list) {
                var owner=stain.getClass().getDeclaredField("ownerId");owner.setAccessible(true);
                if(!player.getUUID().equals(owner.get(stain)))continue;
                var expiry=stain.getClass().getDeclaredField("expiryTick");expiry.setAccessible(true);count++;latest=Math.max(latest,expiry.getLong(stain));
            }
            return Map.of("active",count,"latestExpiry",latest);
        } catch(ReflectiveOperationException error){throw new IllegalStateException("Read-only native stain lifetime inspection failed",error);}
    }
    // Nitrogen is nested in Aether's jar. Reflect only these public read methods
    // to avoid adding a second dependency solely for this developer fixture.
    private static Object aetherRead(ServerPlayer player,String method) {
        Object data=player.getData(AetherDataAttachments.AETHER_PLAYER);
        try { return data.getClass().getMethod(method).invoke(data); }
        catch(ReflectiveOperationException e) { throw new IllegalStateException("Native Aether attachment read: "+method,e); }
    }
    @SuppressWarnings("unchecked")
    private static List<CloudMinion> clouds(ServerPlayer player) { return (List<CloudMinion>)aetherRead(player,"getCloudMinions"); }
    private static final class Fpm {
        static boolean enabled() { return dev.tr7zw.firstperson.api.FirstPersonAPI.isEnabled(); }
        static void enable() { dev.tr7zw.firstperson.api.FirstPersonAPI.setEnabled(true); }
        static void restore(boolean enabled) { dev.tr7zw.firstperson.api.FirstPersonAPI.setEnabled(enabled); }
    }
}

