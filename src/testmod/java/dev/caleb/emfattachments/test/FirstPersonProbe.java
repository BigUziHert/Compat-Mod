package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.authlib.GameProfile;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import dev.tr7zw.firstperson.versionless.mixinbase.ModelPartBase;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.menu.ArmorSlotTypes;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import traben.entity_model_features.models.parts.EMFModelPart;
import traben.entity_texture_features.features.state.ETFState;

/** Integration probe: run on the client render thread after FirstPerson initializes. */
public final class FirstPersonProbe {
    private FirstPersonProbe() {}

    public static Map<String, Object> run() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.putAll(runChecks());
        } catch (Throwable failure) {
            result.putAll(failure(failure));
        }
        result.put("emfProbeStatus", result.get("status"));
        Map<String, Object> attachments;
        try { attachments = FirstPersonAttachmentProbe.run(); }
        catch (Throwable failure) { attachments = failure(failure); }
        result.put("accessoryScope", attachments);
        Map<String, Object> combat = CombatProbe.run();
        result.put("combat", combat);
        if (!"PASS".equals(attachments.get("status")) || !"PASS".equals(combat.get("status"))) result.put("status", "FAIL");
        return result;
    }

    private static Map<String, Object> failure(Throwable failure) {
        return Map.of("status", "FAIL", "error", failure.getClass().getName(),
                "message", String.valueOf(failure.getMessage()));
    }

    private static Map<String, Object> runChecks() {
        FirstPersonModelCore firstPerson = FirstPersonModelCore.instance;
        if (firstPerson == null) {
            throw new IllegalStateException("FirstPerson has not initialized");
        }
        boolean originalBodyPass = firstPerson.isRenderingPlayerRaw();
        boolean originalCameraExtract = FirstPersonModelCore.cameraEntityExtract;
        int originalDepth = ETFState.currentModelPartDepth;
        var minecraft = Minecraft.getInstance();
        CameraType originalCamera = minecraft.options.getCameraType();
        Entity originalCameraEntity = minecraft.getCameraEntity();
        boolean originalEnabled = FirstPersonAPI.isEnabled();
        ProbePart child = new ProbePart(Map.of());
        ProbePart head = new ProbePart(Map.of("custom_face", child));
        ModelPartBase hiddenHead = (ModelPartBase) (Object) head;
        ModelPartBase hiddenChild = (ModelPartBase) (Object) child;
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Actual entity render states are mounted by draw() and fully restored.
            // Geometry is deliberately small so hiding and restoration have exact counts.
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            minecraft.setCameraEntity(minecraft.player);
            FirstPersonAPI.setEnabled(true);
            ETFState.currentModelPartDepth = 1;
            FirstPersonModelCore.cameraEntityExtract = false;
            firstPerson.setRenderingPlayer(true);
            head.z = 2.0f;

            int normal = draw(head);
            require(normal == 48, "Two normal cubes must produce 48 vertices; got " + normal);
            result.put("normalBodyVertices", normal);

            hiddenHead.setHidden();
            require(hiddenHead.isHidden(), "FirstPerson's model-part mixin is missing");
            // Reproduce the important conflict: EMF updates ordinary fields after
            // FPM sets its independent hidden marker, before EMF draws the part.
            head.visible = true;
            head.z = 0.0f;
            int hidden = draw(head);
            require(hidden == 0, "Hidden EMF head/face emitted " + hidden + " vertices");
            result.put("hiddenHeadAndFaceVertices", hidden);

            var otherPlayer = new RemotePlayer(minecraft.level, new GameProfile(UUID.randomUUID(), "MarkerScopeProbe"));
            int otherPlayerVertices = draw(head, otherPlayer);
            require(otherPlayerVertices == normal, "Hidden marker suppressed a different player's EMF geometry");
            result.put("otherPlayerBodyFlagVertices", otherPlayerVertices);
            int absentEntityVertices = draw(head, null);
            require(absentEntityVertices == normal, "Hidden marker suppressed geometry without an EMF entity context");
            result.put("absentEntityContextVertices", absentEntityVertices);
            FirstPersonModelCore.cameraEntityExtract = true;
            int extractionVertices = draw(head);
            require(extractionVertices == normal, "Hidden marker affected camera extraction");
            result.put("cameraExtractionVertices", extractionVertices);
            FirstPersonModelCore.cameraEntityExtract = false;
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            int thirdPersonBodyFlagVertices = draw(head);
            require(thirdPersonBodyFlagVertices == normal, "Hidden marker affected third-person camera");
            result.put("thirdPersonBodyFlagVertices", thirdPersonBodyFlagVertices);
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            FirstPersonAPI.setEnabled(false);
            int disabledVertices = draw(head);
            require(disabledVertices == normal, "Hidden marker affected disabled FirstPerson");
            result.put("firstPersonDisabledVertices", disabledVertices);
            FirstPersonAPI.setEnabled(true);
            if (net.neoforged.fml.ModList.get().isLoaded("iris")) {
                boolean shadow = net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE;
                try {
                    net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE = true;
                    int shadowVertices = draw(head);
                    require(shadowVertices == normal, "Hidden marker affected an Iris shadow pass");
                    result.put("shadowPassVertices", shadowVertices);
                } finally { net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE = shadow; }
            }

            firstPerson.setRenderingPlayer(false);
            int outsideBodyPass = draw(head);
            require(outsideBodyPass == normal,
                    "The fix changed a render outside FirstPerson's body pass");
            result.put("outsideBodyPassVertices", outsideBodyPass);

            hiddenHead.showAgain();
            require(!hiddenHead.isHidden() && head.visible && head.z == 2.0f,
                    "FirstPerson's showAgain did not restore the head state");
            int restored = draw(head);
            require(restored == normal, "Third-person rendering did not recover");
            result.put("restoredThirdPersonVertices", restored);

            firstPerson.setRenderingPlayer(true);
            hiddenChild.setHidden();
            child.visible = true;
            child.z = 0.0f;
            int hiddenChildVertices = draw(head);
            require(hiddenChildVertices == 24,
                    "Hiding a child should retain the visible parent cube");
            result.put("hiddenChildVisibleParentVertices", hiddenChildVertices);
            hiddenChild.showAgain();
            require(!hiddenChild.isHidden() && draw(head) == normal,
                    "Child visibility leaked after restoration");
            checkCosmeticArmorLookup(firstPerson, result);
            result.put("status", "PASS");
            return result;
        } finally {
            hiddenHead.showAgain();
            hiddenChild.showAgain();
            ETFState.currentModelPartDepth = originalDepth;
            FirstPersonModelCore.cameraEntityExtract = originalCameraExtract;
            firstPerson.setRenderingPlayer(originalBodyPass);
            minecraft.options.setCameraType(originalCamera);
            minecraft.setCameraEntity(originalCameraEntity);
            FirstPersonAPI.setEnabled(originalEnabled);
        }
    }

    private static void checkCosmeticArmorLookup(FirstPersonModelCore firstPerson, Map<String, Object> result) {
        var player = Minecraft.getInstance().player;
        var capability = AccessoriesCapability.get(player);
        require(capability != null, "No Accessories capability for cosmetic helmet probe");
        var container = capability.getContainer(ArmorSlotTypes.headSlot());
        require(container != null && container.getSize() > 0, "No virtual armor head container");
        ItemStack original = container.getCosmeticAccessories().getItem(0).copy();
        boolean originalRender = container.shouldRender(0);
        try {
            container.getCosmeticAccessories().setItem(0, Items.LEATHER_HELMET.getDefaultInstance());
            if (!container.renderOptions().isEmpty()) container.renderOptions().set(0, true);
            firstPerson.setRenderingPlayer(false);
            ItemStack thirdPerson = ArmorSlotTypes.getAlternativeStack(player, EquipmentSlot.HEAD);
            require(thirdPerson != null && thirdPerson.is(Items.LEATHER_HELMET),
                    "Cosmetic helmet probe setup did not yield visible headwear");
            firstPerson.setRenderingPlayer(true);
            ItemStack firstPersonStack = ArmorSlotTypes.getAlternativeStack(player, EquipmentSlot.HEAD);
            require(firstPersonStack != null && firstPersonStack.isEmpty(),
                    "Cosmetic armor lookup bypassed FirstPerson's empty head slot");
            require(container.getCosmeticAccessories().getItem(0).is(Items.LEATHER_HELMET),
                    "Cosmetic renderer changed actual inventory contents");
            var remote = new RemotePlayer(player.clientLevel, new GameProfile(UUID.randomUUID(), "CosmeticScopeProbe"));
            var remoteContainer = AccessoriesCapability.get(remote).getContainer(ArmorSlotTypes.headSlot());
            require(remoteContainer != null, "Remote cosmetic container is absent");
            remoteContainer.getCosmeticAccessories().setItem(0, Items.LEATHER_HELMET.getDefaultInstance());
            if (!remoteContainer.renderOptions().isEmpty()) remoteContainer.renderOptions().set(0, true);
            ItemStack remoteHelmet = ArmorSlotTypes.getAlternativeStack(remote, EquipmentSlot.HEAD);
            require(remoteHelmet != null && remoteHelmet.is(Items.LEATHER_HELMET), "Cosmetic lookup hid another player's helmet");
            result.put("otherPlayerCosmeticArmorPreserved", true);
            FirstPersonModelCore.cameraEntityExtract = true;
            try {
                ItemStack extractionHelmet = ArmorSlotTypes.getAlternativeStack(player, EquipmentSlot.HEAD);
                require(extractionHelmet != null && extractionHelmet.is(Items.LEATHER_HELMET), "Cosmetic lookup hid camera-extraction helmet");
                result.put("cameraExtractionCosmeticArmorPreserved", true);
            } finally { FirstPersonModelCore.cameraEntityExtract = false; }
            firstPerson.setRenderingPlayer(false);
            ItemStack restored = ArmorSlotTypes.getAlternativeStack(player, EquipmentSlot.HEAD);
            require(restored != null && restored.is(Items.LEATHER_HELMET),
                    "Cosmetic helmet failed to reappear outside FirstPerson body pass");
            result.put("cosmeticArmorHeadHiddenOnlyInBodyPass", true);
        } finally {
            container.getCosmeticAccessories().setItem(0, original);
            if (!container.renderOptions().isEmpty()) container.renderOptions().set(0, originalRender);
        }
    }

    private static int draw(ModelPart part) {
        return draw(part, Minecraft.getInstance().player);
    }

    private static int draw(ModelPart part, Entity entity) {
        CountingConsumer consumer = new CountingConsumer();
        try (var context = new EmfProbeContext(entity)) {
            part.render(new PoseStack(), consumer, 0xF000F0, 0, -1);
        }
        return consumer.vertices;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ProbePart extends EMFModelPart {
        private ProbePart(Map<String, ModelPart> children) {
            super(List.of(new ModelPart.Cube(0, 0, 0, 0, 0, 1, 1, 1,
                    0, 0, 0, false, 16, 16, EnumSet.allOf(Direction.class))), children, null);
        }

        @Override protected float[] debugBoxColor() { return new float[]{1, 1, 1}; }
    }

    private static final class CountingConsumer implements VertexConsumer {
        private int vertices;

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            require(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z),
                    "Renderer emitted a non-finite vertex");
            vertices++;
            return this;
        }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
