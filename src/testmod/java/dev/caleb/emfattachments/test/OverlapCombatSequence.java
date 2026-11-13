package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import dev.caleb.emfattachments.client.FirstPersonCombat;
import dev.caleb.emfattachments.config.CompatConfig;
import dev.caleb.emfattachments.test.mixin.CombatMinecraftAccess;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.bettercombat.client.BetterCombatClientMod;
import net.bettercombat.client.animation.PlayerAttackAnimatable;
import net.bettercombat.config.TriStateAuto;
import net.bettercombat.logic.PlayerAttackHelper;
import net.bettercombat.logic.PlayerAttackProperties;
import net.bettercombat.logic.WeaponRegistry;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import strm.emfcompat.core.EMFCompatConfig;
import strm.emfcompat.core.EMFCompatCore;

/** Bounded actual-camera ablations. Triggered only in an explicitly opted-in disposable client. */
@EventBusSubscriber(modid = "emfattachments_test", value = Dist.CLIENT)
public final class OverlapCombatSequence {
    private static Session active;
    private OverlapCombatSequence() {}

    @SubscribeEvent public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("emfattachments.overlapAudit")) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (active != null) {
                if (mc.player != active.player || mc.level == null || mc.screen != null)
                    throw new IllegalStateException("World or screen changed during overlap sequence");
                active.tick(); return;
            }
            if (mc.player == null || mc.screen != null || CombatRenderCounters.active || !NativeWorldReadiness.ready()) return;
            Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-overlap-combat.txt");
            if (!Files.isRegularFile(trigger)) return;
            if (!mc.hasSingleplayerServer() || !mc.getUser().getName().equals("AttachmentTest")
                    || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client")))
                throw new IllegalStateException("Overlap sequence requires disposable offline AttachmentTest client");
            for (String id : List.of("emfattachments", "firstperson", "bettercombat", "emf_compat_core", "entity_model_features"))
                if (!ModList.get().isLoaded(id)) throw new IllegalStateException("Missing overlap dependency: " + id);
            String label = Files.readString(trigger).trim();
            if (!label.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Use one simple run label");
            Files.delete(trigger); active = new Session(mc, label); active.prepare();
        } catch (Throwable error) {
            if (active != null) active.finish("FAIL", error.toString());
            else com.mojang.logging.LogUtils.getLogger().error("Overlap sequence could not start", error);
        }
    }
    @SubscribeEvent public static void beforeFrame(RenderFrameEvent.Pre ignored) {
        if (active != null) { OverlapCombatProbe.beginFrame(); CombatRenderCounters.reset(); }
    }
    @SubscribeEvent public static void afterFrame(RenderFrameEvent.Post ignored) {
        if (active == null) return;
        try { active.frame(); }
        catch (Throwable error) { active.finish("FAIL", error.toString()); }
    }
    private record Case(String animation, String item, boolean crouch, String variant, String core) {
        String name() { return animation.substring(animation.indexOf(':') + 1) + "-" + (crouch ? "crouch" : "stand") + "-" + variant + "-" + core; }
    }
    private static List<Case> cases() {
        List<Case> result = new ArrayList<>();
        String[][] attacks = {{"bettercombat:one_handed_slam", "minecraft:mace"},
                {"bettercombat:two_handed_slam", "simplyswords:diamond_greathammer"},
                {"bettercombat:two_handed_stab_left", "simplyswords:diamond_claymore"}};
        for (String[] attack : attacks) for (boolean crouch : new boolean[]{false, true})
            for (String variant : List.of("legacy_y", "candidate", "tilt_off"))
                result.add(new Case(attack[0], attack[1], crouch, variant, "enabled"));
        for (String core : List.of("crouch_fix_off", "global_off"))
            for (String variant : List.of("legacy_y", "candidate"))
                result.add(new Case(attacks[0][0], attacks[0][1], true, variant, core));
        return result;
    }
    private static final class Session {
        final Minecraft mc; final LocalPlayer player; final Path output;
        final List<Case> cases = cases(); final List<Map<String, Object>> captures = new ArrayList<>();
        final List<String> failures = new ArrayList<>(); final Map<String, Object> report = new LinkedHashMap<>();
        final CameraType camera; final boolean gui, fpmEnabled, shiftKey, shift, sprint, coreEnabled;
        final float yaw, pitch, bodyYaw, headYaw; final Vec3 position, motion; final Pose pose;
        final ItemStack main, off; final int combo; final TriStateAuto bcMode; final double bodyOffset;
        final Map<String, Boolean> coreBooleans;
        final long deadline = System.nanoTime() + 600_000_000_000L;
        CompletableFuture<Void> equipment; ItemStack stack; volatile ItemStack serverMain, serverOff;
        int index, ticks, preparing, captureIndex, endTick, observedFrames, hookFrames;
        int[] captureTicks; boolean started, finished;

        Session(Minecraft mc, String label) throws Exception {
            this.mc = mc; player = mc.player;
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);
            if (Files.exists(output)) throw new IllegalArgumentException("Refusing to replace overlap evidence");
            Files.createDirectories(output);
            camera = mc.options.getCameraType(); gui = mc.options.hideGui; fpmEnabled = FirstPersonAPI.isEnabled();
            shiftKey = mc.options.keyShift.isDown(); shift = player.isShiftKeyDown(); sprint = player.isSprinting();
            yaw = player.getYRot(); pitch = player.getXRot(); bodyYaw = player.yBodyRot; headYaw = player.yHeadRot;
            position = player.position(); motion = player.getDeltaMovement(); pose = player.getPose();
            main = player.getMainHandItem().copy(); off = player.getOffhandItem().copy();
            combo = ((PlayerAttackProperties) player).getComboCount(); bcMode = BetterCombatClientMod.config.firstPersonAnimations;
            bodyOffset = CompatConfig.combatBodyYOffset(); coreEnabled = EMFCompatCore.isCompatEnabled();
            coreBooleans = new LinkedHashMap<>(EMFCompatConfig.get().booleans);
            report.put("label", label); report.put("captures", captures); report.put("failures", failures);
            report.put("plannedCases", cases.stream().map(Case::name).toList());
            report.put("resourcePacks", mc.getResourcePackRepository().getSelectedIds());
            report.put("originalBodyOffset", bodyOffset);
            report.put("scope", "Native Better Combat startUpswing and real FirstPerson world/body draws. Legacy only overrides the new Y ownership query; tilt-off only bypasses our body ROTATION channel. Runtime matrices are observed around PAL and Core, not reconstructed.");
            report.put("deadlineSeconds", 600);
            OverlapCombatProbe.active = true; CombatRenderCounters.active = true;
        }
        void prepare() throws Exception {
            Case current = cases.get(index); ticks = preparing = captureIndex = 0; started = false;
            OverlapCombatProbe.legacyY = current.variant.equals("legacy_y");
            OverlapCombatProbe.tiltOff = current.variant.equals("tilt_off");
            CompatConfig.COMBAT_BODY_Y_OFFSET.set(bodyOffset);
            EMFCompatConfig.setBoolean("core.enabled", !current.core.equals("global_off"));
            EMFCompatConfig.setBoolean("core.crouchFix", !current.core.equals("crouch_fix_off"));
            EMFCompatCore.setCompatEnabled(!current.core.equals("global_off"));
            stack = BuiltInRegistries.ITEM.get(ResourceLocation.parse(current.item)).getDefaultInstance();
            if (!hasAttack(stack, current.animation)) stack = BuiltInRegistries.ITEM.stream().map(item -> item.getDefaultInstance())
                    .filter(item -> hasAttack(item, current.animation)).findFirst().orElseThrow();
            ItemStack equipped = stack.copy();
            equipment = CompletableFuture.runAsync(() -> {
                var sp = mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID());
                if (serverMain == null) { serverMain = sp.getMainHandItem().copy(); serverOff = sp.getOffhandItem().copy(); }
                sp.setItemInHand(InteractionHand.MAIN_HAND, equipped); sp.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                sp.inventoryMenu.broadcastChanges();
            }, mc.getSingleplayerServer());
            write("RUNNING", current.name());
        }
        static boolean hasAttack(ItemStack stack, String name) {
            var attributes = WeaponRegistry.getAttributes(stack);
            return attributes != null && attributes.attacks() != null
                    && Arrays.stream(attributes.attacks()).anyMatch(attack -> attack.animation().equals(name));
        }
        void tick() throws Exception {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Overlap sequence deadline exceeded");
            Case current = cases.get(index);
            mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.hideGui = true;
            mc.options.keyShift.setDown(current.crouch); FirstPersonAPI.setEnabled(true);
            player.setShiftKeyDown(current.crouch); player.setSprinting(false);
            player.setPose(current.crouch ? Pose.CROUCHING : Pose.STANDING);
            player.setPos(position); player.setDeltaMovement(Vec3.ZERO);
            player.setYRot(yaw); player.yRotO = yaw; player.yBodyRot = player.yBodyRotO = yaw; player.yHeadRot = player.yHeadRotO = yaw;
            player.setXRot(35); player.xRotO = 35;
            if (!started) {
                if (++preparing > 120) throw new IllegalStateException("Weapon sync or attack cooldown did not become ready");
                if (equipment.isCompletedExceptionally()) equipment.join();
                if (preparing < 10 || !equipment.isDone() || !player.getMainHandItem().is(stack.getItem()) || player.getAttackStrengthScale(0) < 1) return;
                var attributes = WeaponRegistry.getAttributes(player.getMainHandItem());
                boolean found = false;
                for (int comboIndex = 0; comboIndex < 12; comboIndex++) {
                    var attack = PlayerAttackHelper.getCurrentAttack(player, comboIndex);
                    if (attack != null && attack.attack().animation().equals(current.animation)) {
                        ((PlayerAttackProperties) player).setComboCount(comboIndex); found = true; break;
                    }
                }
                if (!found) throw new IllegalStateException("Native weapon no longer has requested attack");
                BetterCombatClientMod.config.firstPersonAnimations = TriStateAuto.YES;
                try { ((CombatMinecraftAccess) mc).emfattachments$startUpswing(attributes); }
                finally { BetterCombatClientMod.config.firstPersonAnimations = bcMode; }
                var swing = ((CombatMinecraftAccess) mc).emfattachments$ongoingSwing();
                if (swing == null || !swing.attackHand().attack().animation().equals(current.animation)) throw new IllegalStateException("Wrong native attack");
                endTick = Math.max(52, (int) Math.ceil(swing.duration() * 2) + 8);
                captureTicks = new int[]{Math.max(3, swing.upswingTicks() / 2), swing.upswingTicks() + 2,
                        (int) Math.ceil(swing.duration() * 1.3), endTick - 4};
                started = true; return;
            }
            ticks++;
        }
        void frame() throws Exception {
            if (!started) return;
            boolean owned = FirstPersonCombat.ownsCombatRendering();
            Map<String, Object> probe = OverlapCombatProbe.snapshot();
            List<?> draws = (List<?>) probe.get("draws");
            if (owned) {
                observedFrames++; if (!draws.isEmpty()) hookFrames++;
                if (draws.size() != 1 && failures.size() < 12) failures.add(cases.get(index).name() + ": expected one complete PAL/Core draw, got " + draws.size());
                if (CombatRenderCounters.bodyPasses != 1 && failures.size() < 12) failures.add(cases.get(index).name() + ": body pass count " + CombatRenderCounters.bodyPasses);
            }
            if (captureIndex < captureTicks.length && ticks >= captureTicks[captureIndex]) {
                Case current = cases.get(index); String file = String.format(Locale.ROOT, "%03d-%s-%d.png", captures.size() + 1, current.name(), captureIndex);
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(file)); }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("case", current.name()); row.put("animation", current.animation); row.put("variant", current.variant); row.put("core", current.core);
                row.put("tick", ticks); row.put("phase", List.of("windup", "strike", "recovery", "expired").get(captureIndex));
                row.put("crouching", player.isCrouching()); row.put("ownsCombatRendering", owned); row.put("attackActive", FirstPersonCombat.isAttackActive(player));
                row.put("bodyOffset", CompatConfig.combatBodyYOffset()); row.put("coreEnabled", EMFCompatCore.isCompatEnabled());
                row.put("crouchFixEnabled", EMFCompatConfig.getBoolean("core.crouchFix", true));
                row.put("probe", probe); row.put("renderCounters", CombatRenderCounters.snapshot()); row.put("screenshot", file);
                captures.add(row); captureIndex++; write("RUNNING", current.name());
            }
            if (ticks >= endTick && captureIndex == captureTicks.length) {
                if (FirstPersonCombat.isAttackActive(player)) throw new IllegalStateException("Native attack failed to expire");
                if (++index == cases.size()) finish(failures.isEmpty() && hookFrames > 0 ? "CAPTURED" : "FAIL", "Review paired matrices/screenshots before certifying equivalence");
                else prepare();
            }
        }
        void finish(String status, String detail) {
            if (finished) return; finished = true;
            try {
                OverlapCombatProbe.active = OverlapCombatProbe.legacyY = OverlapCombatProbe.tiltOff = false;
                CombatRenderCounters.active = false; ((PlayerAttackAnimatable) player).stopAttackAnimation(0);
                CompatConfig.COMBAT_BODY_Y_OFFSET.set(bodyOffset); EMFCompatConfig.get().booleans.clear(); EMFCompatConfig.get().booleans.putAll(coreBooleans);
                EMFCompatCore.setCompatEnabled(coreEnabled); BetterCombatClientMod.config.firstPersonAnimations = bcMode;
                ((PlayerAttackProperties) player).setComboCount(combo);
                player.setItemInHand(InteractionHand.MAIN_HAND, main); player.setItemInHand(InteractionHand.OFF_HAND, off);
                if (serverMain != null) mc.getSingleplayerServer().execute(() -> {
                    var sp = mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID());
                    if (sp != null) { sp.setItemInHand(InteractionHand.MAIN_HAND, serverMain.copy()); sp.setItemInHand(InteractionHand.OFF_HAND, serverOff.copy()); sp.inventoryMenu.broadcastChanges(); }
                });
                mc.options.setCameraType(camera); mc.options.hideGui = gui; mc.options.keyShift.setDown(shiftKey); FirstPersonAPI.setEnabled(fpmEnabled);
                player.setShiftKeyDown(shift); player.setSprinting(sprint); player.setPose(pose); player.setPos(position); player.setDeltaMovement(motion);
                player.setYRot(yaw); player.yRotO = yaw; player.setXRot(pitch); player.xRotO = pitch;
                player.yBodyRot = player.yBodyRotO = bodyYaw; player.yHeadRot = player.yHeadRotO = headYaw;
                report.put("settingsRestored", CompatConfig.combatBodyYOffset() == bodyOffset && EMFCompatCore.isCompatEnabled() == coreEnabled && EMFCompatConfig.get().booleans.equals(coreBooleans));
                report.put("ownedFrames", observedFrames); report.put("completeHookFrames", hookFrames);
                write(status, detail);
            } catch (Throwable error) { com.mojang.logging.LogUtils.getLogger().error("Overlap restoration failed", error); }
            finally { active = null; }
        }
        void write(String status, String detail) throws Exception {
            report.put("status", status); report.put("detail", detail); report.put("completedCases", index);
            Files.writeString(output.resolve("overlap-combat.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }
}
