package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** Explicitly triggered developer-only screenshots of the real FirstPerson camera pass. */
public final class LiveFirstPersonSequence {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EquipmentSlot[] EQUIPMENT = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static Session active;

    private LiveFirstPersonSequence() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(LiveFirstPersonSequence::tick);
        NeoForge.EVENT_BUS.addListener(LiveFirstPersonSequence::beforeFrame);
        NeoForge.EVENT_BUS.addListener(LiveFirstPersonSequence::afterFrame);
    }

    public static boolean isActive() { return active != null; }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) {
            if (mc.player != active.player || mc.level == null || mc.screen != null) {
                active.finish("ABORTED", "World/player changed or a screen was opened");
                active = null;
                return;
            }
            try { active.apply(); active.ticks++; }
            catch (Throwable error) { active.finish("FAIL", error.toString()); active = null; }
            return;
        }
        if (mc.player == null || mc.level == null || mc.screen != null || CombatRenderCounters.active) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-live-firstperson.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            // This harness may replace the local player's rendered equipment. It is
            // intentionally restricted to this workspace's disposable test client.
            Path gameDir = mc.gameDirectory.toPath().toAbsolutePath().normalize();
            if (!gameDir.endsWith(Path.of(".local", "client")) || !mc.hasSingleplayerServer()) {
                throw new IllegalStateException("Live capture requires the isolated .local/client singleplayer world");
            }
            if (!ModList.get().isLoaded("firstperson")) throw new IllegalStateException("FirstPerson is absent");
            String label = Files.readString(trigger).trim().replaceAll("[^a-zA-Z0-9._-]", "_");
            Files.delete(trigger);
            active = new Session(mc, label.isBlank() ? "live-firstperson" : label);
            active.apply();
            LOGGER.info("Started live FirstPerson screenshot sequence {}", label);
        } catch (Throwable error) {
            if (active != null) active.finish("FAIL", error.toString());
            active = null;
            LOGGER.error("Live FirstPerson capture startup failed", error);
            try { Files.move(trigger, trigger.resolveSibling("emfattachments-live-firstperson.failed.txt"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { }
        }
    }

    private static void beforeFrame(RenderFrameEvent.Pre event) {
        if (active == null) return;
        CombatRenderCounters.active = STAGES[active.stageIndex].slot.equals("use");
        CombatRenderCounters.reset();
    }

    private static void afterFrame(RenderFrameEvent.Post event) {
        if (active == null) return;
        try {
            active.frames++;
            int neededTicks = STAGES[active.stageIndex].slot.equals("use") ? 18 : 40;
            if (active.ticks < neededTicks || active.frames < 10) return;
            active.capture();
            if (++active.stageIndex >= STAGES.length) {
                active.finish("CAPTURED", "Review screenshots for visual camera/body alignment");
                active = null;
            } else {
                active.player.stopUsingItem();
                active.mc.options.keyUse.setDown(false);
                active.ticks = 0; active.frames = 0;
            }
        } catch (Throwable error) {
            active.finish("FAIL", error.toString());
            active = null;
        }
    }

    private record Stage(String name, CameraType camera, float pitch, boolean crouch,
                         boolean enabled, String item, String slot, Boolean neaEating) {
        Stage(String name, CameraType camera, float pitch, boolean crouch, boolean enabled, String item, String slot) {
            this(name, camera, pitch, crouch, enabled, item, slot, null);
        }
    }
    private static final Stage[] STAGES = {
            new Stage("firstperson_idle", CameraType.FIRST_PERSON, 0, false, true, "", ""),
            new Stage("firstperson_look_down", CameraType.FIRST_PERSON, 72, false, true, "", ""),
            new Stage("firstperson_look_up", CameraType.FIRST_PERSON, -65, false, true, "", ""),
            new Stage("firstperson_crouch_down", CameraType.FIRST_PERSON, 55, true, true, "", ""),
            new Stage("aviator_armor_down", CameraType.FIRST_PERSON, 72, false, true, "aeronautics:aviators_goggles", "armor"),
            new Stage("aviator_accessory_idle", CameraType.FIRST_PERSON, 0, false, true, "aeronautics:aviators_goggles", "accessory"),
            new Stage("aviator_accessory_down", CameraType.FIRST_PERSON, 72, false, true, "aeronautics:aviators_goggles", "accessory"),
            new Stage("aviator_accessory_crouch", CameraType.FIRST_PERSON, 55, true, true, "aeronautics:aviators_goggles", "accessory"),
            new Stage("aviator_cosmetic_down", CameraType.FIRST_PERSON, 72, false, true, "aeronautics:aviators_goggles", "cosmetic"),
            new Stage("cosmetic_armor_head_down", CameraType.FIRST_PERSON, 72, false, true, "aeronautics:aviators_goggles", "armor_cosmetic"),
            new Stage("cosmetic_armor_head_thirdperson", CameraType.THIRD_PERSON_FRONT, 8, false, true, "aeronautics:aviators_goggles", "armor_cosmetic"),
            new Stage("create_goggles_accessory_down", CameraType.FIRST_PERSON, 72, false, true, "create:goggles", "accessory"),
            new Stage("flower_headwear_accessory_down", CameraType.FIRST_PERSON, 72, false, true, "the_bumblezone:flower_headwear", "accessory"),
            new Stage("flower_headwear_thirdperson_front", CameraType.THIRD_PERSON_FRONT, 8, false, true, "the_bumblezone:flower_headwear", "accessory"),
            new Stage("aviator_thirdperson_front", CameraType.THIRD_PERSON_FRONT, 8, false, true, "aeronautics:aviators_goggles", "accessory"),
            new Stage("aviator_thirdperson_back", CameraType.THIRD_PERSON_BACK, 8, false, true, "aeronautics:aviators_goggles", "accessory"),
            new Stage("aviator_firstperson_return", CameraType.FIRST_PERSON, 72, false, true, "aeronautics:aviators_goggles", "accessory"),
            new Stage("firstperson_disabled_down", CameraType.FIRST_PERSON, 72, false, false, "aeronautics:aviators_goggles", "accessory"),
            new Stage("firstperson_reenabled_down", CameraType.FIRST_PERSON, 72, false, true, "aeronautics:aviators_goggles", "accessory"),
            new Stage("blue_cape_thirdperson_back", CameraType.THIRD_PERSON_BACK, 8, false, true, "aether:blue_cape", "accessory_any"),
            new Stage("blue_cape_thirdperson_crouch", CameraType.THIRD_PERSON_BACK, 8, true, true, "aether:blue_cape", "accessory_any"),
            new Stage("food_nea_enabled", CameraType.FIRST_PERSON, 45, false, true, "minecraft:bread", "use", true),
            new Stage("food_nea_disabled", CameraType.FIRST_PERSON, 45, false, true, "minecraft:bread", "use", false),
            new Stage("drink_nea_enabled", CameraType.FIRST_PERSON, 45, false, true, "minecraft:honey_bottle", "use", true),
            new Stage("drink_nea_disabled", CameraType.FIRST_PERSON, 45, false, true, "minecraft:honey_bottle", "use", false),
            new Stage("shield_using_firstperson", CameraType.FIRST_PERSON, 45, false, true, "minecraft:shield", "use"),
            new Stage("spyglass_using_firstperson", CameraType.FIRST_PERSON, 10, false, true, "minecraft:spyglass", "use"),
            new Stage("food_nea_thirdperson", CameraType.THIRD_PERSON_FRONT, 5, false, true, "minecraft:bread", "use", true),
            new Stage("drink_nea_thirdperson", CameraType.THIRD_PERSON_FRONT, 5, false, true, "minecraft:honey_bottle", "use", true)
    };

    private record ContainerState(List<ItemStack> items, List<ItemStack> cosmetics, List<Boolean> renders) { }

    private static final class Session {
        final Minecraft mc;
        final LocalPlayer player;
        final Path output;
        final CameraType originalCamera;
        final boolean originalGui, originalFpm, originalShiftKey, originalUseKey, originalShift, originalSprint;
        final float originalYaw, originalPitch, originalBodyYaw, originalHeadYaw;
        final Pose originalPose;
        final Vec3 originalPosition, originalMotion;
        final boolean originalNeaEating;
        final String originalNeaConfig;
        final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        final Map<String, ContainerState> containers = new LinkedHashMap<>();
        final List<Map<String, Object>> captures = new ArrayList<>();
        final Map<String, Object> report = new LinkedHashMap<>();
        final AccessoriesCapability capability;
        int stageIndex, ticks, frames;
        int preparedUseStage = -1, initialUseDuration;
        ItemStack stageUseStack = ItemStack.EMPTY;
        String resolvedSlot = "";

        Session(Minecraft mc, String label) throws IOException {
            this.mc = mc; player = mc.player;
            if (player.isUsingItem()) throw new IllegalStateException("Finish the current item-use action before the isolated camera test");
            originalNeaEating = dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.enableEatDrinkAnimation;
            originalNeaConfig = new com.google.gson.Gson().toJson(dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config);
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);
            Files.createDirectories(output);
            capability = AccessoriesCapability.get(player);
            if (capability == null) throw new IllegalStateException("Local player has no Accessories capability");
            originalCamera = mc.options.getCameraType(); originalGui = mc.options.hideGui;
            originalFpm = FirstPersonAPI.isEnabled(); originalShiftKey = mc.options.keyShift.isDown();
            originalUseKey = mc.options.keyUse.isDown();
            originalShift = player.isShiftKeyDown(); originalSprint = player.isSprinting();
            originalPose = player.getPose(); originalPosition = player.position(); originalMotion = player.getDeltaMovement();
            originalYaw = player.getYRot(); originalPitch = player.getXRot();
            originalBodyYaw = player.yBodyRot; originalHeadYaw = player.yHeadRot;
            for (EquipmentSlot slot : EQUIPMENT) equipment.put(slot, player.getItemBySlot(slot).copy());
            capability.getContainers().forEach((name, container) -> {
                List<ItemStack> items = new ArrayList<>(), cosmetics = new ArrayList<>();
                for (int i = 0; i < container.getSize(); i++) {
                    items.add(container.getAccessories().getItem(i).copy());
                    cosmetics.add(container.getCosmeticAccessories().getItem(i).copy());
                }
                containers.put(name, new ContainerState(items, cosmetics, new ArrayList<>(container.renderOptions())));
            });
            report.put("label", label); report.put("scope", "Real local-player camera screenshots; 40 game ticks per equipment/camera stage, 18 ticks per active item-use stage. Temporary NEA eat/drink toggle is restored; no config is saved. Disposable integrated test world only.");
            report.put("resourcePacks", mc.getResourcePackRepository().getSelectedIds());
            report.put("fov", mc.options.fov().get()); report.put("captures", captures);
            report.put("firstPersonProbe", FirstPersonProbe.run());
            write("RUNNING", "");
        }

        void apply() {
            Stage stage = STAGES[stageIndex];
            dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.enableEatDrinkAnimation = stage.neaEating == null ? originalNeaEating : stage.neaEating;
            if (!stage.slot.equals("use")) player.stopUsingItem();
            mc.options.setCameraType(stage.camera); mc.options.hideGui = true;
            mc.options.keyShift.setDown(stage.crouch); FirstPersonAPI.setEnabled(stage.enabled);
            // Minecraft.handleKeybinds releases an active use action whenever
            // this key is up. Hold it for the whole action, just as a user does.
            mc.options.keyUse.setDown(stage.slot.equals("use"));
            player.setPos(originalPosition); player.setDeltaMovement(Vec3.ZERO);
            player.setYRot(originalYaw); player.yRotO = originalYaw;
            player.yBodyRot = player.yBodyRotO = originalYaw;
            player.yHeadRot = player.yHeadRotO = originalYaw;
            player.setXRot(stage.pitch); player.xRotO = stage.pitch;
            player.setShiftKeyDown(stage.crouch); player.setSprinting(false);
            player.setPose(stage.crouch ? Pose.CROUCHING : Pose.STANDING);
            // Keeping the exact stack reference is essential: replacing it each
            // tick stops/restarts vanilla use and produces a false frame-zero capture.
            if (stage.slot.equals("use") && preparedUseStage == stageIndex) return;
            // End a previous stage before replacing the stack. Vanilla input can
            // otherwise restart its old action before this post-tick callback.
            player.stopUsingItem();
            for (EquipmentSlot slot : EQUIPMENT) player.setItemSlot(slot, ItemStack.EMPTY);
            for (AccessoriesContainer container : capability.getContainers().values()) {
                container.getAccessories().clearContent(); container.getCosmeticAccessories().clearContent();
            }
            resolvedSlot = "none";
            if (stage.item.isEmpty()) return;
            ItemStack stack = BuiltInRegistries.ITEM.get(ResourceLocation.parse(stage.item)).getDefaultInstance();
            if (stack.isEmpty()) throw new IllegalStateException("Unknown capture item: " + stage.item);
            if (stage.slot.equals("use")) {
                preparedUseStage = stageIndex;
                stageUseStack = stack;
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                player.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                initialUseDuration = player.getUseItemRemainingTicks();
                if (!player.isUsingItem() || initialUseDuration <= 0 || initialUseDuration != stack.getUseDuration(player)) {
                    throw new IllegalStateException("Cannot start use fixture " + stage.name + ": remaining="
                            + initialUseDuration + ", expected=" + stack.getUseDuration(player));
                }
                resolvedSlot = "mainhand:use";
                return;
            }
            if (stage.slot.equals("armor")) {
                player.setItemSlot(EquipmentSlot.HEAD, stack); resolvedSlot = "equipment:head"; return;
            }
            if (stage.slot.equals("armor_cosmetic")) {
                var slot = io.wispforest.accessories.menu.ArmorSlotTypes.headSlot();
                AccessoriesContainer container = capability.getContainer(slot);
                if (container == null || container.getSize() == 0) throw new IllegalStateException("Virtual armor head container absent");
                container.getCosmeticAccessories().setItem(0, stack);
                if (!container.renderOptions().isEmpty()) container.renderOptions().set(0, true);
                resolvedSlot = container.getSlotName() + ":0:armor_cosmetic";
                return;
            }
            var slots = AccessoriesAPI.getStackSlotTypes(player, stack).stream()
                    .filter(slot -> stage.slot.equals("accessory_any") || slot.name().contains("head")
                            || slot.name().contains("hat") || slot.name().contains("face"))
                    // Virtual armor aliases can also appear in the capability
                    // map. Explicitly exclude them so Curios' bridged hat slot
                    // is exercised rather than equipping the vanilla helmet twice.
                    .filter(slot -> !io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(slot.name()))
                    .filter(slot -> capability.getContainers().containsKey(slot.name()))
                    .sorted(java.util.Comparator.comparing(slot -> slot.name())).toList();
            if (slots.isEmpty()) throw new IllegalStateException("No allowed accessory slot for " + stage.item);
            AccessoriesContainer container = capability.getContainers().get(slots.getFirst().name());
            if (container == null || container.getSize() == 0) throw new IllegalStateException("Allowed accessory slot container absent");
            if (stage.slot.equals("cosmetic")) {
                // A normal underlying item ensures this specifically exercises the
                // cosmetic override path in AccessoriesRenderLayer.
                ItemStack underlying = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:goggles")).getDefaultInstance();
                container.getAccessories().setItem(0, underlying);
                container.getCosmeticAccessories().setItem(0, stack);
            } else container.getAccessories().setItem(0, stack);
            if (!container.renderOptions().isEmpty()) container.renderOptions().set(0, true);
            resolvedSlot = container.getSlotName() + ":0:" + stage.slot;
        }

        void capture() throws IOException {
            Stage stage = STAGES[stageIndex];
            if (stage.slot.equals("use") && (!player.isUsingItem()
                    || player.getUseItem() != stageUseStack
                    || player.getMainHandItem() != stageUseStack
                    || player.getUseItemRemainingTicks() <= 0
                    || initialUseDuration - player.getUseItemRemainingTicks() < 10
                    || !BuiltInRegistries.ITEM.getKey(player.getUseItem().getItem()).toString().equals(stage.item))) {
                throw new IllegalStateException("Item-use fixture is not active for " + stage.name
                        + ": using=" + player.isUsingItem() + ", keyUse=" + mc.options.keyUse.isDown()
                        + ", remaining=" + player.getUseItemRemainingTicks() + ", initial=" + initialUseDuration
                        + ", sameUseStack=" + (player.getUseItem() == stageUseStack)
                        + ", sameHandStack=" + (player.getMainHandItem() == stageUseStack));
            }
            String file = String.format(Locale.ROOT, "%02d-%s.png", stageIndex + 1, stage.name);
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(output.resolve(file));
            }
            Map<String, Object> capture = new LinkedHashMap<>();
            capture.put("stage", stage.name); capture.put("screenshot", file);
            capture.put("camera", mc.options.getCameraType().name()); capture.put("pitch", player.getXRot());
            capture.put("pose", player.getPose().name()); capture.put("fpmEnabled", FirstPersonAPI.isEnabled());
            capture.put("item", stage.item); capture.put("slot", resolvedSlot);
            capture.put("ticks", ticks); capture.put("frames", frames);
            capture.put("usingItem", player.isUsingItem());
            capture.put("useTicksRemaining", player.getUseItemRemainingTicks());
            if (stage.slot.equals("use")) {
                capture.put("useInitialDuration", initialUseDuration);
                capture.put("useTicksElapsed", initialUseDuration - player.getUseItemRemainingTicks());
                capture.put("useProgressed", true);
                capture.put("useKeyHeld", mc.options.keyUse.isDown());
                capture.put("itemRenderCounts", CombatRenderCounters.snapshot());
            }
            capture.put("neaEatDrinkEnabled", dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.enableEatDrinkAnimation);
            captures.add(capture); write("RUNNING", "");
        }

        void finish(String status, String detail) {
            CombatRenderCounters.active = false;
            CombatRenderCounters.reset();
            try {
                player.stopUsingItem();
                dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.enableEatDrinkAnimation = originalNeaEating;
                mc.options.setCameraType(originalCamera); mc.options.hideGui = originalGui;
                mc.options.keyShift.setDown(originalShiftKey); FirstPersonAPI.setEnabled(originalFpm);
                mc.options.keyUse.setDown(originalUseKey);
                player.setPos(originalPosition); player.setDeltaMovement(originalMotion);
                player.setYRot(originalYaw); player.yRotO = originalYaw;
                player.setXRot(originalPitch); player.xRotO = originalPitch;
                player.yBodyRot = player.yBodyRotO = originalBodyYaw;
                player.yHeadRot = player.yHeadRotO = originalHeadYaw;
                player.setShiftKeyDown(originalShift); player.setSprinting(originalSprint); player.setPose(originalPose);
                equipment.forEach((slot, stack) -> player.setItemSlot(slot, stack.copy()));
                containers.forEach((name, state) -> {
                    AccessoriesContainer container = capability.getContainers().get(name);
                    if (container == null) return;
                    for (int i = 0; i < Math.min(container.getSize(), state.items.size()); i++) {
                        container.getAccessories().setItem(i, state.items.get(i).copy());
                        container.getCosmeticAccessories().setItem(i, state.cosmetics.get(i).copy());
                    }
                    for (int i = 0; i < Math.min(container.renderOptions().size(), state.renders.size()); i++) {
                        container.renderOptions().set(i, state.renders.get(i));
                    }
                });
                boolean neaRestored = originalNeaConfig.equals(new com.google.gson.Gson().toJson(dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config));
                report.put("neaSettingsRestored", neaRestored);
                report.put("useKeyRestored", mc.options.keyUse.isDown() == originalUseKey);
                if (!neaRestored) { status = "FAIL"; detail = "NEA settings did not restore"; }
                write(status, detail);
                LOGGER.info("Live FirstPerson sequence {}: {} screenshots, {}", status, captures.size(), detail);
            } catch (Throwable failure) { LOGGER.error("Live FirstPerson restore/report failed", failure); }
        }

        void write(String status, String detail) throws IOException {
            report.put("status", status); report.put("detail", detail);
            Files.writeString(output.resolve("live-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }
}
