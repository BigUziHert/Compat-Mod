package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.bettercombat.api.WeaponAttributes;
import net.bettercombat.client.animation.PlayerAttackAnimatable;
import net.bettercombat.logic.PlayerAttackHelper;
import net.bettercombat.logic.PlayerAttackProperties;
import net.bettercombat.logic.WeaponRegistry;
import net.combat_roll.internals.RollingEntity;
import net.combat_roll.client.Keybindings;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.sweenus.simplyswords.api.AwakeningApi;
import net.sweenus.simplyswords.item.interfaces.UniqueWeaponActiveAbility;
import net.sweenus.simplyswords.util.WeaponManaCost;

/** Exhaustive installed-item native attack/use replay. Never installed outside the disposable test client. */
public final class ExpandedCombatSequence {
    private static volatile Session active;
    private static volatile long serverTicks;
    private static final Set<String> NAMESPACES = Set.of("simplyswords", "aether", "cataclysm");
    private ExpandedCombatSequence() {}

    public static void register() {
        CombatWorldRenderProbe.register();
        NeoForge.EVENT_BUS.addListener(ExpandedCombatSequence::tick);
        NeoForge.EVENT_BUS.addListener(ExpandedCombatSequence::beforeTick);
        NeoForge.EVENT_BUS.addListener(ExpandedCombatSequence::beforeFrame);
        NeoForge.EVENT_BUS.addListener(ExpandedCombatSequence::afterFrame);
        NeoForge.EVENT_BUS.addListener(ExpandedCombatSequence::serverTick);
        NeoForge.EVENT_BUS.addListener(ExpandedCombatSequence::entityAdded);
        NeoForge.EVENT_BUS.addListener(ExpandedCombatSequence::damageApplied);
        net.bettercombat.api.client.BetterCombatClientEvents.ATTACK_HIT.register((player,hand,targets,cursor)-> {
            var session=active;
            if(session==null || !session.nativeActionStarted || !session.observing || session.finishing || player!=session.player)return;
            session.nativeAttackEvents.add(Map.of("animation",hand.attack().animation(),"clientTick",player.tickCount,
                    "targets",targets.stream().map(entity->Map.of("uuid",entity.getUUID().toString(),
                            "type",BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                            "designated",session.target!=null && entity.getUUID().equals(session.target.getUUID()))).toList()));
        });
    }

