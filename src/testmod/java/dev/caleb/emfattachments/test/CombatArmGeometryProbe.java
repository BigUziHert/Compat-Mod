package dev.caleb.emfattachments.test;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.caleb.emfattachments.client.FirstPersonArmGeometry;
import dev.caleb.emfattachments.client.FirstPersonCombat;
import dev.caleb.emfattachments.compat.BetterCombatAttackAccess;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import io.wispforest.accessories.api.client.DefaultAccessoryRenderer;
import io.wispforest.accessories.api.slot.SlotReference;
import java.util.ArrayList;
import java.util.EnumSet;
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
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import traben.entity_model_features.models.parts.EMFModelPart;
import traben.entity_texture_features.features.state.ETFState;

/** Hook-level native animation/model test. This is not a gameplay or camera-sequence test. */
public final class CombatArmGeometryProbe {
    private CombatArmGeometryProbe() {}
    public static Map<String,Object> run() {
        var result = new LinkedHashMap<String,Object>();
        try { checks(result); result.put("status", "PASS"); }
        catch (Throwable failure) { result.put("status", "FAIL"); result.put("error", failure.toString()); }
        return result;
    }

    private static void checks(Map<String,Object> report) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer previous = mc.player; var cameraEntity = mc.getCameraEntity();
        var cameraType = mc.options.getCameraType(); boolean detached = mc.gameRenderer.getMainCamera().isDetached();
        boolean enabled = FirstPersonAPI.isEnabled(), raw = FirstPersonModelCore.instance.isRenderingPlayerRaw();
        boolean extract = FirstPersonModelCore.cameraEntityExtract;
        int depth = ETFState.currentModelPartDepth;
        var config = BetterCombatClientMod.config;
        String configBefore = new Gson().toJson(config);
        boolean arms = config.isShowingArmsInFirstPerson, other = config.isShowingOtherHandFirstPerson;
        TriStateAuto mode = config.firstPersonAnimations;
        LocalPlayer player = new LocalPlayer(mc, mc.level, previous.connection,
                previous.getStats(), previous.getRecipeBook(), false, false);
        player.copyPosition(previous); player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
        var model = new PlayerModel<LivingEntity>(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot(), false);
        var armor = new HumanoidModel<LivingEntity>(LayerDefinition.create(HumanoidModel.createMesh(new CubeDeformation(1), 0), 64, 32).bakeRoot());
        var remote = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), "ArmVisibilityRemote"));
        var glove = cube(Map.of("cuff", cube(Map.of())));
        var emf = new EmfPart(Map.of("detail", cube(Map.of())));
        var rows = new ArrayList<Map<String,Object>>(); report.put("cases", rows);
        try {
            mc.player = player; mc.setCameraEntity(player); mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.gameRenderer.getMainCamera().setup(mc.level, player, false, false, .5f);
            FirstPersonAPI.setEnabled(true); FirstPersonModelCore.instance.setRenderingPlayer(true);
            FirstPersonModelCore.cameraEntityExtract = false; ETFState.currentModelPartDepth = 1;
            // AUTO deliberately reproduces BC's NONE-mode stack filter while FPM is installed.
            config.firstPersonAnimations = TriStateAuto.AUTO; config.isShowingOtherHandFirstPerson = true;
            for (boolean show : new boolean[]{false, true}) {
                config.isShowingArmsInFirstPerson = show;
                ((PlayerAttackAnimatable) player).playAttackAnimation("bettercombat:two_handed_slam", AnimatedHand.TWO_HANDED, 24, .5f);
                require(FirstPersonCombat.ownsCombatRendering(), "No owned native attack");
                model.rightArm.xRot = -.8f; model.rightArm.y = 3.5f;
                var fields = ModelState.state(model.rightArm);
                float[] attachmentBefore = matrix(model);
                try (var ignored = FirstPersonArmGeometry.open(player, model, .5f)) {
                    int skin = draw(model.rightArm, player), sleeve = draw(model.rightSleeve, player);
                    model.copyPropertiesTo(armor);
                    int armorVertices = draw(armor.rightArm, player);
                    glove.copyFrom(armor.rightArm); glove.visible = true; // Native Curios gloves deliberately reset this.
                    int gloveVertices = draw(glove, player);
                    emf.copyFrom(model.rightArm); emf.visible = true;
                    int emfVertices = draw(emf, player);
                    require(show ? skin > 0 && sleeve > 0 && armorVertices > 0 && gloveVertices > 0 && emfVertices > 0
                                 : skin == 0 && sleeve == 0 && armorVertices == 0 && gloveVertices == 0 && emfVertices == 0,
                            "Arm geometry differs from cached native show=" + show);
                    require(draw(model.body, player) > 0, "Body geometry was hidden");
                    require(ModelState.same(fields, ModelState.state(model.rightArm)), "Arm fields changed");
                    require(java.util.Arrays.equals(attachmentBefore, matrix(model)), "Held-item attachment matrix changed");
                    require(FirstPersonArmGeometry.hidesDefaultAnchor("wrist", 0, model) == !show, "Default wrist anchor policy");
                    int defaultItem = drawDefault(player, model);
                    require(show ? defaultItem > 0 : defaultItem == 0, "Actual default wrist renderer violated arm flag");
                    try (var itemScope = FirstPersonArmGeometry.suspendForHeldItems()) {
                        ModelPart weapon = cube(Map.of()); weapon.copyFrom(model.rightArm);
                        require(draw(weapon, player) > 0, "A custom held model copied from the arm was hidden");
                        require(java.util.Arrays.equals(attachmentBefore, matrix(model)), "Held scope changed attachment matrix");
                    }
                    try (var otherEntity = FirstPersonArmGeometry.open(remote, model, .5f)) {
                        require(draw(model.rightArm, remote) > 0, "Nested remote/shared model inherited mask");
                    }
                    require(FirstPersonArmGeometry.hides(model.rightArm) == !show, "Nested scope failed to restore outer mask");
                    rows.add(Map.of("nativeShowArms", show, "skin", skin, "sleeve", sleeve, "armor", armorVertices,
                            "gloveWithCuff", gloveVertices, "emfArmWithDetail", emfVertices, "defaultWristItem", defaultItem));
                }
                require(draw(model.rightArm, player) > 0 && draw(glove, player) > 0, "Geometry mask persisted after render");
                ((PlayerAttackAnimatable) player).stopAttackAnimation(8);
                try (var fade = FirstPersonArmGeometry.open(player, model, .5f)) {
                    require(FirstPersonArmGeometry.hides(model.rightArm) == !show, "Null-current native fade lost arm visibility");
                }
            }

            // Native single-hand configuration is cached by the real attack call,
            // then mirrored by the real modifier chain. Do not synthesize arm flags.
            config.isShowingArmsInFirstPerson = true; config.isShowingOtherHandFirstPerson = false;
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(Items.DIAMOND_SWORD));
            var asymmetric = new ArrayList<Map<String,Object>>(); report.put("nativeAsymmetricHands", asymmetric);
            Boolean previousMirror = null;
            for (AnimatedHand hand : List.of(AnimatedHand.MAIN_HAND, AnimatedHand.OFF_HAND)) {
                ((PlayerAttackAnimatable) player).playAttackAnimation("bettercombat:one_handed_slam", hand, 24, .5f);
                var attack = ((BetterCombatAttackAccess) player).emfattachments$attackStack();
                require(attack.base.getAnimation() != null && FirstPersonCombat.ownsCombatRendering(), "Native asymmetric attack did not activate");
                var nativeFlags = attack.base.getFirstPersonConfiguration(.5f); // Direct native layer, not mode-filtered AnimationStack.
                require(nativeFlags.isShowRightArm() != nativeFlags.isShowLeftArm(), "Native single-hand arm configuration is not asymmetric");
                if (previousMirror != null) require(previousMirror != attack.mirror.isEnabled(), "Native OFF_HAND did not reverse its actual MirrorModifier");
                previousMirror = attack.mirror.isEnabled();
                var resolved = dev.caleb.emfattachments.client.NativeAttackArmVisibility.from(attack, .5f);
                require(resolved.rightArm() == nativeFlags.isShowRightArm() && resolved.leftArm() == nativeFlags.isShowLeftArm()
                        && resolved.rightItem() == nativeFlags.isShowRightItem() && resolved.leftItem() == nativeFlags.isShowLeftItem(),
                        "Production configuration resolver disagrees with the actual native modifier chain");
                float[] rightBefore = matrix(model, HumanoidArm.RIGHT), leftBefore = matrix(model, HumanoidArm.LEFT);
                var geometry = new LinkedHashMap<String,Object>();
                try (var ignored = FirstPersonArmGeometry.open(player, model, .5f)) {
                    model.copyPropertiesTo(armor);
                    for (HumanoidArm side : HumanoidArm.values()) {
                        boolean show = side == HumanoidArm.RIGHT ? nativeFlags.isShowRightArm() : nativeFlags.isShowLeftArm();
                        ModelPart skinPart = side == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
                        ModelPart sleevePart = side == HumanoidArm.RIGHT ? model.rightSleeve : model.leftSleeve;
                        ModelPart armorPart = side == HumanoidArm.RIGHT ? armor.rightArm : armor.leftArm;
                        int skinCount = draw(skinPart, player), sleeveCount = draw(sleevePart, player), armorCount = draw(armorPart, player);
                        glove.copyFrom(armorPart); glove.visible = true; int gloveCount = draw(glove, player);
                        emf.copyFrom(skinPart); emf.visible = true; int emfCount = draw(emf, player);
                        int wristCount = drawDefault(player, model, side == HumanoidArm.RIGHT ? 0 : 1);
                        require(show ? skinCount > 0 && sleeveCount > 0 && armorCount > 0 && gloveCount > 0 && emfCount > 0 && wristCount > 0
                                     : skinCount == 0 && sleeveCount == 0 && armorCount == 0 && gloveCount == 0 && emfCount == 0 && wristCount == 0,
                                "Native asymmetric physical arm violated: " + hand + "/" + side);
                        geometry.put(side.name(), Map.of("nativeShowArm",show,"skin",skinCount,"sleeve",sleeveCount,
                                "armor",armorCount,"glove",gloveCount,"emf",emfCount,"defaultWrist",wristCount));
                        try (var held = FirstPersonArmGeometry.suspendForHeldItems()) {
                            ModelPart weapon = cube(Map.of()); weapon.copyFrom(skinPart);
                            require(draw(weapon, player) > 0, "Arm mask suppressed a custom held model: " + hand + "/" + side);
                        }
                    }
                    require(java.util.Arrays.equals(rightBefore, matrix(model, HumanoidArm.RIGHT))
                            && java.util.Arrays.equals(leftBefore, matrix(model, HumanoidArm.LEFT)), "Asymmetric mask changed hand attachment matrices");
                }
                ((PlayerAttackAnimatable) player).stopAttackAnimation(8);
                try (var fade = FirstPersonArmGeometry.open(player, model, .5f)) {
                    require(FirstPersonArmGeometry.hides(model.rightArm) == !nativeFlags.isShowRightArm()
                            && FirstPersonArmGeometry.hides(model.leftArm) == !nativeFlags.isShowLeftArm(), "Native asymmetric fade lost cached physical arm flags");
                }
                require(draw(model.rightArm, player) > 0 && draw(model.leftArm, player) > 0, "Asymmetric mask persisted after scope");
                asymmetric.add(Map.of("animatedHand",hand.name(),"actualMirrorEnabled",attack.mirror.isEnabled(),
                        "nativeModifierFlags",Map.of("rightArm",nativeFlags.isShowRightArm(),"leftArm",nativeFlags.isShowLeftArm(),
                                "rightItem",nativeFlags.isShowRightItem(),"leftItem",nativeFlags.isShowLeftItem()),
                        "geometry",geometry,"bothAttachmentMatricesUnchanged",true,"nativeFadePreserved",true));
            }

            config.isShowingArmsInFirstPerson = false;
            ((PlayerAttackAnimatable) player).playAttackAnimation("bettercombat:two_handed_slam", AnimatedHand.TWO_HANDED, 24, .5f);
            // Each control opens a real scope, including the camera/body predicate.
            for (String control : List.of("third_person", "fpm_disabled", "camera_extract", "outside_body", "shadow")) {
                boolean shadowBefore = net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE;
                try {
                    if (control.equals("third_person")) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    if (control.equals("fpm_disabled")) FirstPersonAPI.setEnabled(false);
                    if (control.equals("camera_extract")) FirstPersonModelCore.cameraEntityExtract = true;
                    if (control.equals("outside_body")) FirstPersonModelCore.instance.setRenderingPlayer(false);
                    if (control.equals("shadow")) net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE = true;
                    try (var ignored = FirstPersonArmGeometry.open(player, model, .5f)) {
                        require(draw(model.rightArm, player) > 0, "Suppressed negative control: " + control);
                    }
                } finally {
                    mc.options.setCameraType(CameraType.FIRST_PERSON); FirstPersonAPI.setEnabled(true);
                    FirstPersonModelCore.cameraEntityExtract = false; FirstPersonModelCore.instance.setRenderingPlayer(true);
                    net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE = shadowBefore;
                }
            }
            try {
                try (var ignored = FirstPersonArmGeometry.open(player, model, .5f)) { throw new IllegalStateException("expected render failure"); }
            } catch (IllegalStateException expected) {
                require("expected render failure".equals(expected.getMessage()), "Unexpected scope failure");
            }
            require(draw(model.rightArm, player) > 0, "Exception leaked mask");
            report.put("negativeControls", List.of("remote_shared_model", "third_person", "fpm_disabled", "camera_extract", "outside_body", "shadow", "exception_cleanup", "held_item_model"));
            report.put("attachmentMatrixAndModelFieldsUnchanged", true);
        } finally {
            config.isShowingArmsInFirstPerson = arms; config.isShowingOtherHandFirstPerson = other; config.firstPersonAnimations = mode;
            mc.player = previous; mc.setCameraEntity(cameraEntity); mc.options.setCameraType(cameraType);
            FirstPersonAPI.setEnabled(enabled); FirstPersonModelCore.instance.setRenderingPlayer(raw);
            FirstPersonModelCore.cameraEntityExtract = extract; ETFState.currentModelPartDepth = depth;
            mc.gameRenderer.getMainCamera().setup(mc.level, cameraEntity, detached, cameraType == CameraType.THIRD_PERSON_FRONT, .5f);
            FirstPersonModelCore.instance.getLogicHandler().updatePositionOffset(cameraEntity, .5f);
            report.put("configurationRestored", configBefore.equals(new Gson().toJson(config)));
        }
        require(Boolean.TRUE.equals(report.get("configurationRestored")), "Native settings not restored");
    }

    private static int drawDefault(LocalPlayer player, PlayerModel<LivingEntity> model) { return drawDefault(player, model, 0); }
    private static int drawDefault(LocalPlayer player, PlayerModel<LivingEntity> model, int index) {
        Counter count = new Counter();
        DefaultAccessoryRenderer.INSTANCE.render(new ItemStack(Items.DIAMOND), SlotReference.of(player, "wrist", index),
                new PoseStack(), model, type -> count, 0xF000F0, 0, 0, .5f, 0, 0, 0);
        return count.vertices;
    }
    private static float[] matrix(PlayerModel<LivingEntity> model) { return matrix(model, HumanoidArm.RIGHT); }
    private static float[] matrix(PlayerModel<LivingEntity> model, HumanoidArm side) {
        PoseStack pose = new PoseStack(); model.translateToHand(side, pose); return pose.last().pose().get(new float[16]);
    }
    private static ModelPart cube(Map<String,ModelPart> children) { return new ModelPart(List.of(cuboid()), children); }
    private static ModelPart.Cube cuboid() {
        return new ModelPart.Cube(0,0,0,0,0,1,1,1,0,0,0,false,16,16,EnumSet.allOf(Direction.class));
    }
    private static int draw(ModelPart part, LivingEntity player) {
        Counter count = new Counter();
        try (var emf = new EmfProbeContext(player)) { part.render(new PoseStack(), count, 0xF000F0, 0, -1); }
        return count.vertices;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class EmfPart extends EMFModelPart {
        EmfPart(Map<String,ModelPart> children) { super(List.of(cuboid()), children, null); }
        @Override protected float[] debugBoxColor() { return new float[]{1,1,1}; }
    }
    private static final class Counter implements VertexConsumer {
        int vertices;
        @Override public VertexConsumer addVertex(float x,float y,float z) {
            require(Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z), "Nonfinite geometry"); vertices++; return this;
        }
        @Override public VertexConsumer setColor(int r,int g,int b,int a) { return this; }
        @Override public VertexConsumer setUv(float u,float v) { return this; }
        @Override public VertexConsumer setUv1(int u,int v) { return this; }
        @Override public VertexConsumer setUv2(int u,int v) { return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) { return this; }
    }
}
