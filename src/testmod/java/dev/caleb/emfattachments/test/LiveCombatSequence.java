package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.caleb.emfattachments.client.FirstPersonCombat;
import dev.caleb.emfattachments.config.CompatConfig;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.bettercombat.client.BetterCombatClientMod;
import net.bettercombat.client.animation.PlayerAttackAnimatable;
import net.bettercombat.config.TriStateAuto;
import net.bettercombat.logic.AnimatedHand;
import net.bettercombat.logic.PlayerAttackHelper;
import net.bettercombat.logic.PlayerAttackProperties;
import net.bettercombat.logic.WeaponRegistry;
import dev.caleb.emfattachments.test.mixin.CombatMinecraftAccess;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Isolated client-only replay of installed attack keyframes through the real camera. */
public final class LiveCombatSequence {
    private static Session active;
    private static final int[] CAPTURE_TICKS = {5, 18, 34, 48};
    private static final String[] PHASES = {"windup", "strike", "recovery", "expired"};
    private static final Scenario[] SCENARIOS = scenarios();
    private LiveCombatSequence() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(LiveCombatSequence::tick);
        NeoForge.EVENT_BUS.addListener(LiveCombatSequence::beforeFrame);
        NeoForge.EVENT_BUS.addListener(LiveCombatSequence::afterFrame);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) {
            if (mc.player != active.player || mc.level == null || mc.screen != null) {
                active.finish("ABORTED", "World changed or a screen was opened"); active = null; return;
            }
            try { active.tick(); }
            catch (Throwable failure) { active.finish("FAIL", failure.toString()); active = null; }
            return;
        }
        if (mc.player == null || mc.level == null || mc.screen != null || LiveFirstPersonSequence.isActive()) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-live-combat.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
            if (!game.endsWith(Path.of(".local", "client")) || !mc.hasSingleplayerServer())
                throw new IllegalStateException("Combat capture requires disposable .local/client singleplayer");
            if (!ModList.get().isLoaded("firstperson") || !ModList.get().isLoaded("bettercombat"))
                throw new IllegalStateException("FirstPerson and Better Combat are required");
            if (FirstPersonCombat.isAttackActive(mc.player) || mc.player.isUsingItem()) return;
            String[] request = Files.readString(trigger).trim().split("\\R");
            String label = request[0].replaceAll("[^a-zA-Z0-9._-]", "_");
            boolean neutralTranslation = request.length > 1 && request[1].trim().equals("neutral_translation");
            Files.delete(trigger);
            active = new Session(mc, label.isBlank() ? "live-combat" : label, neutralTranslation);
            CombatRenderCounters.active = true;
            active.configureTranslation();
            active.startScenario();
        } catch (Throwable failure) {
            if (active != null) active.finish("FAIL", failure.toString());
            active = null; CombatRenderCounters.active = false;
            LogUtils.getLogger().error("Live combat startup failed", failure);
        }
    }

    private static void beforeFrame(RenderFrameEvent.Pre event) {
        if (active != null) CombatRenderCounters.reset();
    }

    private static void afterFrame(RenderFrameEvent.Post event) {
        if (active == null) return;
        try {
            active.observe();
            if (!active.started) return;
            if (active.captureIndex < CAPTURE_TICKS.length && active.ticks >= active.captureTicks[active.captureIndex]) {
                active.capture(); active.captureIndex++;
            }
            if (active.ticks >= active.endTick && active.captureIndex == CAPTURE_TICKS.length) {
                if (FirstPersonCombat.isAttackActive(active.player)) throw new AssertionError("Attack still active after " + active.endTick + " ticks");
                if (++active.scenarioIndex == SCENARIOS.length) {
                    String status = active.violations.isEmpty() ? "CAPTURED" : "FAIL";
                    active.finish(status, "Review 36 attack screenshots and frame-level renderer counters"); active = null;
                } else active.startScenario();
            }
        } catch (Throwable failure) { active.finish("FAIL", failure.toString()); active = null; }
    }

    private record Scenario(String name, String animation, String item, AnimatedHand hand, float pitch) {}
    private static Scenario[] scenarios() {
        List<Scenario> scenarios = new ArrayList<>();
        for (int view = 0; view < 3; view++) {
            float pitch = new float[]{0, -50, 50}[view];
            String suffix = new String[]{"level", "up", "down"}[view];
            scenarios.add(new Scenario("one_handed_slam_" + suffix, "bettercombat:one_handed_slam", "minecraft:mace", AnimatedHand.MAIN_HAND, pitch));
            scenarios.add(new Scenario("two_handed_slam_" + suffix, "bettercombat:two_handed_slam", "simplyswords:diamond_greathammer", AnimatedHand.TWO_HANDED, pitch));
            scenarios.add(new Scenario("two_handed_stab_" + suffix, "bettercombat:two_handed_stab_left", "simplyswords:diamond_claymore", AnimatedHand.TWO_HANDED, pitch));
        }
        return scenarios.toArray(Scenario[]::new);
    }

    private static final class Session {
        final Minecraft mc;
        final LocalPlayer player;
        final Path output;
        final CameraType camera;
        final boolean gui, enabled, shiftKey, shift, sprint;
        final float yaw, pitch, bodyYaw, headYaw;
        final Pose pose;
        final Vec3 position, motion;
        final ItemStack main, off;
        final TriStateAuto bcMode;
        final int combo;
        final boolean neutralTranslation;
        final double armX, armY, armZ, lookUpZ;
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<Map<String, Object>> captures = new ArrayList<>();
        final List<Map<String, Object>> violations = new ArrayList<>();
        int scenarioIndex, ticks, captureIndex, ownedFrames, correctOwnedFrames;
        int endTick = 52;
        int[] captureTicks = CAPTURE_TICKS.clone();
        boolean started;
        int preparationTicks;
        String resolvedWeapon;
        ItemStack testStack;
        CompletableFuture<Void> equipmentReady;
        volatile ItemStack serverMain, serverOff;

        Session(Minecraft mc, String label, boolean neutralTranslation) throws IOException {
            this.mc = mc; player = mc.player;
            this.neutralTranslation = neutralTranslation;
            armX = CompatConfig.armPositionX(); armY = CompatConfig.armPositionY(); armZ = CompatConfig.armPositionZ(); lookUpZ = CompatConfig.lookUpZShift();
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label); Files.createDirectories(output);
            camera = mc.options.getCameraType(); gui = mc.options.hideGui; enabled = FirstPersonAPI.isEnabled();
            shiftKey = mc.options.keyShift.isDown(); shift = player.isShiftKeyDown(); sprint = player.isSprinting();
            yaw = player.getYRot(); pitch = player.getXRot(); bodyYaw = player.yBodyRot; headYaw = player.yHeadRot;
            pose = player.getPose(); position = player.position(); motion = player.getDeltaMovement();
            main = player.getMainHandItem().copy(); off = player.getOffhandItem().copy();
            bcMode = BetterCombatClientMod.config.firstPersonAnimations;
            combo = ((PlayerAttackProperties) player).getComboCount();
            report.put("label", label); report.put("resourcePacks", mc.getResourcePackRepository().getSelectedIds());
            report.put("scope", "Real local camera and native Better Combat startUpswing with installed weapons/combo attributes in disposable singleplayer. Temporary YES mode deliberately challenges duplicate body rendering.");
            report.put("captures", captures); report.put("violations", violations);
            report.put("neutralTranslation",neutralTranslation);
            report.put("originalArmTranslation",Map.of("x",armX,"y",armY,"z",armZ,"lookUpZShift",lookUpZ));
            write("RUNNING", "");
        }

        void configureTranslation() {
            if (neutralTranslation) {
                CompatConfig.ARM_POSITION_X.set(0.0); CompatConfig.ARM_POSITION_Y.set(0.0);
                CompatConfig.ARM_POSITION_Z.set(0.0); CompatConfig.LOOK_UP_Z_SHIFT.set(0.0);
            }
            report.put("appliedArmTranslation",Map.of("x",CompatConfig.armPositionX(),"y",CompatConfig.armPositionY(),
                    "z",CompatConfig.armPositionZ(),"lookUpZShift",CompatConfig.lookUpZShift()));
        }

        void startScenario() {
            ticks = 0; captureIndex = 0; preparationTicks = 0; started = false; applyPose();
            Scenario scenario = SCENARIOS[scenarioIndex];
            ItemStack stack = BuiltInRegistries.ITEM.get(ResourceLocation.parse(scenario.item)).getDefaultInstance();
            if (!hasAttack(stack, scenario.animation)) {
                stack = BuiltInRegistries.ITEM.stream().map(item -> item.getDefaultInstance())
                        .filter(item -> hasAttack(item, scenario.animation)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No registered weapon uses " + scenario.animation));
            }
            testStack = stack.copy(); resolvedWeapon = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            // The server must apply the real weapon attribute modifiers. Replacing
            // local stacks every tick can cancel the native swing and leaves an
            // unarmed attack-speed attribute, so this fixture equips exactly once.
            equipmentReady = new CompletableFuture<>();
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var serverPlayer = mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID());
                    if (serverPlayer == null) throw new IllegalStateException("Disposable server player absent");
                    if (serverMain == null) {
                        serverMain = serverPlayer.getMainHandItem().copy(); serverOff = serverPlayer.getOffhandItem().copy();
                    }
                    serverPlayer.setItemInHand(InteractionHand.MAIN_HAND, testStack.copy());
                    serverPlayer.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    serverPlayer.inventoryMenu.broadcastChanges();
                    equipmentReady.complete(null);
                } catch (Throwable failure) { equipmentReady.completeExceptionally(failure); }
            });
        }

        private static boolean hasAttack(ItemStack stack, String animation) {
            var attributes = WeaponRegistry.getAttributes(stack);
            if (attributes == null || attributes.attacks() == null) return false;
            return java.util.Arrays.stream(attributes.attacks()).anyMatch(attack -> attack.animation().equals(animation));
        }

        void startAttack() {
            Scenario scenario = SCENARIOS[scenarioIndex];
            var attributes = WeaponRegistry.getAttributes(player.getMainHandItem());
            if (attributes == null) throw new IllegalStateException("Synchronized weapon lost native attributes");
            boolean found = false;
            for (int index = 0; index < 12; index++) {
                var attack = PlayerAttackHelper.getCurrentAttack(player, index);
                if (attack != null && attack.attack().animation().equals(scenario.animation)) {
                    ((PlayerAttackProperties) player).setComboCount(index); found = true; break;
                }
            }
            if (!found) throw new IllegalStateException("Native weapon has no requested animation: " + scenario.item + " " + scenario.animation);
            BetterCombatClientMod.config.firstPersonAnimations = TriStateAuto.YES;
            try { ((CombatMinecraftAccess) mc).emfattachments$startUpswing(attributes); }
            finally { BetterCombatClientMod.config.firstPersonAnimations = bcMode; }
            var swing = ((CombatMinecraftAccess) mc).emfattachments$ongoingSwing();
            if (swing == null || !swing.attackHand().attack().animation().equals(scenario.animation))
                throw new IllegalStateException("Native upswing did not start requested attack");
            endTick = Math.max(52, (int) Math.ceil(swing.duration() * 2) + 8);
            captureTicks = new int[]{Math.max(3, swing.upswingTicks() / 2), swing.upswingTicks() + 2,
                    (int) Math.ceil(swing.duration() * 1.3), endTick - 4};
            started = true;
        }

        void applyPose() {
            Scenario scenario = SCENARIOS[scenarioIndex];
            mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.hideGui = true; mc.options.keyShift.setDown(false);
            FirstPersonAPI.setEnabled(true); player.setShiftKeyDown(false); player.setSprinting(false); player.setPose(Pose.STANDING);
            player.setPos(position); player.setDeltaMovement(Vec3.ZERO);
            player.setYRot(yaw); player.yRotO = yaw; player.yBodyRot = player.yBodyRotO = yaw; player.yHeadRot = player.yHeadRotO = yaw;
            player.setXRot(scenario.pitch); player.xRotO = scenario.pitch;
        }

        void tick() {
            applyPose();
            if (!started) {
                preparationTicks++;
                if (equipmentReady.isCompletedExceptionally()) equipmentReady.join();
                if (preparationTicks % 10 == 0) report.put("lastPreparation", Map.of(
                        "scenario", SCENARIOS[scenarioIndex].name, "ticks", preparationTicks,
                        "expectedItem", resolvedWeapon,
                        "actualItem", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString(),
                        "expectedComponents", testStack.getComponents().toString(),
                        "actualComponents", player.getMainHandItem().getComponents().toString(),
                        "attackSpeed", player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED),
                        "cooldown", player.getAttackStrengthScale(0), "serverTaskDone", equipmentReady.isDone()));
                if (preparationTicks > 100) throw new IllegalStateException("Authoritative weapon/attributes did not synchronize: expected="
                        + resolvedWeapon + ", actual=" + BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem())
                        + ", cooldown=" + player.getAttackStrengthScale(0));
                if (preparationTicks >= 8 && equipmentReady.isDone()
                        // Simply Swords enriches synchronized stacks with native
                        // components. Match the actual item, then use its current
                        // authoritative attributes; a pristine component map is
                        // neither expected nor required.
                        && player.getMainHandItem().is(testStack.getItem())
                        && player.getAttackStrengthScale(0) >= 1) startAttack();
                return;
            }
            ticks++;
        }

        void observe() {
            if (!started || ticks < 3 || !FirstPersonCombat.ownsCombatRendering()) return;
            ownedFrames++;
            if (CombatRenderCounters.bodyPasses == 1 && CombatRenderCounters.bodyItems == 1
                    && CombatRenderCounters.vanillaItems == 0 && CombatRenderCounters.otherItems == 0) correctOwnedFrames++;
            else if (violations.size() < 20) {
                Map<String, Object> violation = new LinkedHashMap<>(CombatRenderCounters.snapshot());
                violation.put("scenario", SCENARIOS[scenarioIndex].name); violation.put("tick", ticks); violations.add(violation);
            }
        }

        void capture() throws IOException {
            Scenario scenario = SCENARIOS[scenarioIndex];
            String file = String.format(Locale.ROOT, "%02d-%s-%s.png", captures.size() + 1, scenario.name, PHASES[captureIndex]);
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(file)); }
            Map<String, Object> capture = new LinkedHashMap<>(CombatRenderCounters.snapshot());
            capture.put("screenshot", file); capture.put("scenario", scenario.name); capture.put("animation", scenario.animation);
            capture.put("weapon", resolvedWeapon); capture.put("pitch", scenario.pitch); capture.put("phase", PHASES[captureIndex]);
            capture.put("attackSpeed", player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED));
            capture.put("tick", ticks); capture.put("attackActive", FirstPersonCombat.isAttackActive(player));
            capture.put("ownsCombatRendering", FirstPersonCombat.ownsCombatRendering());
            capture.put("playerAnimatorMode", ((IAnimatedPlayer) player).playerAnimator_getAnimation().getFirstPersonMode().name());
            captures.add(capture); write("RUNNING", "");
        }

        void finish(String status, String detail) {
            CombatRenderCounters.active = false;
            try {
                CompatConfig.ARM_POSITION_X.set(armX); CompatConfig.ARM_POSITION_Y.set(armY);
                CompatConfig.ARM_POSITION_Z.set(armZ); CompatConfig.LOOK_UP_Z_SHIFT.set(lookUpZ);
                report.put("armTranslationRestored",CompatConfig.armPositionX()==armX && CompatConfig.armPositionY()==armY
                        && CompatConfig.armPositionZ()==armZ && CompatConfig.lookUpZShift()==lookUpZ);
                ((PlayerAttackAnimatable) player).stopAttackAnimation(0);
                BetterCombatClientMod.config.firstPersonAnimations = bcMode;
                ((PlayerAttackProperties) player).setComboCount(combo);
                player.setItemInHand(InteractionHand.MAIN_HAND, main); player.setItemInHand(InteractionHand.OFF_HAND, off);
                if (serverMain != null) mc.getSingleplayerServer().execute(() -> {
                    var serverPlayer = mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID());
                    if (serverPlayer != null) {
                        serverPlayer.setItemInHand(InteractionHand.MAIN_HAND, serverMain.copy());
                        serverPlayer.setItemInHand(InteractionHand.OFF_HAND, serverOff.copy());
                        serverPlayer.inventoryMenu.broadcastChanges();
                    }
                });
                mc.options.setCameraType(camera); mc.options.hideGui = gui; mc.options.keyShift.setDown(shiftKey); FirstPersonAPI.setEnabled(enabled);
                player.setShiftKeyDown(shift); player.setSprinting(sprint); player.setPose(pose);
                player.setPos(position); player.setDeltaMovement(motion);
                player.setYRot(yaw); player.yRotO = yaw; player.setXRot(pitch); player.xRotO = pitch;
                player.yBodyRot = player.yBodyRotO = bodyYaw; player.yHeadRot = player.yHeadRotO = headYaw;
                report.put("ownedFrames", ownedFrames); report.put("correctSingleBodyAndWeaponFrames", correctOwnedFrames);
                report.put("settingsRestored", true); write(status, detail);
                LogUtils.getLogger().info("Live combat {}: {} screenshots, {}/{} single body/weapon frames", status, captures.size(), correctOwnedFrames, ownedFrames);
            } catch (Throwable failure) { LogUtils.getLogger().error("Live combat restoration failed", failure); }
        }

        void write(String status, String detail) throws IOException {
            report.put("status", status); report.put("detail", detail);
            Files.writeString(output.resolve("combat-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }
}