    private static void damageApplied(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        var session=active;
        if(session==null || !session.nativeActionStarted || !session.observing || session.finishing
                || event.getEntity().level().isClientSide() || session.target==null
                || !event.getEntity().getUUID().equals(session.target.getUUID()))return;
        var source=event.getSource(); var actor=source.getEntity(); var direct=source.getDirectEntity();
        session.nativeDamageEvents.add(Map.of("damage",event.getNewDamage(),"damageSource",source.getMsgId(),
                "actor",actor==null?"":actor.getUUID().toString(),"directEntity",direct==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()).toString(),
                "playerOwned",actor!=null&&actor.getUUID().equals(session.player.getUUID()),
                "serverTick",event.getEntity().getServer().getTickCount()));
    }

    private static void beforeTick(ClientTickEvent.Pre event) {
        Session session=active;
        if(session!=null && session.started && !session.finishing && session.holdUse && !session.released
                && session.useMax>0 && session.useMax<=1000 && !session.requiresUseRelease && session.clientUseEnded) {
            // Do not auto-start another finite use. A delayed authoritative
            // using flag may briefly reactivate the client; keep that existing
            // use held until the server finishes instead of sending an early cancel.
            session.mc.options.keyUse.setDown(session.player.isUsingItem() && !session.serverUseEnded);
        }
    }

    private static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (active != null) {
            if(active.finishing) { active.finishTick(); return; }
            if (mc.player != active.player || mc.level == null || !mc.player.isAlive() || mc.screen != null) {
                active.finish("ABORTED", "World/player changed, death, or screen opened"); return;
            }
            try { active.tick(); }
            catch (Throwable failure) { active.failCase(failure); }
            return;
        }
        if (mc.player == null || mc.screen != null || CombatRenderCounters.active) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-expanded-combat.txt");
        if (!Files.isRegularFile(trigger)) return;
        if (!NativeWorldReadiness.ready()) return;
        try {
            if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client")) || !mc.hasSingleplayerServer())
                throw new IllegalStateException("Expanded combat requires disposable .local/client integrated world");
            String[] lines = Files.readString(trigger).trim().split("\\R");
            Files.delete(trigger);
            String label = lines[0].replaceAll("[^a-zA-Z0-9._-]", "_");
            String mode = lines.length > 1 ? lines[1].trim() : "all";
            active = new Session(mc, label, mode, java.util.Arrays.copyOfRange(lines, Math.min(2, lines.length), lines.length));
            if (mode.equals("catalog")) { active.write("CATALOGUED", "No actions requested; registry-only inventory"); active = null; }
            else { CombatRenderCounters.active = true; active.next(); }
        } catch (Throwable failure) {
            if (active != null) active.finish("FAIL", failure.toString());
            CombatRenderCounters.active = false;
            LogUtils.getLogger().error("Expanded combat startup", failure);
        }
    }

    private static void beforeFrame(RenderFrameEvent.Pre event) {
        if (active != null && !active.finishing) {
            CombatRenderCounters.reset();
            CombatWorldRenderProbe.resetFrame();
            RagdollRenderCounters.active=!ExpandedRagdollRecovery.clientRestored(active.player);
            if(RagdollRenderCounters.active) RagdollRenderCounters.reset();
        }
    }
    private static void afterFrame(RenderFrameEvent.Post event) {
        Session session = active;
        if (session == null) return;
        try { session.frame(); } catch (Throwable error) { session.failCase(error); }
    }
    private static void serverTick(ServerTickEvent.Post event) {
        serverTicks++;
        Session session = active;
        if (session != null) { if(session.finishing) session.restoreServerIfReady(); else if(session.endPending) session.cleanupCaseIfReady(); else session.observeServer(); }
    }
    private static void entityAdded(EntityJoinLevelEvent event) {
        Session session = active;
        if (session != null && !event.getLevel().isClientSide() && session.observing
                && event.getEntity().distanceToSqr(session.origin) < 4096) {
            session.spawned.add(event.getEntity().getUUID());
            session.spawnedTypes.merge(BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).toString(), 1, Integer::sum);
            Entity entity=event.getEntity();
            if(entity instanceof net.minecraft.world.entity.projectile.Projectile projectile && session.projectileTraces.size()<24)
                session.projectileTraces.put(projectile.getUUID(),new ProjectileTrajectoryProbe(projectile,session.serverPlayer()));
            String namespace=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace();
            if (NAMESPACES.contains(namespace) || entity instanceof net.minecraft.world.entity.projectile.Projectile
                    || entity instanceof net.minecraft.world.entity.AreaEffectCloud || entity instanceof net.minecraft.world.entity.LightningBolt
                    || entity instanceof net.minecraft.world.entity.item.PrimedTnt)
                session.abilitySpawnedTypes.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),1,Integer::sum);
        }
    }

    private record Case(String item, String kind, int attackIndex, float pitch, String context, String awakening, boolean crouch) {
        String name() { return item.replace(':', '_') + "-" + kind + "-" + attackIndex + "-" + context + "-" + awakening + "-" + (int)pitch + (crouch ? "-crouch" : ""); }
    }
    private record AccessoryState(List<ItemStack> items,List<ItemStack> cosmetic,List<Boolean> renders) {}

    private static boolean overrides(Item item, String method, Class<?>... args) {
        try { return item.getClass().getMethod(method, args).getDeclaringClass() != Item.class; }
        catch (ReflectiveOperationException error) { return false; }
    }

    private static final class Session {
        final Minecraft mc; final LocalPlayer player; final Path output;
        final int originalClientSelected; volatile int originalServerSelected=-1, observedServerSelected=-1;
        boolean fixtureSelectedPacketSent;
        volatile String observedServerHeldItem="";
        final CameraType camera; final boolean gui, shiftKey, useKey, rollKey, sprint;
        final float yaw, pitch, bodyYaw, headYaw; final Vec3 origin, originalPosition, motion; final Pose pose; final int combo;
        final boolean clientFlying;
        final boolean clientInvisible;
        final Map<KeyMapping,Boolean> movementKeys = new LinkedHashMap<>();
        final Map<String,AccessoryState> accessoriesBefore = new LinkedHashMap<>();
        boolean shrink;
        boolean ragdollFirstPerson;
        final boolean hasRagdoll, originalRagdollFirstPerson;
        volatile float observedServerScale;
        final Map<BlockPos,BlockState> arenaBefore = new LinkedHashMap<>();
        volatile boolean serverFlying;
        volatile Difficulty originalDifficulty;
        volatile boolean originalDifficultyLocked;
        final boolean hasFpm, fpmEnabled;
        final List<Case> cases = new ArrayList<>();
        final Map<String,List<String>> nativePlannedAttackSignatures = new LinkedHashMap<>();
        final List<Map<String,Object>> catalogue = new ArrayList<>(), rows = new ArrayList<>();
        final Map<String,Object> report = new LinkedHashMap<>();
        final List<String> limitations = new ArrayList<>();
        final Set<UUID> spawned = ConcurrentHashMap.newKeySet();
        final Map<String,Integer> spawnedTypes = new ConcurrentHashMap<>();
        final Map<String,Integer> abilitySpawnedTypes = new ConcurrentHashMap<>();
        final Set<String> targetEffects = ConcurrentHashMap.newKeySet(), playerEffects = ConcurrentHashMap.newKeySet();
        final Set<String> allSpawnedTypes = ConcurrentHashMap.newKeySet();
        final Map<UUID,ProjectileTrajectoryProbe> projectileTraces=new LinkedHashMap<>();
        final java.util.Queue<Map<String,Object>> nativeDamageEvents=new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.Queue<Map<String,Object>> nativeAttackEvents=new java.util.concurrent.ConcurrentLinkedQueue<>();
        volatile boolean nativeActionStarted;
        volatile Map<String,Object> serverAim=Map.of();
        volatile boolean observing; volatile Mob target; volatile Vec3 targetPosition;
        volatile boolean serverWasUsing,serverUseEnded;
        volatile float lastHealth, totalDamage; volatile boolean targetBurned, targetDied, serverUsed, serverCooldown;
        volatile int serverUseTicks; volatile List<ItemStack> inventoryBefore; volatile float serverHealth;
        volatile int serverFood; volatile Vec3 serverOrigin;
        volatile float serverSaturation; volatile int serverExperience, serverLevel;
        volatile float serverExperienceProgress;
        volatile List<net.minecraft.world.effect.MobEffectInstance> effectsBefore;
        CompletableFuture<Void> prepared, finalized;
        CompletableFuture<Map<String,Object>> weaponStateVerified;
        ItemStack expected; Case current; Map<String,Object> row;
        int index = -1, ticks, prepTicks, endTick, captureAt, nativeCombo, frames, violations, useMax, rollWait;
        int maxUseElapsed, firstInactive = -1; boolean started, sawActive, screenshot, recoveryScreenshot, released, sawRoll;
        int maxBodyPasses, maxBodyItems, maxVanillaItems, maxOtherItems, zeroItemFrames;
        volatile boolean endPending; volatile boolean finishing; Vec3 actionOrigin;
        volatile long caseCleanupAfterTick=Long.MAX_VALUE;
        volatile long caseCleanupScheduledTick;
        volatile boolean caseCleanupWaiting,caseExpectedInvisible,movedDuringAction;
        int caseCleanupClientTicks;
        long finishAfterServerTick;
        volatile long cleanupGraceThroughServerTick;
        int finishClientTicks;
        boolean serverRestorationStarted;
        volatile boolean serverRestorationComplete;
        String finishStatus,finishDetail;
        boolean holdUse,clientWasUsing,clientUseEnded,requiresUseRelease;
        final String mode;

        Session(Minecraft mc, String label, String mode, String[] selection) throws IOException {
            this.mc = mc; this.mode = mode; player = mc.player;originalClientSelected=player.getInventory().selected;
            if (!Set.of("all","catalog","melee","abilities","roll").contains(mode)) throw new IllegalArgumentException("Unknown combat mode " + mode);
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label); Files.createDirectories(output);
            camera = mc.options.getCameraType(); gui = mc.options.hideGui;
            shiftKey = mc.options.keyShift.isDown(); useKey = mc.options.keyUse.isDown(); rollKey = Keybindings.roll.isDown();
            for (KeyMapping key : List.of(mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,
                    mc.options.keyJump,mc.options.keySprint,mc.options.keyAttack)) movementKeys.put(key,key.isDown());
            sprint = player.isSprinting(); yaw = player.getYRot(); pitch = player.getXRot();
            bodyYaw = player.yBodyRot; headYaw = player.yHeadRot; originalPosition = player.position();
            origin = new Vec3(128.5,mc.level.getMinBuildHeight()+13,128.5);
            clientFlying = player.getAbilities().flying; motion = player.getDeltaMovement(); pose = player.getPose();
            clientInvisible=player.isInvisible();
            combo = ((PlayerAttackProperties)player).getComboCount();
            hasFpm = ModList.get().isLoaded("firstperson"); fpmEnabled = hasFpm && Fpm.enabled();
            hasRagdoll=ModList.get().isLoaded("sable_player_ragdoll"); originalRagdollFirstPerson=hasRagdoll&&NativeRagdollCamera.get();
            report.put("label", label); report.put("mode", mode); report.put("firstPersonInstalled", hasFpm);
            report.put("catalogue", catalogue); report.put("cases", rows); report.put("limitations", limitations);
            report.put("resourcePacks", mc.getResourcePackRepository().getSelectedIds());
            var originalKeys=new LinkedHashMap<String,Boolean>(); movementKeys.forEach((key,down)->originalKeys.put(key.getName(),down));
            originalKeys.put(mc.options.keyUse.getName(),useKey); originalKeys.put(mc.options.keyShift.getName(),shiftKey); originalKeys.put(Keybindings.roll.getName(),rollKey);
            report.put("originalInputStates",originalKeys); report.put("originalClientPosition",originalPosition.toString()); report.put("originalClientMotion",motion.toString());
            report.put("arena",Map.of("origin",List.of(origin.x,origin.y,origin.z),"floor","bedrock","radius",16,"clearance",9));
            limitations.add("Every registered relevant item is catalogued; all native attribute attack entries are scheduled at level and 55-degree down pitch. Item-level failures continue independently.");
            limitations.add("Normal high-health stationary targets exercise ordinary damage hooks. Chance-based, on-kill, biome/weather, allies, stored-charge, runic gem variants and exceptional secondary prerequisites are recorded as unproven unless their effects are actually observed. They are not implied to pass.");
            catalogue();
            report.put("unfilteredPlannedCases", cases.size());
            String itemPattern = ".*", kindPattern = ".*";
            int startIndex = 0, maximum = Integer.MAX_VALUE;
            for (String option : selection) {
                if (option.startsWith("item=")) itemPattern = option.substring(5);
                else if (option.startsWith("kind=")) kindPattern = option.substring(5);
                else if (option.startsWith("start=")) startIndex = Integer.parseInt(option.substring(6));
                else if (option.startsWith("limit=")) maximum = Integer.parseInt(option.substring(6));
                else if (option.equals("shrink")) shrink=true;
                else if (option.equals("ragdoll_firstperson")) ragdollFirstPerson=true;
                else if (!option.isBlank()) throw new IllegalArgumentException("Unknown selection: " + option);
            }
            if (startIndex < 0 || maximum < 1) throw new IllegalArgumentException("Invalid test range");
            var itemFilter = java.util.regex.Pattern.compile(itemPattern);
            var kindFilter = java.util.regex.Pattern.compile(kindPattern);
            cases.removeIf(c -> !itemFilter.matcher(c.item).matches() || !kindFilter.matcher(c.kind).matches());
            report.put("selection", Map.of("item", itemPattern, "kind", kindPattern, "start", startIndex, "limit", maximum));
            report.put("matchingCasesBeforeRange", cases.size());
            var selected = new ArrayList<>(cases.subList(Math.min(startIndex, cases.size()),
                    (int)Math.min((long)cases.size(), (long)startIndex + maximum)));
            cases.clear(); cases.addAll(selected);
            report.put("plannedCases", cases.size());
            report.put("shrinkingArtifact",shrink);
            report.put("requestedNativeRagdollFirstPerson",ragdollFirstPerson); report.put("originalNativeRagdollFirstPerson",originalRagdollFirstPerson);
            report.put("estimatedMinutesAt20TPS", Math.round(cases.stream().mapToInt(c -> c.kind.equals("melee") ? 70 : 100).sum() / 1200.0));
            write("RUNNING", "Catalogue complete before replay");
        }

        void catalogue() {
            var items = BuiltInRegistries.ITEM.stream().sorted(Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).toString())).toList();
            for (Item requestedItem : items) {
                String id = BuiltInRegistries.ITEM.getKey(requestedItem).toString(); String ns = BuiltInRegistries.ITEM.getKey(requestedItem).getNamespace();
                if (!NAMESPACES.contains(ns) && !Set.of("minecraft:bow", "minecraft:crossbow", "minecraft:trident").contains(id)) continue;
                ItemStack originalStack = requestedItem.getDefaultInstance();
                var originalAttributes = WeaponRegistry.getAttributes(originalStack);
                // Schedule the same native migrated form that next() equips.
                // Legacy alias IDs do not necessarily share its attack array.
                var prepared = ExpandedNativeWeaponPreparation.prepare(id, "full");
                ItemStack stack = prepared.stack(); Item item = stack.getItem();
                var attributes = WeaponRegistry.getAttributes(stack);
                boolean weapon = attributes != null || item instanceof SwordItem || item instanceof ProjectileWeaponItem || item instanceof TridentItem
                        || (item.getClass().getName().contains("aether.item.combat.loot") && !(item instanceof ArmorItem))
                        || (ns.equals("cataclysm") && !(item instanceof ArmorItem) && !(item instanceof BlockItem)
                            && (stack.isDamageableItem() || item.getClass().getSimpleName().contains("Gauntlet")));
                Map<String,Object> entry = new LinkedHashMap<>(); entry.put("item", id); entry.put("class", requestedItem.getClass().getName());
                entry.put("preparedClass", item.getClass().getName()); entry.putAll(prepared.evidence());
                entry.put("originalRegistryUseAnimation", originalStack.getUseAnimation().name());
                entry.put("originalRegistryAttackCount", originalAttributes == null || originalAttributes.attacks() == null ? 0 : originalAttributes.attacks().length);
                entry.put("originalRegistryAttackSignatures", originalAttributes == null || originalAttributes.attacks() == null ? List.of()
                        : Arrays.stream(originalAttributes.attacks()).map(a -> new com.google.gson.Gson().toJson(a)).toList());
                entry.put("combatItem", weapon); entry.put("nativeAttacks", attributes == null || attributes.attacks() == null ? 0 : attributes.attacks().length);
                entry.put("useAnimation", stack.getUseAnimation().name());
                entry.put("activeUniqueAbility", item instanceof UniqueWeaponActiveAbility);
                entry.put("defaultAwakeningLevel", ns.equals("simplyswords") ? AwakeningApi.getLevel(originalStack) : -1);
                catalogue.add(entry);
                if (!weapon) { entry.put("coverage", "NON_COMBAT_ITEM_IN_REGISTRY"); continue; }
                entry.put("coverage", "SCHEDULED");
                if (attributes != null && attributes.attacks() != null) {
                    List<Map<String,Object>> attacks = new ArrayList<>(); entry.put("attacks", attacks);
                    nativePlannedAttackSignatures.put(id, Arrays.stream(attributes.attacks())
                            .map(a -> new com.google.gson.Gson().toJson(a)).toList());
                    for (int a = 0; a < attributes.attacks().length; a++) {
                        var attack = attributes.attacks()[a]; var conditions = attack.conditions() == null ? List.<WeaponAttributes.Condition>of() : Arrays.asList(attack.conditions());
                        String context = conditions.contains(WeaponAttributes.Condition.MOUNTED) ? "mounted"
                            : conditions.stream().anyMatch(c -> c.name().startsWith("DUAL_WIELDING") || c == WeaponAttributes.Condition.OFF_HAND_ONLY) ? "dual"
                            : conditions.contains(WeaponAttributes.Condition.OFF_HAND_SHIELD) ? "shield" : "empty";
                        attacks.add(Map.of("index", a, "animation", attack.animation(), "conditions", conditions, "context", context,
                                "nativeAttackSignature", nativePlannedAttackSignatures.get(id).get(a)));
                        for (float angle : new float[]{0, 55}) add(new Case(id,"melee",a,angle,context,"full",false));
                    }
                } else if (!(item instanceof ProjectileWeaponItem) && !(item instanceof ShieldItem)) {
                    for (float angle : new float[]{0,55}) add(new Case(id,"vanilla_hit",0,angle,"empty","full",false));
                }
                boolean nativeUse = overrides(item, "use", Level.class, Player.class, InteractionHand.class);
                boolean useOn = ns.equals("cataclysm") && overrides(item,"useOn",net.minecraft.world.item.context.UseOnContext.class)
                        && item.getClass().getName().startsWith("com.github.L_Ender.cataclysm.items.");
                entry.put("nativeUse", nativeUse); entry.put("nativeUseOn", useOn);
                if (nativeUse || useOn) {
                    for (float angle : new float[]{0,55}) for (boolean crouch : new boolean[]{false,true})
                        add(new Case(id,useOn ? "use_on" : "use",0,angle,"empty","full",crouch));
                    if (ns.equals("simplyswords") && item instanceof UniqueWeaponActiveAbility && AwakeningApi.usesAwakeningProgression(stack))
                        add(new Case(id,"use",0,0,"empty","locked",false));
                }
            }
            add(new Case("minecraft:air","roll",0,55,"empty","full",false));
            Map<String,Long> counts = new LinkedHashMap<>();
            for (String namespace : NAMESPACES) counts.put(namespace, catalogue.stream().filter(e -> ((String)e.get("item")).startsWith(namespace + ":") && Boolean.TRUE.equals(e.get("combatItem"))).count());
            report.put("registeredCombatItemCounts", counts);
        }

        void add(Case test) {
            if (mode.equals("all") || mode.equals("catalog") || (mode.equals("melee") && (test.kind.equals("melee") || test.kind.equals("vanilla_hit")))
                    || (mode.equals("abilities") && test.kind.startsWith("use")) || (mode.equals("roll") && test.kind.equals("roll"))) cases.add(test);
        }

        void next() {
            if (++index >= cases.size()) { finish("CAPTURED", "Review per-case statuses, evidence and explicit unmet prerequisites"); return; }
            current = cases.get(index); row = new ConcurrentHashMap<>();
            CombatWorldRenderProbe.beginCase(current.item);
            row.put("case", current.name()); row.put("item", current.item); row.put("kind", current.kind); row.put("attackIndex", current.attackIndex);
            row.put("pitch", current.pitch); row.put("context", current.context); row.put("awakening", current.awakening); row.put("crouch", current.crouch);
            rows.add(row); ticks = prepTicks = frames = violations = maxUseElapsed = rollWait = 0;
            maxBodyPasses = maxBodyItems = maxVanillaItems = maxOtherItems = zeroItemFrames = 0;
            firstInactive = -1; started = sawActive = screenshot = recoveryScreenshot = released = sawRoll = endPending = false;
            holdUse=clientWasUsing=clientUseEnded=serverWasUsing=serverUseEnded=requiresUseRelease=false;
            projectileTraces.clear(); serverAim=Map.of();
            weaponStateVerified=null;fixtureSelectedPacketSent=false;observedServerSelected=-1;observedServerHeldItem="";
            row.put("preEquipClientSelected",player.getInventory().selected);row.put("fixtureSelectedSlot",0);
            nativeActionStarted=false; nativeDamageEvents.clear(); nativeAttackEvents.clear();
            spawnedTypes.clear(); abilitySpawnedTypes.clear(); targetEffects.clear(); playerEffects.clear(); totalDamage = 0; targetBurned = targetDied = serverUsed = serverCooldown = false; serverUseTicks = 0;
            mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(player); player.stopUsingItem(); Keybindings.roll.setDown(false);
            movementKeys.keySet().forEach(key -> key.setDown(false));
            ((PlayerAttackAnimatable)player).stopAttackAnimation(0);
            var nativeWeapon = ExpandedNativeWeaponPreparation.prepare(current.item, current.awakening);
            expected = nativeWeapon.stack();
            row.putAll(nativeWeapon.evidence());
            if (current.item.startsWith("simplyswords:")) row.put("manaCost", WeaponManaCost.of(expected));
            pose(); observing = false;
            prepared = new CompletableFuture<>();
            mc.getSingleplayerServer().execute(() -> {
                try {
                    ServerPlayer sp = serverPlayer();
                    if (inventoryBefore == null) {
                        originalServerSelected=sp.getInventory().selected;
                        inventoryBefore = new ArrayList<>(); for (int i=0;i<sp.getInventory().getContainerSize();i++) inventoryBefore.add(sp.getInventory().getItem(i).copy());
                        serverHealth = sp.getHealth(); serverFood = sp.getFoodData().getFoodLevel(); serverOrigin = sp.position();
                        serverSaturation = sp.getFoodData().getSaturationLevel(); serverExperience = sp.totalExperience;
                        serverLevel = sp.experienceLevel; serverExperienceProgress = sp.experienceProgress;
                        serverFlying = sp.getAbilities().flying;
                        originalDifficulty=sp.getServer().getWorldData().getDifficulty();
                        originalDifficultyLocked=sp.getServer().getWorldData().isDifficultyLocked();
                        effectsBefore = sp.getActiveEffects().stream().map(net.minecraft.world.effect.MobEffectInstance::new).toList();
                        if(shrink) {
                            var cap=Objects.requireNonNull(AccessoriesCapability.get(sp),"Accessories capability missing");
                            cap.getContainers().forEach((name,container)-> {
                                List<ItemStack> items=new ArrayList<>(),cosmetics=new ArrayList<>();
                                for(int i=0;i<container.getSize();i++) { items.add(container.getAccessories().getItem(i).copy()); cosmetics.add(container.getCosmeticAccessories().getItem(i).copy()); }
                                accessoriesBefore.put(name,new AccessoryState(items,cosmetics,new ArrayList<>(container.renderOptions())));
                            });
                        }
                    }
                    cleanupEntities(sp); prepareArena(sp); sp.getServer().setDifficulty(Difficulty.NORMAL,true);
                    sp.stopUsingItem(); sp.removeAllEffects(); sp.setHealth(sp.getMaxHealth()); sp.getFoodData().setFoodLevel(20);
                    sp.getAbilities().flying = false; sp.onUpdateAbilities();
                    sp.teleportTo(origin.x, origin.y, origin.z); sp.setYRot(yaw); sp.setXRot(current.pitch); sp.setShiftKeyDown(current.crouch); sp.setDeltaMovement(Vec3.ZERO);
                    ExpandedReactionTransition.displaced(sp);
                    row.put("preEquipServerSelected",sp.getInventory().selected);sp.getInventory().selected=0;
                    sp.setItemInHand(InteractionHand.MAIN_HAND, expected.copy());
                    sp.setItemInHand(InteractionHand.OFF_HAND, current.context.equals("dual") ? expected.copy() : current.context.equals("shield") ? Items.SHIELD.getDefaultInstance() : ItemStack.EMPTY);
                    if(shrink) equipShrinkingCharm(sp);
                    sp.getCooldowns().removeCooldown(expected.getItem());
                    if (expected.getItem() instanceof ProjectileWeaponItem projectile) {
                        Item ammo = BuiltInRegistries.ITEM.stream().filter(i -> projectile.getAllSupportedProjectiles().test(i.getDefaultInstance())).findFirst().orElse(Items.ARROW);
                        sp.getInventory().setItem(9, new ItemStack(ammo,64)); row.put("ammo", BuiltInRegistries.ITEM.getKey(ammo).toString());
                    }
                    sp.inventoryMenu.broadcastChanges();
                    if (current.context.equals("mounted")) {
                        var horse = EntityType.HORSE.create(sp.serverLevel());
                        if (horse == null) throw new IllegalStateException("Native horse creation failed");
                        horse.moveTo(origin.x,origin.y,origin.z,yaw,0); horse.setTamed(true); horse.setNoAi(true);
                        horse.equipSaddle(Items.SADDLE.getDefaultInstance(),net.minecraft.sounds.SoundSource.PLAYERS);
                        sp.serverLevel().addFreshEntity(horse); spawned.add(horse.getUUID());
                        if (!sp.startRiding(horse,true)) throw new IllegalStateException("Native horse mount refused");
                    }
                    if (!current.kind.equals("roll")) createTarget(sp);
                    observing = true; prepared.complete(null);
                } catch (Throwable error) { prepared.completeExceptionally(error); }
            });
            writeQuiet();
        }

        ServerPlayer serverPlayer() {
            var sp = mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID());
            if (sp == null) throw new IllegalStateException("Integrated server player disappeared"); return sp;
        }

        void equipShrinkingCharm(ServerPlayer sp) {
            var cap=Objects.requireNonNull(AccessoriesCapability.get(sp));
            ItemStack charm=BuiltInRegistries.ITEM.get(ResourceLocation.parse("artifacts:charm_of_shrinking")).getDefaultInstance();
            if(charm.isEmpty()) throw new IllegalStateException("Shrinking charm unavailable");
            // Preserve original component data in the snapshot; only the disposable
            // default charm is activated. Never toggle or rewrite the user's stack.
            for(var container:cap.getContainers().values()) for(int i=0;i<container.getSize();i++)
                if(container.getAccessories().getItem(i).is(charm.getItem())) container.getAccessories().setItem(i,ItemStack.EMPTY);
            var slots=AccessoriesAPI.getStackSlotTypes(sp,charm).stream()
                .filter(slot->!io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(slot.name()))
                .filter(slot->cap.getContainer(slot)!=null && cap.getContainer(slot).getSize()>0)
                .sorted(Comparator.comparing(slot->slot.name())).toList();
            if(slots.isEmpty()) throw new IllegalStateException("No actual accessory slot accepts shrinking charm");
            var slot=slots.getFirst(); var container=cap.getContainer(slot);
            container.getAccessories().setItem(0,charm); container.getCosmeticAccessories().setItem(0,ItemStack.EMPTY);
            container.renderOptions().set(0,true); row.put("shrinkingCharmSlot",slot.name()+":0");
            row.put("shrinkingCharmDisabled",charm.has(artifacts.registry.ModDataComponents.DISABLED_BY_TOGGLE.get()));
        }

        void prepareArena(ServerPlayer sp) {
            BlockPos floor = BlockPos.containing(origin).below();
            for (int x=-16;x<=16;x++) for (int z=-16;z<=16;z++) for (int y=0;y<=9;y++) {
                BlockPos at = floor.offset(x,y,z); BlockState wanted = y == 0 ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                arenaBefore.putIfAbsent(at,sp.serverLevel().getBlockState(at));
                if (!sp.serverLevel().getBlockState(at).equals(wanted)) sp.serverLevel().setBlock(at,wanted,3);
            }
        }

        void createTarget(ServerPlayer sp) {
            target = current.item.contains("pig_slayer") ? EntityType.PIG.create(sp.serverLevel()) : EntityType.ZOMBIE.create(sp.serverLevel());
            if (target == null) throw new IllegalStateException("Target factory unavailable");
            target.setNoAi(true); target.setPersistenceRequired();
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024); target.setHealth(1024); lastHealth = 1024;
            target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            double distance = current.pitch > 40 ? 1.05 : 2.0;
            if(shrink && !current.context.equals("mounted")) distance*=.5;
            Vec3 direction = Vec3.directionFromRotation(0,yaw);
            if(shrink && current.pitch>40 && current.kind.startsWith("use")) {
                // Vanilla arrows clip against target bounds inflated by .3.
                // A start inside those bounds has no entry face, so the old
                // .525-distance fixture hit the floor without hitting the mob.
                // Account for diagonal aim too; keep the muzzle .05 outside
                // that native box while its downward ray still enters above ground.
                double minimum=(target.getBbWidth()*.5+(double).3F+.05)/Math.max(Math.abs(direction.x),Math.abs(direction.z));
                distance=Math.max(distance,minimum);
                row.put("fixtureProjectileTargetMinimumDistance",minimum);
            }
            row.put("fixtureTargetDistance",distance);
            targetPosition = origin.add(direction.scale(distance)); target.moveTo(targetPosition.x,targetPosition.y,targetPosition.z,yaw+180,0);
            if (current.context.equals("mounted") && current.pitch == 0) {
                targetPosition = targetPosition.add(0,1,0);
                sp.serverLevel().setBlock(BlockPos.containing(targetPosition).below(),Blocks.BEDROCK.defaultBlockState(),3);
                target.setPos(targetPosition);
            }
            target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, Items.LEATHER_HELMET.getDefaultInstance());
            sp.serverLevel().addFreshEntity(target); spawned.add(target.getUUID());
        }

        void observeServer() {
            if (!observing || finishing) return;
            var sp = mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID()); if (sp == null) return;
            if(started && (sp.position().distanceToSqr(origin)>.0625 || sp.getDeltaMovement().horizontalDistanceSqr()>.0025)) movedDuringAction=true;
            observedServerSelected=sp.getInventory().selected;
            observedServerHeldItem=BuiltInRegistries.ITEM.getKey(sp.getMainHandItem().getItem()).toString();
            observedServerScale=sp.getScale(); serverAim=ProjectileTrajectoryProbe.aim(sp);
            CombatWorldRenderProbe.serverState(sp);
            projectileTraces.values().forEach(trace->trace.observe(target));
            boolean using=sp.isUsingItem();
            if(serverWasUsing && !using) serverUseEnded=true;
            serverWasUsing=using;
            serverUsed |= using; serverUseTicks = Math.max(serverUseTicks, sp.getTicksUsingItem());
            serverCooldown |= sp.getCooldowns().isOnCooldown(expected.getItem());
            sp.getActiveEffects().forEach(e -> playerEffects.add(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).toString()));
            Mob mob = target;
            if (mob != null) {
                float health = mob.getHealth(); if (health < lastHealth) totalDamage += lastHealth-health; lastHealth = health;
                targetDied |= !mob.isAlive(); targetBurned |= mob.isOnFire();
                mob.getActiveEffects().forEach(e -> targetEffects.add(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).toString()));
                if (mob.isAlive()) { mob.setPos(targetPosition); mob.setDeltaMovement(Vec3.ZERO); }
            }
        }

        void pose() {
            // F1 suppresses vanilla first-person hands when FirstPerson is absent.
            mc.options.hideGui = false;
            if(hasRagdoll && ragdollFirstPerson) NativeRagdollCamera.set(true);
            // A native ragdoll owns its camera and aligns local view rotation.
            // Forcing FIRST_PERSON while its firstperson config is false skips
            // the native torso eye anchor and can put the eye inside the body.
            if(!ExpandedRagdollRecovery.clientRestored(player)) return;
            mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.keyShift.setDown(current.crouch);
            if (hasFpm) Fpm.enable();
            player.setShiftKeyDown(current.crouch); player.setSprinting(false); player.setPose(current.crouch ? Pose.CROUCHING : Pose.STANDING);
            player.setYRot(yaw); player.yRotO = yaw; player.yBodyRot = player.yBodyRotO = yaw; player.yHeadRot = player.yHeadRotO = yaw;
            player.setXRot(current.pitch); player.xRotO = current.pitch;
            if (!started) {
                movementKeys.keySet().forEach(key -> key.setDown(false));
                player.getAbilities().flying = false;
                // Let native gravity establish onGround and the real horse seat
                // update passenger position after the authoritative teleport.
                player.setDeltaMovement(new Vec3(0,player.getDeltaMovement().y,0));
            }
        }

        void tick() {
            if (finishing) return;
            if (endPending) {
                if (finalized.isCompletedExceptionally()) finalized.join();
                if (finalized.isDone()) {
                    boolean seat=ExpandedRagdollRecovery.clientRestored(player),visibility=player.isInvisible()==caseExpectedInvisible;
                    if((!seat||!visibility)&&++caseCleanupClientTicks<100) return;
                    row.put("betweenCaseClientSeatReleased",seat);row.put("betweenCaseClientVisibilityRestored",visibility);
                    if(!seat||!visibility) {finish("FAIL","Native cleanup failed before next independent case");return;}
                    next();
                } return;
            }
            pose();
            if (!started) {
                prepTicks++; if (prepared.isCompletedExceptionally()) prepared.join();
                if(prepared.isDone() && !fixtureSelectedPacketSent) {
                    player.getInventory().selected=0;
                    player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(0));
                    fixtureSelectedPacketSent=true;
                }
                if(prepTicks==1 || prepTicks==20 || prepTicks==160 || prepTicks==161)
                    row.put("selectionReadiness",Map.of("prepTicks",prepTicks,"clientSelected",player.getInventory().selected,
                        "serverSelected",observedServerSelected,"clientHeldItem",BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString(),
                        "serverHeldItem",observedServerHeldItem,"expectedItem",BuiltInRegistries.ITEM.getKey(expected.getItem()).toString(),
                        "nativeSelectionPacketSent",fixtureSelectedPacketSent,"futureDone",prepared.isDone()));
                if (prepTicks > 160) throw new IllegalStateException("Weapon synchronization/readiness timed out: " + BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()));
                boolean mounted = current.context.equals("mounted");
                boolean grounded = mounted ? player.getVehicle() != null && player.getVehicle().onGround() : !player.isPassenger() && player.onGround();
                boolean liveTarget=current.kind.equals("roll") || (target!=null && target.isAlive() && !target.isRemoved()
                        && mc.level.getEntity(target.getId())!=null && mc.level.getEntity(target.getId()).isAlive());
                if(prepared.isDone() && !current.kind.equals("roll") && (target==null || !target.isAlive() || target.isRemoved()))
                    throw new IllegalStateException("Native target disappeared before action; difficulty="+mc.level.getDifficulty());
                boolean correctScale=!shrink || (Math.abs(player.getScale()-.5)<.001 && Math.abs(observedServerScale-.5)<.001);
                if (prepared.isDone() && prepTicks >= 12 && fixtureSelectedPacketSent && player.getInventory().selected==0 && observedServerSelected==0
                        && grounded && liveTarget && correctScale && player.getMainHandItem().is(expected.getItem())
                        && player.getAttackStrengthScale(0) >= 1 && !ExpandedCombatActions.attackActive(player)) {
                    row.put("nativeWorldReadiness", NativeWorldReadiness.evidence());
                    if (!NativeWorldReadiness.ready()) return;
                    if(weaponStateVerified==null) {
                        weaponStateVerified=new CompletableFuture<>();
                        var verification=weaponStateVerified;
                        mc.getSingleplayerServer().execute(()-> {
                            try {verification.complete(ExpandedNativeWeaponPreparation.verify(serverPlayer().getMainHandItem(),expected,"server"));}
                            catch(Throwable failure){verification.completeExceptionally(failure);}
                        });
                    }
                    if(!weaponStateVerified.isDone())return;
                    row.putAll(weaponStateVerified.join());
                    row.putAll(ExpandedNativeWeaponPreparation.verify(player.getMainHandItem(),expected,"client"));
                    row.put("selectionVerifiedAtAction",Map.of("client",player.getInventory().selected,"server",observedServerSelected,
                        "nativeSelectionPacketSent",fixtureSelectedPacketSent));
                    start();
                }
                return;
            }
            ticks++;
            if (current.kind.equals("melee")) {
                boolean isActive = ExpandedCombatActions.attackActive(player); sawActive |= isActive;
                if (sawActive && !isActive && firstInactive < 0) {
                    firstInactive = ticks;
                    // Observe the complete native layer, including its fade,
                    // then retain four ordinary ticks of recovery. No simulated
                    // time acceleration or animation-frame skipping is used.
                    endTick = Math.min(endTick, ticks + 4);
                }
                if (ticks >= endTick && isActive && ticks > endTick + 120) throw new IllegalStateException("Native attack did not end");
            } else if (current.kind.startsWith("use")) {
                maxUseElapsed = Math.max(maxUseElapsed, player.getTicksUsingItem());
                boolean using=player.isUsingItem();
                if(clientWasUsing && !using) clientUseEnded=true;
                clientWasUsing=using;
                boolean finite=useMax>0 && useMax<=1000 && !requiresUseRelease;
                if(finite && clientUseEnded && !using) mc.options.keyUse.setDown(false);
                int requiredServerCharge=useMax>0 && useMax<=1000?useMax:65;
                // A post-tick capture must occur while still charging. Releasing
                // on the capture tick made the old "active" bow image idle.
                boolean readyToRelease=!holdUse ? ticks>=captureAt+4 : finite ? serverUseEnded : screenshot && serverUseTicks>=requiredServerCharge;
                if (readyToRelease && !released) {
                    mc.options.keyUse.setDown(false);
                    if(!finite || !holdUse) mc.gameMode.releaseUsingItem(player);
                    released = true; endTick=Math.max(endTick,ticks+26);
                    row.put("releaseClientTick",ticks); row.put("serverUseElapsedAtRelease",serverUseTicks);
                    row.put("clientNaturalUseEndObserved",clientUseEnded); row.put("serverNaturalUseEndObserved",serverUseEnded);
                    if (expected.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(player.getMainHandItem())) {
                        row.put("chargedBeforeShot",true); mc.gameMode.useItem(player,InteractionHand.MAIN_HAND);
                    }
                }
                if(holdUse && !released && ticks>Math.max(400,finite?useMax+400:400))
                    throw new IllegalStateException("Native use did not reach server completion/charge: client="+maxUseElapsed+", server="+serverUseTicks+", duration="+useMax);
            } else if (current.kind.equals("roll")) {
                boolean rolling = ((RollingEntity)player).getRollManager().isRolling(); sawRoll |= rolling;
                if (sawRoll) Keybindings.roll.setDown(false);
                if (!sawRoll && ++rollWait > 160) throw new IllegalStateException("Native roll did not start within readiness window");
            }
        }

        void start() {
            nativeActionStarted=true;
            RagdollCameraProbe.resetHistory();
            player.getCooldowns().removeCooldown(expected.getItem());
            row.put("nativeGroundedAtStart",player.isPassenger() ? player.getVehicle().onGround() : player.onGround());
            row.put("nativeVehicle",player.getVehicle()==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString());
            row.put("nativeDifficulty",mc.level.getDifficulty().name());
            row.put("clientScaleAtStart",player.getScale()); row.put("serverScaleAtStart",observedServerScale);
            if(target!=null) { row.put("targetHealthAtStart",target.getHealth()); row.put("targetPositionAtStart",target.position().toString()); }
            actionOrigin = player.position(); endTick = 40; captureAt = 10;
            movedDuringAction=false;
            if (current.kind.equals("melee")) {
                var attributes = WeaponRegistry.getAttributes(player.getMainHandItem());
                var actualSignatures = attributes == null || attributes.attacks() == null ? List.<String>of()
                        : Arrays.stream(attributes.attacks()).map(a -> new com.google.gson.Gson().toJson(a)).toList();
                var plannedSignatures = nativePlannedAttackSignatures.get(current.item);
                if (!actualSignatures.equals(plannedSignatures) || current.attackIndex < 0 || current.attackIndex >= actualSignatures.size())
                    throw new IllegalStateException("Native prepared attack plan changed: requested=" + current.item
                            + ", index=" + current.attackIndex + ", planned=" + plannedSignatures + ", actual=" + actualSignatures);
                row.put("nativeAttackPlanVerified", true); row.put("nativeResolvedAttackCount", actualSignatures.size());
                row.put("nativeResolvedAttackSignature", actualSignatures.get(current.attackIndex));
                var desired = attributes.attacks()[current.attackIndex]; nativeCombo = -1;
                for (int i=0;i<attributes.attacks().length*2+2;i++) {
                    var hand = PlayerAttackHelper.getCurrentAttack(player,i);
                    if (hand != null && hand.attack() == desired) { nativeCombo = i; break; }
                }
                if (nativeCombo < 0) { complete("UNMET_PRECONDITION","Native condition filter excludes requested attack in supplied context"); return; }
                var swing = ExpandedCombatActions.startAttack(mc,nativeCombo);
                row.put("nativeCombo",nativeCombo); row.put("animation",swing.attackHand().attack().animation()); row.put("nativeDuration",swing.duration());
                row.put("attackSpeed",player.getAttributeValue(Attributes.ATTACK_SPEED));
                endTick = Math.max(45,(int)Math.ceil(swing.duration()*2)+12); captureAt = Math.max(4,swing.upswingTicks()+1);
            } else if (current.kind.equals("vanilla_hit")) {
                Entity clientTarget = target == null ? null : mc.level.getEntity(target.getId());
                if (clientTarget == null) throw new IllegalStateException("Target entity not synchronized");
                mc.gameMode.attack(player,clientTarget); player.swing(InteractionHand.MAIN_HAND); captureAt = 4; endTick = 18;
            } else if (current.kind.startsWith("use")) {
                useMax = player.getMainHandItem().getUseDuration(player);
                requiresUseRelease=player.getMainHandItem().useOnRelease(); row.put("nativeUseOnRelease",requiresUseRelease);
                // Finite native channels include EmberIre's 80-tick full charge,
                // configured Magiblade/Thunderbrand and ten-tick dart shooters.
                // Let those reach their actual finish boundary; ordinary bows
                // and Cataclysm's 72000-tick channels release after full charge.
                // Sample finite channels early enough to precede their native
                // completion, even when server/client ticks are offset. Long
                // channels still run to natural completion; bows still charge
                // at least 65 authoritative ticks before release.
                captureAt = useMax > 0 && useMax <= 1000 ? Math.max(1,Math.min(20,useMax/3)) : 60;
                endTick = captureAt + 30;
                String result;
                if (current.kind.equals("use_on")) {
                    BlockPos ground = BlockPos.containing(origin).below();
                    result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(ground).add(0,.5,0),Direction.UP,ground,false)).toString();
                } else result = mc.gameMode.useItem(player,InteractionHand.MAIN_HAND).toString();
                row.put("clientUseResult",result); row.put("useDuration",useMax);
                holdUse=player.isUsingItem(); clientWasUsing=holdUse;
                mc.options.keyUse.setDown(player.isUsingItem());
                if (!player.isUsingItem()) { captureAt = 8; endTick = 40; }
                row.put("manaAffordable", !current.item.startsWith("simplyswords:") || WeaponManaCost.canAfford(player, player.getMainHandItem()));
            } else if (current.kind.equals("roll")) {
                var manager = ((RollingEntity)player).getRollManager();
                row.put("rollAvailableAtStart",manager.isRollAvailable(player)); row.put("onGround",player.onGround());
                Keybindings.roll.setDown(true); captureAt = 8; endTick = 40;
            }
            started = true; ticks = 0;
        }

        void frame() throws IOException {
            if (!started || finishing || endPending) return;
            CombatWorldRenderProbe.frame(ticks,ExpandedCombatActions.attackActive(player));
            if(!ExpandedRagdollRecovery.clientRestored(player))
                RagdollCameraProbe.captureMismatchFrame(output,String.format(Locale.ROOT,"%05d-%s",index+1,current.name()));
            if (ticks >= 2) {
                frames++;
                int held = (player.getMainHandItem().isEmpty() ? 0 : 1) + (player.getOffhandItem().isEmpty() ? 0 : 1);
                maxBodyPasses = Math.max(maxBodyPasses,CombatRenderCounters.bodyPasses);
                maxBodyItems = Math.max(maxBodyItems,CombatRenderCounters.bodyItems);
                maxVanillaItems = Math.max(maxVanillaItems,CombatRenderCounters.vanillaItems);
                maxOtherItems = Math.max(maxOtherItems,CombatRenderCounters.otherItems);
                int renderedItems = CombatRenderCounters.bodyItems + CombatRenderCounters.vanillaItems + CombatRenderCounters.otherItems;
                if (renderedItems == 0 && !player.getMainHandItem().isEmpty()) zeroItemFrames++;
                boolean invalid = CombatRenderCounters.bodyPasses > 1;
                // A live native melee weapon must reach an item render path even if its
                // geometry is outside the camera. Two-handed animations can hide the
                // offhand, so require the primary weapon rather than both held stacks.
                // Use/release cases intentionally permit consumed or thrown items to vanish.
                boolean activeMelee = current.kind.equals("melee") && ExpandedCombatActions.attackActive(player);
                if (activeMelee) invalid |= renderedItems > held
                    || (!player.getMainHandItem().isEmpty() && renderedItems < 1);
                if (hasFpm && Fpm.owns()) invalid |= CombatRenderCounters.bodyPasses != 1
                    || CombatRenderCounters.vanillaItems != 0 || CombatRenderCounters.otherItems != 0 || CombatRenderCounters.bodyItems > held
                    || (activeMelee && !player.getMainHandItem().isEmpty() && CombatRenderCounters.bodyItems < 1);
                if (invalid) { violations++; if (!row.containsKey("firstRendererViolation")) row.put("firstRendererViolation",new LinkedHashMap<>(CombatRenderCounters.snapshot())); }
            }
            // Short native channels can finish before their advertised item duration:
            // Magiblade's manager ends at chargeDuration, while the item returns
            // chargeDuration + 5. Capture the first genuinely progressed use frame
            // instead of waiting one third of that upper bound. The real input,
            // server charge and natural completion are left untouched.
            boolean shortFiniteUse = current.kind.startsWith("use") && holdUse
                    && !requiresUseRelease && useMax > 0 && useMax <= 20;
            boolean progressedUseFrame = player.isUsingItem() && player.getTicksUsingItem() > 0;
            if (!screenshot && ((shortFiniteUse && progressedUseFrame) || ticks >= captureAt)) {
                row.put("activeCapturePolicy",shortFiniteUse ? "first_progressed_native_short_use_frame" : "scheduled_client_tick");
                row.put("activeCapturePlannedClientTick",captureAt);
                capture("active"); screenshot = true;
            }
            if (!recoveryScreenshot && ticks >= endTick-4 && (!holdUse || released)) { capture("recovery"); recoveryScreenshot = true; }
            if (ticks < endTick || (current.kind.equals("melee") && ExpandedCombatActions.attackActive(player))
                    || (current.kind.startsWith("use") && holdUse && !released)
                    || (current.kind.equals("roll") && !sawRoll)) return;
            row.put("frames",frames); row.put("rendererViolations",violations); row.put("activeLayerObserved",sawActive);
            row.put("maxRenderCounts",Map.of("bodyPasses",maxBodyPasses,"bodyItems",maxBodyItems,"vanillaItems",maxVanillaItems,"otherItems",maxOtherItems));
            row.put("zeroItemFrames",zeroItemFrames);
            row.put("firstInactiveTick",firstInactive); row.put("clientUseElapsed",maxUseElapsed);
            row.put("rollObserved",sawRoll); row.put("movementDistance",player.position().distanceTo(actionOrigin));
            String status = violations == 0 ? "CAPTURED" : "RENDER_FAILURE";
            if (current.kind.equals("melee") && !sawActive) status = "ACTION_FAILURE";
            if (current.kind.equals("roll") && !sawRoll) status = "ACTION_FAILURE";
            if (current.kind.startsWith("use") && holdUse && !Boolean.TRUE.equals(row.get("activeUsePoseObserved"))) status="ACTION_FAILURE";
            complete(status, "Native action timeline completed; inspect server outcome and images");
        }

        void capture(String phase) throws IOException {
            String filename = String.format(Locale.ROOT,"%05d-%s-%s.png",index+1,current.name(),phase);
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(filename)); }
            Map<String,Object> evidence = new LinkedHashMap<>(CombatRenderCounters.snapshot());
            evidence.put("screenshot",filename); evidence.put("tick",ticks); evidence.put("usingItem",player.isUsingItem()); evidence.put("useElapsed",player.getTicksUsingItem());
            if(phase.equals("active") && current.kind.startsWith("use") && holdUse)
                row.put("activeUsePoseObserved",player.isUsingItem() && player.getTicksUsingItem()>0);
            evidence.put("attackActive",ExpandedCombatActions.attackActive(player)); evidence.put("position",List.of(player.getX(),player.getY(),player.getZ()));
            evidence.put("pose",player.getPose().name()); evidence.put("invisible",player.isInvisible());
            evidence.put("clientScale",player.getScale()); evidence.put("serverScale",observedServerScale);
            evidence.put("authoritativeAim",serverAim);
            evidence.put("clientAim",Map.of("yaw",player.getYRot(),"pitch",player.getXRot(),"eyePosition",ProjectileTrajectoryProbe.vector(player.getEyePosition())));
            evidence.put("cameraAim",Map.of("yaw",mc.gameRenderer.getMainCamera().getYRot(),"pitch",mc.gameRenderer.getMainCamera().getXRot(),"position",ProjectileTrajectoryProbe.vector(mc.gameRenderer.getMainCamera().getPosition())));
            evidence.put("cameraType",mc.options.getCameraType().name());
            evidence.put("neaProjectilePose",NeaProjectilePoseProbe.snapshot(player.getUUID()));
            if(hasRagdoll) evidence.put("nativeRagdollFirstPersonConfig",NativeRagdollCamera.get());
            if(!ExpandedRagdollRecovery.clientRestored(player)) {
                evidence.put("nativeRagdollGeometry",RagdollRenderCounters.snapshot());
                evidence.put("ragdollCameraDiagnostics",RagdollCameraProbe.capture(mc.getTimer().getGameTimeDeltaPartialTick(false)));
            }
            evidence.put("vehicle",player.getVehicle()==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString());
            evidence.put("worldPlayerRenderDiagnostics",CombatWorldRenderProbe.snapshot());
            row.put(phase,evidence);
        }

        void complete(String status, String detail) {
            row.put("screenOverlayHistory",RagdollCameraProbe.history());
            row.put("worldPlayerRenderHistory",CombatWorldRenderProbe.history());
            mc.options.keyUse.setDown(false); Keybindings.roll.setDown(false); mc.gameMode.releaseUsingItem(player);
            row.put("status",status); row.put("detail",detail); observing = false;
            nativeActionStarted=false;
            caseCleanupAfterTick=Long.MAX_VALUE;caseCleanupWaiting=false;caseCleanupClientTicks=0;
            finalized = new CompletableFuture<>(); endPending = true;
            mc.getSingleplayerServer().execute(() -> {
                try {
                    observeServerFinal();
                    row.put("authoritativeAimFinal",serverAim);
                    row.put("projectileTrajectories",projectileTraces.values().stream().map(ProjectileTrajectoryProbe::snapshot).toList());
                    row.put("projectileTraceLimit",24);
                    row.put("targetDamage",totalDamage); row.put("targetDied",targetDied); row.put("targetBurned",targetBurned);
                    row.put("targetHealthLossScope","Preparation plus action/recovery; not damage-source attributed");
                    row.put("nativeAttackHitEvents",List.copyOf(nativeAttackEvents));
                    row.put("nativeActionDamageEvents",List.copyOf(nativeDamageEvents));
                    row.put("nativeActionDamageEventScope","Designated target damage events after native action start through recovery; inspect actual source and actor. Player-owned lingering abilities can still originate in an earlier case.");
                    row.put("targetEffects",new ArrayList<>(targetEffects)); row.put("playerEffects",new ArrayList<>(playerEffects));
                    row.put("spawnedEntityTypes",new TreeMap<>(spawnedTypes)); allSpawnedTypes.addAll(spawnedTypes.keySet());
                    row.put("abilitySpawnedEntityTypes",new TreeMap<>(abilitySpawnedTypes));
                    row.put("serverUseObserved",serverUsed); row.put("serverUseElapsed",serverUseTicks); row.put("serverCooldownObserved",serverCooldown);
                    boolean observed = totalDamage > 0 || !abilitySpawnedTypes.isEmpty() || !targetEffects.isEmpty() || !playerEffects.isEmpty() || serverCooldown;
                    row.put("serverEffectObserved",observed);
                    row.put("hitDamageObserved",totalDamage > 0);
                    if (current.kind.startsWith("use") && !observed) row.put("abilityOutcome","UNCONFIRMED: no damage, spawn, effect or cooldown observed; inspect prerequisites/locked state");
                    if ((current.kind.equals("melee") || current.kind.equals("vanilla_hit")) && totalDamage <= 0) row.put("hitOutcome","UNCONFIRMED: native action ran but no target health loss");
                    var server=serverPlayer();
                    boolean pending=ExpandedRagdollRecovery.pending(server),nativeActive=ExpandedRagdollRecovery.active(server);
                    boolean drain=pending||nativeActive||movedDuringAction||current.kind.equals("roll");
                    row.put("betweenCasePendingPoseObserved",pending);row.put("betweenCaseNativeSessionObserved",nativeActive);
                    row.put("betweenCaseMotionObserved",movedDuringAction);row.put("betweenCasePoseDrainServerTicks",drain?15:0);
                    caseCleanupScheduledTick=serverTicks;caseCleanupAfterTick=serverTicks+(drain?15:0);caseCleanupWaiting=true;
                    cleanupCaseIfReady();
                } catch (Throwable error) { finalized.completeExceptionally(error); }
            });
            writeQuiet();
            if ((index+1)%10 == 0) LogUtils.getLogger().info("Expanded combat {}/{} cases complete",index+1,cases.size());
        }

        void observeServerFinal() {
            Mob mob = target; if (mob != null && mob.getHealth() < lastHealth) totalDamage += lastHealth-mob.getHealth();
        }

        void cleanupCaseIfReady() {
            if(!caseCleanupWaiting||serverTicks<caseCleanupAfterTick) return;
            caseCleanupWaiting=false;
            try {
                var sp=serverPlayer();cleanupEntities(sp);sp.setDeltaMovement(Vec3.ZERO);
                ExpandedReactionTransition.displaced(sp);
                boolean pending=ExpandedRagdollRecovery.pending(sp);
                row.put("betweenCasePendingPoseDrained",!pending);
                row.put("betweenCasePoseDrainElapsedServerTicks",serverTicks-caseCleanupScheduledTick);
                row.put("betweenCaseServerSessionReleased",!ExpandedRagdollRecovery.active(sp));
                if(pending) throw new IllegalStateException("Native pending pose remained after its 15-server-tick drain");
                caseExpectedInvisible=sp.isInvisible();finalized.complete(null);
            } catch(Throwable error) {finalized.completeExceptionally(error);}
        }
        void cleanupEntities(ServerPlayer sp) {
            observing = false;
            ExpandedRagdollRecovery.release(sp);
            sp.stopRiding();
            for (UUID uuid : spawned) { Entity entity = sp.serverLevel().getEntity(uuid); if (entity != null && !(entity instanceof Player)) entity.discard(); }
            spawned.clear(); target = null;
        }
        void failCase(Throwable error) {
            LogUtils.getLogger().error("Expanded combat case {}", current == null ? "startup" : current.name(), error);
            if (row == null || finishing) { finish("FAIL",error.toString()); return; }
            if (endPending) { row.put("finalizationError",error.toString()); finish("FAIL","Case finalization/cleanup could not establish a clean next fixture: "+error); }
            else complete("CASE_FAILURE",error.toString());
        }

        void finish(String status, String detail) {
            if(finishing) return;
            finishStatus=status; finishDetail=detail; finishAfterServerTick=serverTicks+15;
            observing = false; CombatRenderCounters.active = false; RagdollRenderCounters.active=false;
            finishing = true;
            // Ragdoll Reactions can request a native client pose on the final
            // action tick and launch up to ten server ticks later. Drain that
            // request before releasing, restoring state, or accepting a new suite.
            mc.options.keyUse.setDown(false); Keybindings.roll.setDown(false);
            movementKeys.keySet().forEach(key->key.setDown(false));
            if(mc.gameMode!=null) mc.gameMode.releaseUsingItem(player);
            ((PlayerAttackAnimatable)player).stopAttackAnimation(0);
            try { write("RESTORING","Draining native pending pose requests"); }
            catch(IOException error) { LogUtils.getLogger().error("Expanded combat restore report",error); }
        }

        void restoreServerIfReady() {
            if(serverRestorationStarted) {
                // Keep sampling actual server authority after the native restore packet is processed.
                var restored=mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID());
                if(restored!=null) observedServerSelected=restored.getInventory().selected;
                return;
            }
            if(serverTicks<finishAfterServerTick) return;
            serverRestorationStarted=true;
            try {
                var sp=serverPlayer(); boolean grace=ExpandedReactionTransition.begin(sp); cleanupEntities(sp); sp.stopUsingItem();
                if (inventoryBefore != null) {
                        sp.removeAllEffects();
                        for (int i=0;i<inventoryBefore.size();i++) sp.getInventory().setItem(i,inventoryBefore.get(i).copy());
                        sp.getInventory().selected=originalServerSelected;observedServerSelected=sp.getInventory().selected;
                        if(shrink) {
                            var cap=Objects.requireNonNull(AccessoriesCapability.get(sp));
                            accessoriesBefore.forEach((name,saved)-> {
                                AccessoriesContainer container=cap.getContainers().get(name);
                                if(container==null || container.getSize()!=saved.items.size()) throw new IllegalStateException("Accessory slot shape changed during shrink fixture: "+name);
                                for(int i=0;i<saved.items.size();i++) { container.getAccessories().setItem(i,saved.items.get(i).copy()); container.getCosmeticAccessories().setItem(i,saved.cosmetic.get(i).copy()); }
                                for(int i=0;i<saved.renders.size();i++) container.renderOptions().set(i,saved.renders.get(i));
                            });
                        }
                        sp.setHealth(serverHealth); sp.getFoodData().setFoodLevel(serverFood); sp.getFoodData().setSaturation(serverSaturation);
                        sp.totalExperience = serverExperience; sp.experienceLevel = serverLevel; sp.experienceProgress = serverExperienceProgress;
                        for (var effect : effectsBefore) sp.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect));
                        sp.getAbilities().flying = serverFlying; sp.onUpdateAbilities();
                        sp.getServer().setDifficulty(originalDifficulty,true); sp.getServer().getWorldData().setDifficultyLocked(originalDifficultyLocked);
                        for (var block : arenaBefore.entrySet()) sp.serverLevel().setBlock(block.getKey(),block.getValue(),3);
                        sp.teleportTo(serverOrigin.x,serverOrigin.y,serverOrigin.z); sp.setYRot(yaw); sp.setXRot(pitch); sp.setShiftKeyDown(shiftKey);
                        sp.inventoryMenu.broadcastChanges();
                }
                boolean released=!ExpandedRagdollRecovery.active(sp);
                if(!released) throw new IllegalStateException("Native server ragdoll session remained after drain/release");
                cleanupGraceThroughServerTick=serverTicks+ExpandedReactionTransition.GRACE_TICKS+5;
                mc.execute(()-> {
                    if(inventoryBefore!=null) {
                        // Repair any incoming client/server selection mismatch using native server authority.
                        player.getInventory().selected=originalServerSelected;
                        player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(originalServerSelected));
                    }
                    report.put("serverInventoryRestored",true); report.put("nativeServerSessionReleased",released);
                    report.put("nativePendingPoseDrainServerTicks",15);
                    report.put("cleanupReactionGraceTicks",grace?ExpandedReactionTransition.GRACE_TICKS:0);
                    report.put("arenaBlocksRestored",arenaBefore.size());
                    if(originalDifficulty!=null) { report.put("difficultyRestored",originalDifficulty.name()); report.put("difficultyLockRestored",originalDifficultyLocked); }
                    report.put("originalAccessoriesAndToggleComponentsRestored",!shrink || !accessoriesBefore.isEmpty());
                    serverRestorationComplete=true;
                });
            } catch(Throwable error) {
                LogUtils.getLogger().error("Expanded combat server restore failed",error);
                mc.execute(()-> { finishStatus="FAIL"; finishDetail+="; server restoration failed: "+error; report.put("serverRestorationError",error.toString()); serverRestorationComplete=true; });
            }
        }

        void finishTick() {
            if(!serverRestorationComplete || serverTicks<cleanupGraceThroughServerTick || ++finishClientTicks<20) return;
            boolean seat=ExpandedRagdollRecovery.clientRestored(player), visible=player.isInvisible()==clientInvisible;
            if((!seat||!visible)&&finishClientTicks<100) return;
            try {
                report.put("originalClientSelected",originalClientSelected);report.put("originalServerSelected",originalServerSelected);
                report.put("originalClientServerSelectionMatched",originalServerSelected<0 || originalClientSelected==originalServerSelected);
                boolean selectionRestored=inventoryBefore==null || (player.getInventory().selected==originalServerSelected && observedServerSelected==originalServerSelected);
                report.put("selectionReconciledToOriginalServer",selectionRestored);
                report.put("clientOriginalSelectionRestored",player.getInventory().selected==originalClientSelected);
                report.put("serverSelectedRestored",inventoryBefore==null || observedServerSelected==originalServerSelected);
                if(!selectionRestored)throw new IllegalStateException("Selected slot did not restore to original server authority");
                report.put("nativeClientSeatReleased",seat); report.put("nativeClientVisibilityRestored",visible);
                report.put("cleanupReactionGraceExpired",serverTicks>=cleanupGraceThroughServerTick);
                if(!seat||!visible) { finishStatus="FAIL"; finishDetail+="; native client seat/visibility did not restore"; }
                mc.options.keyUse.setDown(useKey); mc.options.keyShift.setDown(shiftKey); Keybindings.roll.setDown(rollKey);
                movementKeys.forEach(KeyMapping::setDown);
                ((PlayerAttackProperties)player).setComboCount(combo); mc.options.setCameraType(camera); mc.options.hideGui=gui;
                if(hasFpm) Fpm.restore(fpmEnabled);
                if(hasRagdoll) { NativeRagdollCamera.set(originalRagdollFirstPerson); report.put("nativeRagdollCameraConfigRestored",NativeRagdollCamera.get()==originalRagdollFirstPerson); }
                player.setShiftKeyDown(shiftKey); player.setSprinting(sprint); player.setPose(pose); player.setPos(originalPosition); player.setDeltaMovement(motion);
                player.getAbilities().flying=clientFlying;
                player.setYRot(yaw); player.yRotO=yaw; player.setXRot(pitch); player.xRotO=pitch; player.yBodyRot=player.yBodyRotO=bodyYaw; player.yHeadRot=player.yHeadRotO=headYaw;
                report.put("restoredClientPosition",player.position().toString()); report.put("restoredClientMotion",player.getDeltaMovement().toString());
                report.put("clientSettingsRestored",true); report.put("allSpawnedEntityTypes",new TreeSet<>(allSpawnedTypes));
                write(finishStatus,finishDetail);
            } catch(Throwable error) {
                LogUtils.getLogger().error("Expanded combat client restore failed",error);
                report.put("clientRestorationError",error.toString());
                try { write("FAIL",finishDetail+"; client restoration failed: "+error); }
                catch(IOException reportError) { LogUtils.getLogger().error("Expanded combat failure report",reportError); }
            }
            finally { if(active==this) active=null; }
        }
        void writeQuiet() { try { write("RUNNING",""); } catch (IOException error) { throw new IllegalStateException(error); } }
        void write(String status,String detail) throws IOException {
            report.put("status",status); report.put("detail",detail); report.put("completedCases",rows.size());
            Files.writeString(output.resolve("expanded-combat-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }
    private static final class NativeRagdollCamera {
        static boolean get() { return dev.leo.sableplayerragdoll.neoforge.config.RagdollClientConfig.USE_FIRST_PERSON_CAMERA.get(); }
        static void set(boolean value) { dev.leo.sableplayerragdoll.neoforge.config.RagdollClientConfig.USE_FIRST_PERSON_CAMERA.set(value); }
    }
    /** Loaded only when FirstPerson exists; absent-mode attacks use the independent BC helper. */
    private static final class Fpm {
        static boolean enabled() { return dev.tr7zw.firstperson.api.FirstPersonAPI.isEnabled(); }
        static void enable() { dev.tr7zw.firstperson.api.FirstPersonAPI.setEnabled(true); }
        static void restore(boolean value) { dev.tr7zw.firstperson.api.FirstPersonAPI.setEnabled(value); }
        static boolean owns() { return dev.caleb.emfattachments.client.FirstPersonCombat.ownsCombatRendering(); }
    }
}
