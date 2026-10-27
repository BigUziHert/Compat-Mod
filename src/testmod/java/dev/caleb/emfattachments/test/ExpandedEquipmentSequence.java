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
public final class ExpandedEquipmentSequence {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static int readyTicks;
    private static String forcedSkinModel = "";
    private static boolean rearView;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ExpandedEquipmentSequence::tick);
        NeoForge.EVENT_BUS.addListener(ExpandedEquipmentSequence::frameFinished);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen instanceof SweepScreen) { readyTicks = 0; return; }
        if (++readyTicks < 40) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-expanded-equipment.txt");
        if (!Files.isRegularFile(trigger) || mc.screen != null || LiveFirstPersonSequence.isActive()) return;
        try {
            String request = Files.isRegularFile(trigger) ? Files.readString(trigger).trim() : "automatic";
            if (Files.isRegularFile(trigger)) Files.delete(trigger);
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

    private static AttachmentTestMod.TestResult result(Case c, String action) {
        var r = new AttachmentTestMod.TestResult();
        r.item = BuiltInRegistries.ITEM.getKey(c.item).toString(); r.slot = c.slot; r.slotIndex = c.index;
        r.renderer = c.renderer; r.itemClass = c.item.getClass().getName(); r.pose = action;
        r.variant = c.dyeColor < 0 ? "default" : "dyed-blue"; r.expectedTint = c.dyeColor < 0 ? null : c.dyeColor;
        return r;
    }
    private record Case(Item item, String slot, int index, EquipmentSlot armor, String renderer, int dyeColor) {
        private Case(Item item, String slot, int index, EquipmentSlot armor, String renderer) { this(item,slot,index,armor,renderer,-1); }
        ItemStack stack() {
            ItemStack stack = item.getDefaultInstance();
            if (dyeColor >= 0) stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(dyeColor, true));
            return stack;
        }
    }
    private static final String[] POSES = {"eat_native_17ticks", "drink_native_17ticks", "bow_native_20ticks", "sprint_fixture",
            "climb_fixture", "boat_passenger", "horse_passenger", "fall_fly_fixture", "roll_native_tick7", "attack_native_tick8", "half_scale_crouch", "crouch_turn"};
    private static final int ACTION_PAGES = (POSES.length + 3) / 4;

    private static final class TestPlayer extends RemotePlayer {
        private final PlayerSkin skin;
        private final net.minecraft.world.scores.PlayerTeam hiddenNameTeam = new net.minecraft.world.scores.PlayerTeam(new net.minecraft.world.scores.Scoreboard(), "attachment-test-clones");
        private boolean swimming, climbing, nativeAnimationPrepared;
        private net.minecraft.world.entity.Entity testMount;
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
        @Override public boolean onClimbable() { return climbing; }
        void beginUse(net.minecraft.world.item.Item item, int elapsed) {
            stopUsingItem(); setLivingEntityFlag(1, false);
            ItemStack stack = item.getDefaultInstance(); setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
            startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND); setLivingEntityFlag(1, true);
            for (int tick = 0; tick < elapsed; tick++) updateUsingItem(stack);
            if (!isUsingItem() || getUseItemRemainingTicks() != stack.getUseDuration(this) - elapsed)
                throw new IllegalStateException("Native clone use did not advance: " + item);
        }
        void applyPose(int pose) {
            climbing = pose == 4; swimming = false;
            stopUsingItem(); setLivingEntityFlag(1, false);
            boolean crouch = pose >= 10;
            setPose(crouch ? Pose.CROUCHING : Pose.STANDING);
            setShiftKeyDown(crouch); setSwimming(false); setSprinting(pose == 3);
            setOnGround(pose != 4 && pose != 7);
            setDeltaMovement(pose == 3 ? new Vec3(0.24, 0, 0.03) : pose == 4 ? new Vec3(0, 0.12, 0) : pose == 7 ? new Vec3(0.6, -0.03, 0) : Vec3.ZERO);
            walkAnimation.setSpeed(pose == 3 ? 1.3F : 0F); walkAnimation.update(pose == 3 ? 1.3F : 0F, 1F);
            tickCount = 120 + pose * 7;
            yBodyRot = yBodyRotO = 180F; yHeadRot = yHeadRotO = pose == 11 ? 225F : 180F;
            setYRot(yHeadRot); yRotO = yHeadRot;
            setXRot(pose == 2 ? -25F : crouch ? 35F : 0F); xRotO = getXRot();
            getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE).setBaseValue(pose == 10 ? 0.5 : 1);
            if (pose == 0) beginUse(net.minecraft.world.item.Items.BREAD, 17);
            if (pose == 1) beginUse(net.minecraft.world.item.Items.HONEY_BOTTLE, 17);
            if (pose == 2) beginUse(net.minecraft.world.item.Items.BOW, 20);
            if ((pose == 5 || pose == 6) && testMount == null) {
                testMount = (pose == 5 ? net.minecraft.world.entity.EntityType.BOAT : net.minecraft.world.entity.EntityType.HORSE).create(level());
                testMount.setPos(position());
                if (!startRiding(testMount, true) || !isPassenger()) throw new IllegalStateException("Native passenger fixture failed");
            }
            if (pose == 7) { startFallFlying(); fallFlyTicks = 20; }
            if (pose == 9) {
                setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("simplyswords:diamond_greathammer")).getDefaultInstance());
                ((net.bettercombat.logic.PlayerAttackProperties) (Object) this).setComboCount(0);
            }
            if ((pose == 8 || pose == 9) && !nativeAnimationPrepared) {
                nativeAnimationPrepared = true;
                if (pose == 8) ((net.combat_roll.client.animation.AnimatablePlayer) (Object) this).playRollAnimation("combat_roll:roll", new Vec3(0, 0, -1));
                else ((net.bettercombat.client.animation.PlayerAttackAnimatable) (Object) this).playAttackAnimation("bettercombat:two_handed_slam", net.bettercombat.logic.AnimatedHand.TWO_HANDED, 24, 0.5F);
                for (int i = 0; i < (pose == 8 ? 7 : 8); i++) ((dev.kosmx.playerAnim.impl.IAnimatedPlayer) (Object) this).getAnimationStack().tick();
                if (!((dev.kosmx.playerAnim.impl.IAnimatedPlayer) (Object) this).getAnimationStack().isActive()) throw new IllegalStateException("Native animation fixture inactive");
            }
            if (pose == 9 && strm.emfcompat.bettercombat.compat.BetterCombatCompat.getAttackHand(this) == null)
                throw new IllegalStateException("Remote EMF-BC bridge has no native attack hand");
        }
        Map<String,Object> actionState() {
            var state = new LinkedHashMap<String,Object>();
            state.put("fixture", "controlled remote clone; native use/animation advancement where named; not live gameplay");
            state.put("usingItem", isUsingItem()); state.put("useTicks", getTicksUsingItem()); state.put("remainingUseTicks", getUseItemRemainingTicks());
            state.put("passenger", isPassenger()); state.put("mount", getVehicle() == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(getVehicle().getType()).toString());
            state.put("climbing", onClimbable()); state.put("fallFlying", isFallFlying()); state.put("sprinting", isSprinting());
            state.put("bridgeAttackHand", strm.emfcompat.bettercombat.compat.BetterCombatCompat.getAttackHand(this) != null);
            state.put("scale", getScale()); state.put("animationActive", ((dev.kosmx.playerAnim.impl.IAnimatedPlayer) (Object) this).getAnimationStack().isActive());
            return state;
        }
    }

    private static final class SweepScreen extends Screen {
        private final TestPlayer player = new TestPlayer();
        private final TestPlayer[] posePlayers = java.util.stream.IntStream.range(0, POSES.length).mapToObj(i -> i == 0 ? player : new TestPlayer()).toArray(TestPlayer[]::new);
        private final List<Case> cases = new ArrayList<>();
        private final List<AttachmentTestMod.TestResult> results = new ArrayList<>();
        private final List<Map<String,Object>> actionMatrix = new ArrayList<>();
        private final List<String> discoveryErrors = new ArrayList<>();
        private final Path output;
        private final String label;
        private int page, frames, rows = 4;
        private long pageReadyAfterNanos;
        private boolean capture, complete;
        private final AccessoriesCapability capability;
        private final Map<String, Object> report = new LinkedHashMap<>();

        SweepScreen(String label, String filter) throws IOException {
            super(Component.literal("Expanded equipment action matrix"));
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
                        var registered = AccessoriesRendererRegistry.getRenderer(stack);
                        String renderer = registered == null ? "no-registered-accessory-renderer" : registered.getClass().getName();
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
            report.put("label", label); report.put("scope", "Runtime registry wearable armor/head items and every actual Accessories-valid item/slot/index (armor UI aliases excluded as duplicates); twelve additional controlled RemotePlayer action contexts including real native use-tick advancement and installed native animation layers. Climb/sprint/fall-fly are render fixtures, not gameplay certification.");
            report.put("caseCount", cases.size()); report.put("uniqueItems", cases.stream().map(Case::item).distinct().count()); report.put("expectedPoseRenders", cases.size() * POSES.length); report.put("poses", POSES); report.put("actionMatrix", actionMatrix);
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
            player.stopUsingItem();
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
            graphics.drawString(font, "EMF equipment sweep | " + label + " | " + (page + 1) + "/" + Math.max(1, ((cases.size() + rows - 1) / rows) * ACTION_PAGES), 10, 8, 0xFFFFFFFF);
            graphics.drawString(font, "12 additional action contexts; clone/native-animation evidence, separate from actual movement.", 10, 22, 0xFFCAD5E0);
            if (complete) { graphics.drawString(font, "Complete: " + results.size() + " cases/poses. Esc returns to world.", 10, 44, 0xFF80FF9C); return; }
            int rowHeight = (height - 55) / rows;
            float columnWidth = (width - 20) / 4F;
            // FA Player's verti_drag starts at zero and converges on pos_y at
            // 16/second when age >= 9. These unticked clones start at age 120,
            // so a negative-world-Y clone initially looks like a large upward
            // jump to the cape animation. Let that real-time filter settle.
            if (pageReadyAfterNanos == 0) pageReadyAfterNanos = System.nanoTime() + 2_000_000_000L;
            boolean record = frames >= 5 && System.nanoTime() >= pageReadyAfterNanos;
            
            for (int row = 0; row < rows && (page / ACTION_PAGES) * rows + row < cases.size(); row++) {
                Case c = cases.get((page / ACTION_PAGES) * rows + row);
                int top = 42 + row * rowHeight;
                graphics.fill(6, top, width - 6, top + rowHeight - 2, row % 2 == 0 ? 0xFF223440 : 0xFF1D2D37);
                String id = BuiltInRegistries.ITEM.getKey(c.item).toString() + (c.dyeColor < 0 ? "" : " [dyed-blue]");
                graphics.drawString(font, id + " | " + c.slot + "[" + c.index + "]", 10, top + 3, 0xFFFFFFFF);
                for (int column = 0; column < 4; column++) {
                    int pose = (page % ACTION_PAGES) * 4 + column;
                    TestPlayer player = posePlayers[pose];
                    AttachmentTestMod.current = result(c, POSES[pose]);
                    float cx = 10 + columnWidth * (column + 0.5F);
                    float cy = top + rowHeight - 8;
                    float scale = Math.min((rowHeight - 27) / 2F, columnWidth / 2.5F);
                    // A forward-flying model is almost end-on in the standing
                    // portrait angle; use a centered side view for its full span.
                    if (pose == 7) { cy = top + (rowHeight + 28) / 2F; scale *= 0.8F; }
                    graphics.drawString(font, POSES[pose], (int)(10 + columnWidth * column), top + 15, 0xFF9CC3D8);
                    try {
                        equip(c, player); player.applyPose(pose);
                        InventoryScreen.renderEntityInInventory(graphics, cx, cy, scale,
                                new Vector3f(), new Quaternionf().rotationZ((float)Math.PI).rotateY(pose == 7 ? (float)Math.PI / 2F : (rearView ? (float)Math.PI : 0F) - 0.35F), null, player);
                        var actualRenderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
                        if (actualRenderer instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer playerRenderer) {
                            AttachmentTestMod.current.playerModelClass = playerRenderer.getModel().getClass().getName();
                            AttachmentTestMod.current.headPartClass = playerRenderer.getModel().head.getClass().getName();
                            AttachmentTestMod.current.renderedHead = ModelState.state(playerRenderer.getModel().head);
                            var cape = player.getSkin().capeTexture();
                            AttachmentTestMod.current.capeTexture = cape == null ? null : cape.toString();
                        }
                        AttachmentTestMod.current.completed = true;
                    } catch (Throwable ex) {
                        AttachmentTestMod.current.exception = ex.toString();
                        LOGGER.error("Attachment test render failed: {} {} {}", id, c.slot, POSES[pose], ex);
                    }
                    if (record) {
                        results.add(AttachmentTestMod.current);
                        var state = player.actionState(); state.put("item", id); state.put("slot", c.slot); state.put("slotIndex", c.index); state.put("action", POSES[pose]);
                        actionMatrix.add(state);
                    }
                    AttachmentTestMod.current = null;
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
                complete = page >= ((cases.size() + rows - 1) / rows) * ACTION_PAGES;
                writeReport(complete);
                if (complete) { LOGGER.info("Expanded sweep {} finished: {} pose results", label, results.size()); Minecraft.getInstance().setScreen(null); }
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
            Files.writeString(output.resolve("expanded-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }
}



