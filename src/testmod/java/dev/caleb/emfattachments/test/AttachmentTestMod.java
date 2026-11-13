package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.api.client.AccessoriesRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Deliberately packaged in a separate developer-only jar. Never installs itself. */
@Mod(value = "emfattachments_test", dist = Dist.CLIENT)
public final class AttachmentTestMod {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean autoStarted;
    private static boolean namedTestWindow;
    private static int readyTicks;
    private static String forcedSkinModel = "";
    private static boolean rearView;
    public static TestResult current;

    public AttachmentTestMod() {
        if (Boolean.getBoolean("emfattachments.artificeOnly")) {
            NativeWorldReadiness.register();
            ArtificeLifecycleSequence.register();
            NeoForge.EVENT_BUS.addListener(AttachmentTestMod::tick);
            return;
        }
        if (Boolean.getBoolean("emfattachments.sableAuditOnly")) {
            NativeWorldReadiness.register();
            if (net.neoforged.fml.ModList.get().isLoaded("sable")
                    && net.neoforged.fml.ModList.get().isLoaded("entityculling")) SableOcclusionSequence.register();
            NeoForge.EVENT_BUS.addListener(AttachmentTestMod::tick);
            return;
        }
        if (net.neoforged.fml.ModList.get().isLoaded("irons_artifice")
                && net.neoforged.fml.ModList.get().isLoaded("accessories")
                && net.neoforged.fml.ModList.get().isLoaded("combat_roll")) ArtificeLifecycleSequence.register();
        NativeWorldReadiness.register();
        GlErrorTraceProbe.register();
        if (net.neoforged.fml.ModList.get().isLoaded("sable")
                && net.neoforged.fml.ModList.get().isLoaded("artifacts")
                && net.neoforged.fml.ModList.get().isLoaded("accessories")) SableSleepSequence.register();
        CrowdFrameSequence.register();
        if (net.neoforged.fml.ModList.get().isLoaded("sable")
                && net.neoforged.fml.ModList.get().isLoaded("entity_model_features")) SableDeckSequence.register();
        if (net.neoforged.fml.ModList.get().isLoaded("sable")
                && net.neoforged.fml.ModList.get().isLoaded("entityculling")) SableOcclusionSequence.register();
        if (net.neoforged.fml.ModList.get().isLoaded("artifacts")
                && net.neoforged.fml.ModList.get().isLoaded("accessories")
                && net.neoforged.fml.ModList.get().isLoaded("entity_model_features")) NativeHandAccessorySequence.register();
        ExpandedEquipmentSequence.register();
        ExpandedMovementSequence.register();
        ExpandedArmorAbilitySequence.register();
        ExpandedLiveWearableSequence.register();
        ShieldDrawOrderProbe.register();
        ExpandedShieldDefenseSequence.register();
        ExpandedRagdollSequence.register();
        if (net.neoforged.fml.ModList.get().isLoaded("ragrevival")
                && net.neoforged.fml.ModList.get().isLoaded("sable_player_ragdoll")
                && net.neoforged.fml.ModList.get().isLoaded("accessories")
                && net.neoforged.fml.ModList.get().isLoaded("curios")) RevivalFaceSequence.register();
        if (net.neoforged.fml.ModList.get().isLoaded("cataclysm")) ExpandedTidalGrappleSequence.register();
        if (net.neoforged.fml.ModList.get().isLoaded("the_bumblezone")) ExpandedHangingSequence.register();
        if (net.neoforged.fml.ModList.get().isLoaded("aether")
                && net.neoforged.fml.ModList.get().isLoaded("cataclysm")
                && net.neoforged.fml.ModList.get().isLoaded("simplyswords")) ExpandedSequentialAbilitySequence.register();
        if (net.neoforged.fml.ModList.get().isLoaded("notenoughanimations")
                && net.neoforged.fml.ModList.get().isLoaded("emf_compat_core")
                && net.neoforged.fml.ModList.get().isLoaded("emf_compat_not_enough_animations")
                && net.neoforged.fml.ModList.get().isLoaded("bettercombat")) {
            NeaProjectileLifecycleSequence.register();
            CrossbowLifecycleSequence.register();
        }
        if (net.neoforged.fml.ModList.get().isLoaded("firstperson")
                && net.neoforged.fml.ModList.get().isLoaded("notenoughanimations")
                && net.neoforged.fml.ModList.get().isLoaded("entity_model_features")
                && net.neoforged.fml.ModList.get().isLoaded("emf_compat_core")
                && net.neoforged.fml.ModList.get().isLoaded("emf_compat_not_enough_animations")
                && net.neoforged.fml.ModList.get().isLoaded("bettercombat")) BowVisibilityDiagnosticSequence.register();
        if (net.neoforged.fml.ModList.get().isLoaded("firstperson")) {
            ExpandedLocalEquipmentProbe.register();
            WardenHelmetProbe.register();
        }
        if (net.neoforged.fml.ModList.get().isLoaded("bettercombat")
                && net.neoforged.fml.ModList.get().isLoaded("simplyswords")
                && net.neoforged.fml.ModList.get().isLoaded("combat_roll")) ExpandedCombatSequence.register();
        LiveFirstPersonSequence.register();
        if (net.neoforged.fml.ModList.get().isLoaded("firstperson")
                && net.neoforged.fml.ModList.get().isLoaded("bettercombat")) {
            LiveCombatSequence.register();
        }
        NeoForge.EVENT_BUS.addListener(AttachmentTestMod::tick);
        NeoForge.EVENT_BUS.addListener(AttachmentTestMod::frameFinished);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Path shutdown = mc.gameDirectory.toPath().resolve("emfattachments-stop.txt");
        if (mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))
                && Files.isRegularFile(shutdown)) {
            try { Files.delete(shutdown); mc.stop(); } catch (IOException ex) { LOGGER.error("Test client shutdown failed", ex); }
            return;
        }
        if (!namedTestWindow && mc.player != null && mc.hasSingleplayerServer()
                && mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client"))) {
            mc.getWindow().setTitle("EMF isolated test | FirstPerson "
                    + (net.neoforged.fml.ModList.get().isLoaded("firstperson") ? "installed" : "removed")
                    + " | " + mc.gameDirectory.toPath().toAbsolutePath().normalize());
            namedTestWindow = true;
        }
        if (Boolean.getBoolean("emfattachments.sableAuditOnly")
                && !Boolean.getBoolean("emfattachments.artificeOnly")) return;
        if (mc.level == null || mc.player == null || mc.screen instanceof SweepScreen) { readyTicks = 0; return; }
        if (++readyTicks < 40) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-test-start.txt");
        boolean auto = Boolean.getBoolean("emfattachments.test") && !autoStarted;
        if (!auto && !Files.isRegularFile(trigger)) return;
        try {
            String request = Files.isRegularFile(trigger) ? Files.readString(trigger).trim() : "automatic";
            if (Files.isRegularFile(trigger)) Files.delete(trigger);
            autoStarted = true;
            String[] parts = request.split("\\R", 4);
            String label = parts[0].replaceAll("[^a-zA-Z0-9._-]", "_");
            String filter = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : ".*";
            forcedSkinModel = parts.length > 2 ? parts[2].trim() : "";
            rearView = parts.length > 3 && parts[3].trim().equalsIgnoreCase("back");
            mc.setScreen(new SweepScreen(label.isEmpty() ? "run" : label, filter));
        } catch (Throwable ex) { LOGGER.error("Attachment test startup failed", ex); }
    }

    private static void frameFinished(RenderFrameEvent.Post event) {
        if (Minecraft.getInstance().screen instanceof SweepScreen screen) screen.finishFrame();
    }

    public static final class TestResult {
        public String item, slot, pose, renderer, itemClass, variant;
        public Integer expectedTint;
        public boolean tintObserved;
        public int slotIndex, rendererCalls, vertices, invalidVertices, mutatedParts, gogglesAnchorChecks, gogglesAnchorFailures;
        public int torsoAnchorChecks, torsoAnchorFailures;
        public int flowerAnchorChecks, flowerAnchorFailures, flowerColorChecks, flowerColorFailures, expectedFlowerColor, actualFlowerColor;
        public int quiverLayerCalls, quiverRenderCalls, quiverVertices, quiverInvalidVertices, quiverBodyAnchorCalls, quiverLegAnchorCalls;
        public String quiverMode;
        public double torsoAnchorMaxError;
        public float[] sourceHead, armorHead, renderedHead;
        public String playerModelClass, headPartClass, capeTexture;
        public boolean completed;
        public String exception = "";
        public List<String> mutations = new ArrayList<>();
        public TestResult() {}
        public TestResult(Case c, String pose) {
            item = BuiltInRegistries.ITEM.getKey(c.item).toString(); slot = c.slot; slotIndex = c.index;
            renderer = c.renderer; itemClass = c.item.getClass().getName(); this.pose = pose;
            variant = c.dyeColor < 0 ? "default" : "dyed-blue"; expectedTint = c.dyeColor < 0 ? null : c.dyeColor;
        }
    }
    private record Case(Item item, String slot, int index, EquipmentSlot armor, String renderer, int dyeColor) {
        private Case(Item item, String slot, int index, EquipmentSlot armor, String renderer) { this(item,slot,index,armor,renderer,-1); }
        ItemStack stack() {
            ItemStack stack = item.getDefaultInstance();
            if (dyeColor >= 0) stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(dyeColor, true));
            return stack;
        }
    }
    private static final String[] POSES = {"idle", "walk_head_turn", "crouch_look_down", "swim"};

    private static final class TestPlayer extends RemotePlayer {
        private final PlayerSkin skin;
        private final net.minecraft.world.scores.PlayerTeam hiddenNameTeam = new net.minecraft.world.scores.PlayerTeam(new net.minecraft.world.scores.Scoreboard(), "attachment-test-clones");
        private boolean swimming;
        TestPlayer() {
            super(Minecraft.getInstance().level, new GameProfile(UUID.randomUUID(), "AttachmentTest"));
            PlayerSkin originalSkin = Minecraft.getInstance().player.getSkin();
            skin = forcedSkinModel.isEmpty() ? originalSkin : new PlayerSkin(originalSkin.texture(), originalSkin.textureUrl(), originalSkin.capeTexture(), originalSkin.elytraTexture(), PlayerSkin.Model.byName(forcedSkinModel), originalSkin.secure());
            hiddenNameTeam.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.NEVER);
            setPos(Minecraft.getInstance().player.position());
            setOldPosAndRot(); setOnGround(true);
            // Clones are not world-ticked: initialize cape chase coordinates so
            // CapeLayer does not interpret the copied world position as a teleport.
            xCloak = xCloakO = getX();
            yCloak = yCloakO = getY();
            zCloak = zCloakO = getZ();
            try {
                var holder = (java.util.function.Supplier<?>) Class.forName("io.github.razordevs.deep_aether.networking.attachment.DAAttachments").getField("PLAYER").get(null);
                Object scarfState = getData((net.neoforged.neoforge.attachment.AttachmentType<?>) holder.get());
                scarfState.getClass().getMethod("setFloatyScarfWrappedAroundNeck", boolean.class).invoke(scarfState, true);
            } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot enable scarf geometry on test clone", ex); }
        }
        @Override public PlayerSkin getSkin() {
            // Keep modded cape/elytra replacements produced by AbstractClientPlayer's
            // real getSkin path (notably Aether accessory capes).
            PlayerSkin dynamic = super.getSkin();
            if (skin == null) return dynamic;
            return new PlayerSkin(skin.texture(), skin.textureUrl(), dynamic.capeTexture(), dynamic.elytraTexture(), skin.model(), skin.secure());
        }
        @Override public net.minecraft.world.scores.PlayerTeam getTeam() { return hiddenNameTeam; }
        @Override public boolean isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart part) { return true; }
        @Override public float getSwimAmount(float partialTick) { return swimming ? 1F : 0F; }
        void applyPose(int pose) {
            swimming = pose == 3;
            setPose(swimming ? Pose.SWIMMING : pose == 2 ? Pose.CROUCHING : Pose.STANDING);
            setShiftKeyDown(pose == 2); setSwimming(swimming); setSprinting(pose == 1);
            setOnGround(!swimming);
            setDeltaMovement(pose == 1 ? new Vec3(0.12, 0, 0.04) : swimming ? new Vec3(0.04, 0, 0.02) : Vec3.ZERO);
            walkAnimation.setSpeed(pose == 1 ? 0.8F : 0F);
            walkAnimation.update(pose == 1 ? 0.8F : 0F, 1F);
            tickCount = 120 + pose * 7;
            yBodyRot = yBodyRotO = 180F;
            yHeadRot = yHeadRotO = pose == 1 ? 225F : 180F;
            setYRot(yHeadRot); yRotO = yHeadRot;
            setXRot(pose == 2 ? 35F : 0F); xRotO = getXRot();
        }
    }

    private static final class SweepScreen extends Screen {
        private final TestPlayer player = new TestPlayer();
        private final TestPlayer[] posePlayers = {player, new TestPlayer(), new TestPlayer(), new TestPlayer()};
        private final List<Case> cases = new ArrayList<>();
        private final List<TestResult> results = new ArrayList<>();
        private final List<String> discoveryErrors = new ArrayList<>();
        private final Path output;
        private final String label;
        private int page, frames, rows = 4;
        private long pageReadyAfterNanos;
        private boolean capture, complete;
        private final AccessoriesCapability capability;
        private final Map<String, Object> report = new LinkedHashMap<>();

        SweepScreen(String label, String filter) throws IOException {
            super(Component.literal("EMF attachment renderer sweep"));
            this.label = label;
            output = Minecraft.getInstance().gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);
            Files.createDirectories(output);
            capability = AccessoriesCapability.get(player);
            if (capability == null) throw new IllegalStateException("No Accessories capability on test player");
            for (Item item : BuiltInRegistries.ITEM) {
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                if (!id.matches(filter)) continue;
                ItemStack stack = item.getDefaultInstance();
                if (stack.isEmpty()) continue;
                try {
                    EquipmentSlot equipmentSlot = player.getEquipmentSlotForItem(stack);
                    if (equipmentSlot == EquipmentSlot.HEAD || equipmentSlot == EquipmentSlot.CHEST || equipmentSlot == EquipmentSlot.LEGS || equipmentSlot == EquipmentSlot.FEET) {
                        cases.add(new Case(item, "equipment:" + equipmentSlot.getName(), 0, equipmentSlot, "vanilla/equipment-layer"));
                    }
                    for (var slot : AccessoriesAPI.getStackSlotTypes(player, stack)) {
                        // These are UI aliases of the already-covered vanilla armor slots;
                        // their regular accessory container is not the actual equipment inventory.
                        if (io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(slot.name())) continue;
                        AccessoriesContainer container = capability.getContainer(slot);
                        if (container == null) { discoveryErrors.add(id + " slot container absent: " + slot.name()); continue; }
                        String renderer = AccessoriesRendererRegistry.getRenderer(stack).getClass().getName();
                        for (int i = 0; i < container.getSize(); i++) {
                            cases.add(new Case(item, slot.name(), i, null, renderer));
                            if (id.equals("the_bumblezone:flower_headwear") && slot.name().equals("hat")) cases.add(new Case(item, slot.name(), i, null, renderer, 0x33AAFF));
                        }
                    }
                } catch (Throwable ex) { discoveryErrors.add(id + ": " + ex); }
            }
            cases.sort(Comparator.comparing((Case c) -> BuiltInRegistries.ITEM.getKey(c.item).toString()).thenComparing(Case::slot).thenComparingInt(Case::index));
            report.put("playerSkinModel", player.getSkin().model().id());
            report.put("view", rearView ? "back" : "front");
            report.put("label", label); report.put("scope", "Runtime registry wearable armor/head items and every actual Accessories-valid item/slot/index (armor UI aliases excluded as duplicates); four controlled poses on a RemotePlayer clone. Does not certify live first-person camera or every NBT/component variation.");
            report.put("caseCount", cases.size()); report.put("poses", POSES);
            report.put("resourcePacks", Minecraft.getInstance().getResourcePackRepository().getSelectedIds());
            report.put("discoveryErrors", discoveryErrors); report.put("results", results);
            writeReport(false);
            LOGGER.info("Attachment test {} discovered {} wearable slot cases", label, cases.size());
        }

        @Override protected void init() { rows = Math.max(2, Math.min(6, (height - 60) / 132)); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public boolean shouldCloseOnEsc() { return true; }
        @Override public void onClose() { try { writeReport(complete); } catch (IOException e) { LOGGER.error("Report write failed", e); } super.onClose(); }

        private void equip(Case c, TestPlayer player) {
            AccessoriesCapability capability = AccessoriesCapability.get(player);
            for (EquipmentSlot slot : EquipmentSlot.values()) player.setItemSlot(slot, ItemStack.EMPTY);
            for (AccessoriesContainer container : capability.getContainers().values()) {
                container.getAccessories().clearContent(); container.getCosmeticAccessories().clearContent();
            }
            ItemStack stack = c.stack();
            ((net.mehvahdjukaar.supplementaries.common.entities.IQuiverEntity) (Object) player).supplementaries$setQuiver(
                    BuiltInRegistries.ITEM.getKey(c.item).toString().equals("supplementaries:quiver") ? stack : ItemStack.EMPTY);
            if (c.armor != null) player.setItemSlot(c.armor, stack);
            else {
                AccessoriesContainer container = capability.getContainers().get(c.slot);
                container.getAccessories().setItem(c.index, stack);
                if (c.index < container.renderOptions().size()) container.renderOptions().set(c.index, true);
            }
        }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xFF16212A);
            graphics.drawString(font, "EMF equipment sweep | " + label + " | " + (page + 1) + "/" + Math.max(1, (cases.size() + rows - 1) / rows), 10, 8, 0xFFFFFFFF);
            graphics.drawString(font, "Four controlled poses. Render checks + screenshots; visual alignment requires review.", 10, 22, 0xFFCAD5E0);
            if (complete) { graphics.drawString(font, "Complete: " + results.size() + " cases/poses. Esc returns to world.", 10, 44, 0xFF80FF9C); return; }
            int rowHeight = (height - 55) / rows;
            float columnWidth = (width - 20) / 4F;
            // FA Player's verti_drag starts at zero and converges on pos_y at
            // 16/second when age >= 9. These unticked clones start at age 120,
            // so a negative-world-Y clone initially looks like a large upward
            // jump to the cape animation. Let that real-time filter settle.
            if (pageReadyAfterNanos == 0) pageReadyAfterNanos = System.nanoTime() + 2_000_000_000L;
            boolean record = frames >= 5 && System.nanoTime() >= pageReadyAfterNanos;
            if (!report.containsKey("firstPersonProbe") && net.neoforged.fml.ModList.get().isLoaded("firstperson")) report.put("firstPersonProbe", FirstPersonProbe.run());
            for (int row = 0; row < rows && page * rows + row < cases.size(); row++) {
                Case c = cases.get(page * rows + row);
                int top = 42 + row * rowHeight;
                graphics.fill(6, top, width - 6, top + rowHeight - 2, row % 2 == 0 ? 0xFF223440 : 0xFF1D2D37);
                String id = BuiltInRegistries.ITEM.getKey(c.item).toString() + (c.dyeColor < 0 ? "" : " [dyed-blue]");
                graphics.drawString(font, id + " | " + c.slot + "[" + c.index + "]", 10, top + 3, 0xFFFFFFFF);
                for (int pose = 0; pose < POSES.length; pose++) {
                    TestPlayer player = posePlayers[pose];
                    equip(c, player);
                    player.applyPose(pose);
                    current = new TestResult(c, POSES[pose]);
                    float cx = 10 + columnWidth * (pose + 0.5F);
                    float cy = top + rowHeight - 8;
                    float scale = Math.min((rowHeight - 27) / 2F, columnWidth / 2.5F);
                    graphics.drawString(font, POSES[pose], (int)(10 + columnWidth * pose), top + 15, 0xFF9CC3D8);
                    try {
                        InventoryScreen.renderEntityInInventory(graphics, cx, cy, scale,
                                new Vector3f(), new Quaternionf().rotationZ((float)Math.PI).rotateY((rearView ? (float)Math.PI : 0F) - 0.35F), null, player);
                        var actualRenderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
                        if (actualRenderer instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer playerRenderer) {
                            current.playerModelClass = playerRenderer.getModel().getClass().getName();
                            current.headPartClass = playerRenderer.getModel().head.getClass().getName();
                            current.renderedHead = ModelState.state(playerRenderer.getModel().head);
                            var cape = player.getSkin().capeTexture();
                            current.capeTexture = cape == null ? null : cape.toString();
                        }
                        current.completed = true;
                    } catch (Throwable ex) {
                        current.exception = ex.toString();
                        LOGGER.error("Attachment test render failed: {} {} {}", id, c.slot, POSES[pose], ex);
                    }
                    if (record) results.add(current);
                    current = null;
                }
            }
            graphics.flush();
            frames++;
            if (record) capture = true;
        }

        void finishFrame() {
            if (!capture || complete) return;
            capture = false;
            try {
                try (NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                    image.writeToFile(output.resolve(String.format(Locale.ROOT, "page-%03d.png", page + 1)));
                }
                page++; frames = 0;
                pageReadyAfterNanos = System.nanoTime() + 250_000_000L;
                complete = page * rows >= cases.size();
                writeReport(complete);
                if (complete) LOGGER.info("Attachment sweep {} finished: {} pose results", label, results.size());
            } catch (Throwable ex) { complete = true; LOGGER.error("Attachment screenshot/report failed", ex); }
        }

        private void writeReport(boolean finished) throws IOException {
            report.put("finished", finished);
            report.put("renderExceptions", results.stream().filter(r -> !r.exception.isEmpty()).count());
            report.put("poseMutationCases", results.stream().filter(r -> r.mutatedParts > 0).count());
            report.put("nonFiniteGeometryCases", results.stream().filter(r -> r.invalidVertices > 0).count());
            report.put("accessoryRendererCalls", results.stream().mapToInt(r -> r.rendererCalls).sum());
            report.put("gogglesAnchorChecks", results.stream().mapToInt(r -> r.gogglesAnchorChecks).sum());
            report.put("gogglesAnchorFailures", results.stream().mapToInt(r -> r.gogglesAnchorFailures).sum());
            report.put("torsoAnchorChecks", results.stream().mapToInt(r -> r.torsoAnchorChecks).sum());
            report.put("torsoAnchorFailures", results.stream().mapToInt(r -> r.torsoAnchorFailures).sum());
            report.put("flowerAnchorChecks", results.stream().mapToInt(r -> r.flowerAnchorChecks).sum());
            report.put("flowerAnchorFailures", results.stream().mapToInt(r -> r.flowerAnchorFailures).sum());
            report.put("flowerColorChecks", results.stream().mapToInt(r -> r.flowerColorChecks).sum());
            report.put("flowerColorFailures", results.stream().mapToInt(r -> r.flowerColorFailures).sum());
            report.put("dyeTintChecks", results.stream().filter(r -> r.expectedTint != null).count());
            report.put("dyeTintFailures", results.stream().filter(r -> r.expectedTint != null && !r.tintObserved).count());
            report.put("screenshots", page);
            Files.writeString(output.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }
}



