package dev.caleb.emfattachments.test;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import dev.caleb.emfattachments.client.FirstPersonCombat;
import dev.caleb.emfattachments.compat.BetterCombatAttackAccess;
import dev.caleb.emfattachments.config.CompatConfig;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import dev.tr7zw.firstperson.api.ActivationHandler;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import dev.tr7zw.firstperson.api.PlayerOffsetHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.bettercombat.client.BetterCombatClientMod;
import net.bettercombat.client.animation.PlayerAttackAnimatable;
import net.bettercombat.config.TriStateAuto;
import net.bettercombat.logic.AnimatedHand;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Exercises the installed Better Combat implementation on a disposable local player. */
public final class CombatProbe {
    public static final String[] ANIMATIONS = {"bettercombat:one_handed_slam", "bettercombat:two_handed_slam",
            "bettercombat:two_handed_stab_right", "bettercombat:two_handed_stab_left"};
    private CombatProbe() {}

    public static Map<String, Object> run() {
        try { return checks(); }
        catch (Throwable failure) {
            return Map.of("status", "FAIL", "error", failure.getClass().getName(), "message", String.valueOf(failure.getMessage()));
        }
    }

    private static Map<String, Object> checks() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer originalPlayer = mc.player;
        var originalEntity = mc.getCameraEntity();
        var cameraType = mc.options.getCameraType();
        boolean enabled = FirstPersonAPI.isEnabled();
        boolean body = FirstPersonModelCore.instance.isRenderingPlayerRaw();
        boolean extraction = FirstPersonModelCore.cameraEntityExtract;
        boolean cameraDetached = mc.gameRenderer.getMainCamera().isDetached();
        var firstPersonConfig = FirstPersonModelCore.instance.getConfig();
        String fpmSettings = new Gson().toJson(firstPersonConfig);
        String bcSettings = new Gson().toJson(BetterCombatClientMod.config);
        String neaSettings = new Gson().toJson(dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config);
        TriStateAuto oldMode = BetterCombatClientMod.config.firstPersonAnimations;
        LocalPlayer player = new LocalPlayer(mc, mc.level, originalPlayer.connection,
                originalPlayer.getStats(), originalPlayer.getRecipeBook(), false, false);
        player.copyPosition(originalPlayer);
        player.setYRot(0); player.yBodyRot = player.yBodyRotO = 0;
        player.setXRot(45); player.xRotO = 45; player.tickCount = 120;
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
        List<Map<String, Object>> cases = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cases", cases);
        try {
            mc.player = player; mc.setCameraEntity(player);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.gameRenderer.getMainCamera().setup(mc.level, player, false, false, 0.5f);
            FirstPersonAPI.setEnabled(true);
            FirstPersonModelCore.instance.setRenderingPlayer(true);
            FirstPersonModelCore.cameraEntityExtract = false;
            var processor = ((IAnimatedPlayer) player).playerAnimator_getAnimation();
            var animatable = (PlayerAttackAnimatable) player;
            var layer = (ModifierLayer<?>) ((BetterCombatAttackAccess) player).emfattachments$attackLayer();
            var remote = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), "CombatScopeProbe"));
            var remoteProcessor = ((IAnimatedPlayer) remote).playerAnimator_getAnimation();
            // YES deliberately requests the conflicting native animated-model pass.
            // This probe never saves the setting and restores it in finally.
            BetterCombatClientMod.config.firstPersonAnimations = TriStateAuto.YES;
            for (String animation : ANIMATIONS) {
                AnimatedHand hand = animation.contains("one_handed") ? AnimatedHand.MAIN_HAND : AnimatedHand.TWO_HANDED;
                animatable.playAttackAnimation(animation, hand, 24, 0.5f);
                require(layer.getAnimation() != null, "Missing installed animation: " + animation);
                processor.setTickDelta(0.5f);
                require(FirstPersonCombat.ownsCombatRendering(), "Local attack was not owned: " + animation);
                require(processor.getFirstPersonMode() == FirstPersonMode.NONE, "Competing native body pass remained enabled");
                require(!FirstPersonModelCore.instance.getLogicHandler().showVanillaHands(), "Duplicate vanilla weapon path remained enabled");
                require(!FirstPersonModelCore.instance.getLogicHandler().hideArmsAndItems(player), "Animated body weapon was hidden");
                require(!FirstPersonCombat.ownsProcessor(remoteProcessor), "Changed another player's processor");
                ((PlayerAttackAnimatable) remote).playAttackAnimation(animation, hand, 24, 0.5f);
                require(remoteProcessor.getFirstPersonMode() == FirstPersonMode.THIRD_PERSON_MODEL, "Changed remote animation mode");
                Vec3f initial = new Vec3f(-5, 2, 0);
                Vec3f adjusted = processor.get3DTransform("rightArm", TransformType.POSITION, initial);
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                Vec3f normal = processor.get3DTransform("rightArm", TransformType.POSITION, initial);
                require(!FirstPersonCombat.ownsCombatRendering(), "Owned third-person rendering");
                require(processor.getFirstPersonMode() == FirstPersonMode.THIRD_PERSON_MODEL, "Changed third-person animation mode");
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                FirstPersonAPI.setEnabled(false);
                require(!FirstPersonCombat.ownsCombatRendering(), "Owned disabled FPM rendering");
                same(normal, processor.get3DTransform("rightArm", TransformType.POSITION, initial), "Changed arms with FPM disabled");
                FirstPersonAPI.setEnabled(true);
                require(Math.abs(adjusted.getZ() - normal.getZ()) > 0.001, "Arm correction did not run");
                boolean originalToggle = CompatConfig.combatCompatibility();
                try {
                    CompatConfig.COMBAT_COMPATIBILITY.set(false);
                    require(!FirstPersonCombat.ownsCombatRendering(), "Ignored disabled combat compatibility setting");
                    same(normal, processor.get3DTransform("rightArm", TransformType.POSITION, initial), "Config off still altered arms");
                } finally { CompatConfig.COMBAT_COMPATIBILITY.set(originalToggle); }
                var roll = ((net.combat_roll.internals.RollingEntity) player).getRollManager();
                roll.onRoll(player);
                require(roll.isRolling() && !FirstPersonCombat.ownsCombatRendering(), "Combat retained control during actual Combat Roll");
                int rollTicks = 0;
                while (roll.isRolling() && rollTicks++ < 200) roll.tick(player);
                require(!roll.isRolling() && FirstPersonCombat.ownsCombatRendering(), "Combat did not recover after roll ended");

                int[] callbacks = {0};
                ActivationHandler nested = () -> { callbacks[0]++; processor.getFirstPersonMode(); return false; };
                FirstPersonAPI.getActivationHandlers().add(nested);
                try { require(FirstPersonCombat.ownsCombatRendering(), "Reentrant camera hook disabled the outer attack"); }
                finally { FirstPersonAPI.getActivationHandlers().remove(nested); }
                require(callbacks[0] == 1, "Recursive activation evaluation: " + callbacks[0]);

                // Stop while the source remains active; null getAnimation is not
                // an expired layer because the real fade still renders it.
                for (int tick = 0; tick < 4; tick++) { layer.tick(); processor.setTickDelta(0.5f); }
                animatable.stopAttackAnimation(10);
                require(layer.getAnimation() == null && layer.isActive(), "Native fade was not exercised");
                List<Float> weights = new ArrayList<>();
                int fadeTicks = 0;
                while (layer.isActive() && fadeTicks < 80) {
                    processor.setTickDelta(0.5f);
                    require(FirstPersonCombat.ownsCombatRendering(), "Ownership stopped before fade ended");
                    float weight = FirstPersonCombat.correctionWeight("rightArm", TransformType.POSITION, 0.5f);
                    weights.add(weight);
                    require(Float.isFinite(weight) && weight >= 0 && weight <= 1, "Invalid native fade weight");
                    layer.tick(); fadeTicks++;
                }
                require(!layer.isActive() && !FirstPersonCombat.ownsCombatRendering(), "Attack did not expire");
                require(weights.size() > 1 && weights.getLast() < 0.03f, "Arm correction did not fade to zero: " + weights);
                processor.setTickDelta(0.5f);
                same(initial, processor.get3DTransform("rightArm", TransformType.POSITION, initial), "Arm offset leaked after expiry");

                // Also let the installed animation finish naturally, including its
                // native closing frames instead of the short native first-person mode.
                animatable.playAttackAnimation(animation, hand, 24, 0.5f);
                int naturalTicks = 0;
                float tailWeight = 1;
                while (layer.isActive() && naturalTicks < 240) {
                    processor.setTickDelta(0.5f);
                    require(FirstPersonCombat.ownsCombatRendering(), "Ownership ended during natural animation");
                    tailWeight = FirstPersonCombat.correctionWeight("rightArm", TransformType.POSITION, 0.5f);
                    layer.tick(); naturalTicks++;
                }
                require(!layer.isActive(), "Native animation never ended");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("animation", animation); row.put("fadeTicks", fadeTicks); row.put("fadeWeights", weights);
                row.put("naturalTicks", naturalTicks); row.put("lastNaturalWeight", tailWeight);
                row.put("scopeAndSingleRendererDecisions", "PASS"); cases.add(row);
                row.put("combatConfigOffAndRollRelinquish", "PASS");
            }

            // Attribute scale is tested on the actual transformed FPM method.
            player.setXRot(0); player.xRotO = 0;
            var scale = player.getAttribute(Attributes.SCALE);
            var logic = FirstPersonModelCore.instance.getLogicHandler();
            double nativeZ = -(0.25f + firstPersonConfig.xOffset / 100f);
            PlayerOffsetHandler external = (entity, delta, original, current) -> current.add(1, 2, 3);
            List<Map<String, Object>> scaled = new ArrayList<>();
            FirstPersonAPI.getPlayerOffsetHandlers().add(external);
            try {
                for (double value : new double[]{0.5, 1, 2}) {
                    scale.setBaseValue(value); logic.updatePositionOffset(player, 0.5f);
                    Vec3 offset = logic.getOffset();
                    require(Math.abs(offset.x - 1) < 1e-5 && Math.abs(offset.y - 2) < 1e-5
                            && Math.abs(offset.z - (nativeZ * value + 3)) < 1e-5, "Scale or external handler contribution changed: " + offset);
                    scaled.add(Map.of("scale", value, "x", offset.x, "y", offset.y, "z", offset.z));
                }
            } finally { FirstPersonAPI.getPlayerOffsetHandlers().remove(external); }
            result.put("nativeScaleOffsetsWithExternalHandler", scaled);
            result.put("status", "PASS");
        } finally {
            BetterCombatClientMod.config.firstPersonAnimations = oldMode;
            mc.player = originalPlayer; mc.setCameraEntity(originalEntity); mc.options.setCameraType(cameraType);
            FirstPersonAPI.setEnabled(enabled); FirstPersonModelCore.instance.setRenderingPlayer(body);
            FirstPersonModelCore.cameraEntityExtract = extraction;
            mc.gameRenderer.getMainCamera().setup(mc.level, originalEntity, cameraDetached,
                    cameraType == CameraType.THIRD_PERSON_FRONT, 0.5f);
            FirstPersonModelCore.instance.getLogicHandler().updatePositionOffset(originalEntity, 0.5f);
        }
        require(fpmSettings.equals(new Gson().toJson(firstPersonConfig)), "FPM user settings changed");
        require(bcSettings.equals(new Gson().toJson(BetterCombatClientMod.config)), "Better Combat user settings changed");
        require(neaSettings.equals(new Gson().toJson(dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config)), "NEA user settings changed");
        result.put("externalSettingsPreserved", true);
        return result;
    }

    private static void same(Vec3f expected, Vec3f actual, String message) {
        require(Math.abs(expected.getX() - actual.getX()) < 1e-5
                && Math.abs(expected.getY() - actual.getY()) < 1e-5
                && Math.abs(expected.getZ() - actual.getZ()) < 1e-5, message + ": " + actual);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
