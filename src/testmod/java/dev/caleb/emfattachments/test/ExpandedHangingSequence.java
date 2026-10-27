package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.telepathicgrunt.the_bumblezone.modinit.BzDataComponents;
import com.telepathicgrunt.the_bumblezone.modinit.BzStats;
import com.telepathicgrunt.the_bumblezone.modinit.BzTags;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Native Carpenter Bee Boots hanging and release, with an unequipped fall control. */
public final class ExpandedHangingSequence {
    private static Session active;
    private static volatile long serverTicks;
    private ExpandedHangingSequence() {}
    public static void register() {
        if (!ModList.get().isLoaded("the_bumblezone")) return;
        NeoForge.EVENT_BUS.addListener(ExpandedHangingSequence::tick);
        NeoForge.EVENT_BUS.addListener(ExpandedHangingSequence::frame);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) -> serverTicks++);
    }
    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (active != null) { if (active.tick()) active = null; return; }
            if (mc.player == null || mc.level == null || mc.screen != null || CombatRenderCounters.active) return;
            Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-expanded-hanging.txt");
            if (!Files.isRegularFile(trigger)) return;
            if (!mc.hasSingleplayerServer() || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client")))
                throw new IllegalStateException("Requires disposable integrated .local/client");
            String label = Files.readString(trigger).trim().replaceAll("[^a-zA-Z0-9._-]", "_");
            Files.delete(trigger); active = new Session(mc, label);
        } catch (Throwable failure) {
            if (active != null) active.finish("FAIL", failure.toString());
            else LogUtils.getLogger().error("Native hanging startup failed", failure);
        }
    }
    private static void frame(RenderFrameEvent.Post event) {
        if (active != null) try { active.frame(); } catch (Throwable failure) { active.finish("FAIL", failure.toString()); }
    }
    private record SavedAccessories(List<ItemStack> normal, List<ItemStack> cosmetic, List<Boolean> renders) {}
    private static final class Session {
        final Minecraft mc;
        final Path output;
        final CameraType originalCamera;
        final boolean originalGui;
        final Vec3 originalPosition;
        final float originalYaw, originalPitch;
        final Map<KeyMapping, Boolean> keys = new LinkedHashMap<>();
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final Map<String, SavedAccessories> accessories = new LinkedHashMap<>();
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<Map<String, Object>> captures = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        ServerPlayer player;
        ListTag inventory;
        GameType gameType;
        boolean originalFlying, dropped, finishing, restored;
        long originalDayTime, resumeServerTick;
        int stage, ticks, nativeHangStatBefore, cleanupTicks, preparationRecoveries;
        Vec3 dropPosition;
        BlockPos floor;
        CompletableFuture<Void> pending;
        CompletableFuture<Map<String, Object>> observation;
        String status, detail;
        Session(Minecraft mc, String label) throws Exception {
            this.mc = mc;
            if (mc.player.isPassenger() || mc.player.isUsingItem()) throw new IllegalStateException("Start recovered, unmounted and not using an item");
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label.isBlank() ? "expanded-hanging" : label);
            Files.createDirectories(output);
            originalCamera = mc.options.getCameraType(); originalGui = mc.options.hideGui;
            originalPosition = mc.player.position(); originalYaw = mc.player.getYRot(); originalPitch = mc.player.getXRot();
            for (KeyMapping key : List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                    mc.options.keyJump, mc.options.keyShift, mc.options.keySprint, mc.options.keyAttack, mc.options.keyUse)) keys.put(key, key.isDown());
            keys.keySet().forEach(key -> key.setDown(false)); mc.options.hideGui = false; CombatRenderCounters.active = true;
            report.put("firstPersonInstalled", ModList.get().isLoaded("firstperson"));
            report.put("scope", "Actual Carpenter Bee Boots armor tick beside a native tagged oak-log wall: unequipped falling, equipped hanging in both cameras, and native sneak release. No forced movement, onGround or pose flags.");
            report.put("captures", captures); report.put("failures", failures);
            pending = task(() -> {
                player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                inventory = player.getInventory().save(new ListTag()); gameType = player.gameMode.getGameModeForPlayer();
                originalFlying = player.getAbilities().flying; originalDayTime = player.serverLevel().getDayTime();
                var cap = AccessoriesCapability.get(player);
                cap.getContainers().forEach((name, container) -> {
                    List<ItemStack> normal = new ArrayList<>(), cosmetic = new ArrayList<>();
                    for (int i = 0; i < container.getSize(); i++) { normal.add(container.getAccessories().getItem(i).copy()); cosmetic.add(container.getCosmeticAccessories().getItem(i).copy()); }
                    accessories.put(name, new SavedAccessories(normal, cosmetic, new ArrayList<>(container.renderOptions())));
                });
                floor = BlockPos.containing(originalPosition).offset(24, 6, 24);
                for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 0; y <= 12; y++)
                    setBlock(floor.offset(x, y, z), y == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                if (!Blocks.OAK_LOG.defaultBlockState().is(BzTags.CARPENTER_BEE_BOOTS_CLIMBABLES)) throw new IllegalStateException("Oak log absent from native hanging support tag");
                for (int x = -2; x <= 2; x++) for (int y = 1; y <= 11; y++) setBlock(floor.offset(x, y, -1), Blocks.OAK_LOG.defaultBlockState());
                prepare();
            });
            write("PREPARING", "");
        }
        CompletableFuture<Void> task(Runnable action) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            mc.getSingleplayerServer().execute(() -> { try { action.run(); future.complete(null); } catch (Throwable failure) { future.completeExceptionally(failure); } });
            return future;
        }
        void setBlock(BlockPos pos, BlockState state) {
            if (player.serverLevel().getBlockEntity(pos) != null) throw new IllegalStateException("Fixture would replace block entity " + pos);
            blocks.putIfAbsent(pos.immutable(), player.serverLevel().getBlockState(pos)); player.serverLevel().setBlock(pos, state, 2);
        }
        int hangStat() { return player.getStats().getValue(Stats.CUSTOM.get(BzStats.CARPENTER_BEE_BOOTS_WALL_HANG_TIME_RL.get())); }
        void prepare() {
            ExpandedRagdollRecovery.release(player); player.stopRiding(); player.stopUsingItem();
            player.getInventory().clearContent(); var cap = AccessoriesCapability.get(player);
            cap.getContainers().values().forEach(c -> { c.getAccessories().clearContent(); c.getCosmeticAccessories().clearContent(); });
            ItemStack goggles = BuiltInRegistries.ITEM.get(ResourceLocation.parse("aeronautics:aviators_goggles")).getDefaultInstance();
            var slot = AccessoriesAPI.getStackSlotTypes(player, goggles).stream().filter(s -> !io.wispforest.accessories.menu.ArmorSlotTypes.isArmorType(s.name()))
                    .filter(s -> cap.getContainer(s) != null && cap.getContainer(s).getSize() > 0).findFirst().orElseThrow();
            cap.getContainer(slot).getAccessories().setItem(0, goggles); cap.getContainer(slot).renderOptions().set(0, true);
            if (stage > 0) player.setItemSlot(EquipmentSlot.FEET, BuiltInRegistries.ITEM.get(ResourceLocation.parse("the_bumblezone:carpenter_bee_boots_1")).getDefaultInstance());
            player.setGameMode(GameType.CREATIVE); player.getAbilities().flying = false; player.onUpdateAbilities();
            player.serverLevel().setDayTime(6000);
            player.teleportTo(player.serverLevel(), floor.getX() + .5, floor.getY() + 1, floor.getZ() + .31, 180, stage == 2 ? 5 : 65);
            player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0; player.inventoryMenu.broadcastChanges();
            nativeHangStatBefore = hangStat();
        }
        boolean tick() throws Exception {
            if (pending != null) {
                if (!pending.isDone()) return false;
                try { pending.join(); }
                catch (java.util.concurrent.CompletionException failure) {
                    if (!finishing) throw failure;
                    status = "FAIL"; detail += "; restoration failed: " + failure;
                } finally { pending = null; }
            }
            if (finishing) {
                if (!restored) {
                    if (serverTicks < resumeServerTick) return false;
                    restored = true; pending = task(this::restoreServer); return false;
                }
                if (++cleanupTicks < 20) return false;
                mc.options.setCameraType(originalCamera); mc.options.hideGui = originalGui;
                mc.player.setYRot(originalYaw); mc.player.setXRot(originalPitch); keys.forEach(KeyMapping::setDown);
                report.put("inputRestored", keys.entrySet().stream().allMatch(e -> e.getKey().isDown() == e.getValue()));
                report.put("nativeSeatReleased", ExpandedRagdollRecovery.clientRestored(mc.player)); CombatRenderCounters.active = false;
                write(status, detail); return true;
            }
            if (mc.screen != null) { finish("ABORTED", "Screen opened"); return false; }
            keys.keySet().forEach(key -> key.setDown(false));
            if (resumeServerTick > 0) {
                if (serverTicks < resumeServerTick) return false;
                resumeServerTick = 0; pending = task(this::prepare); return false;
            }
            mc.options.setCameraType(stage == 2 ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
            mc.player.setYRot(180); mc.player.setXRot(stage == 2 ? 5 : 65);
            if (!dropped) {
                // Grounded warmup lets native armor ticking initialize its hang
                // timer and synchronizes the exact equipped stack before falling.
                if (++ticks < 20) return false;
                if (mc.player.isPassenger() && !ExpandedRagdollRecovery.clientRestored(mc.player)) {
                    if (++preparationRecoveries > 2) throw new IllegalStateException("Native ragdoll persisted through independent hanging preparation");
                    ticks = 0; resumeServerTick = serverTicks + 15; return false;
                }
                if (!mc.player.onGround()) { if (ticks > 150) throw new IllegalStateException("Grounded equipment warmup failed"); return false; }
                dropped = true; ticks = 0;
                dropPosition = new Vec3(floor.getX() + .5, floor.getY() + 8, floor.getZ() + .31);
                pending = task(() -> { player.teleportTo(player.serverLevel(), dropPosition.x, dropPosition.y, dropPosition.z, 180, stage == 2 ? 5 : 65); player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0; });
                return false;
            }
            ticks++;
            if (stage == 3 && ticks >= 10) mc.options.keyShift.setDown(true);
            return false;
        }
        void frame() throws Exception {
            if (finishing || !dropped || pending != null || ticks < (stage == 3 ? 20 : 12)) return;
            if (observation == null) {
                observation = new CompletableFuture<>(); var destination = observation;
                mc.getSingleplayerServer().execute(() -> {
                    ItemStack feet = player.getItemBySlot(EquipmentSlot.FEET);
                    var data = feet.get(BzDataComponents.CARPENTER_BEE_BOOTS_HANGING_DATA.get());
                    destination.complete(Map.of("position", player.position().toString(), "y", player.getY(), "velocityY", player.getDeltaMovement().y,
                            "nativeHangStatIncrease", hangStat() - nativeHangStatBefore, "hangStartTime", data == null ? -1 : data.hangStartTime(),
                            "equippedFeet", BuiltInRegistries.ITEM.getKey(feet.getItem()).toString(), "onGround", player.onGround(), "onClimbable", player.onClimbable(),
                            "passenger", player.isPassenger(), "shift", player.isShiftKeyDown()));
                }); return;
            }
            if (!observation.isDone()) return;
            var server = observation.join();
            int hang = ((Number) server.get("nativeHangStatIncrease")).intValue();
            double fall = dropPosition.y - ((Number) server.get("y")).doubleValue();
            if (stage == 0 && (hang != 0 || fall < 1)) failures.add("Unequipped control did not fall normally");
            if (stage > 0 && hang <= 0) failures.add("Equipped stage " + stage + " never entered native wall hanging");
            if ((stage == 1 || stage == 2) && (fall > .8 || fall < -.1)) failures.add("Equipped hanging did not retain native wall height");
            if (stage == 3 && (fall < .8 || !(Boolean) server.get("shift"))) failures.add("Native sneak did not release hanging");
            if ((Boolean) server.get("passenger") || mc.player.isPassenger()) failures.add("Ragdoll/mount replaced native hanging fixture");
            String name = switch (stage) { case 0 -> "unequipped_fall_control"; case 1 -> "native_hanging_firstperson"; case 2 -> "native_hanging_thirdperson"; default -> "native_sneak_release"; };
            String file = String.format("%02d-%s.png", stage + 1, name);
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(file)); }
            Map<String, Object> row = new LinkedHashMap<>(); row.put("stage", name); row.put("file", file); row.put("serverState", server);
            row.put("serverFallDistance", fall); row.put("clientFallDistance", dropPosition.y - mc.player.getY()); row.put("clientVelocityY", mc.player.getDeltaMovement().y);
            row.put("camera", mc.options.getCameraType().name()); row.put("cameraPosition", mc.gameRenderer.getMainCamera().getPosition().toString());
            row.put("clientFeet", BuiltInRegistries.ITEM.getKey(mc.player.getItemBySlot(EquipmentSlot.FEET).getItem()).toString()); captures.add(row);
            keys.keySet().forEach(key -> key.setDown(false)); write("RUNNING", "");
            if (++stage == 4) { finish(failures.isEmpty() ? "CAPTURED" : "FAIL", "Review native hanging screenshots and control"); return; }
            dropped = false; ticks = preparationRecoveries = 0; observation = null; resumeServerTick = serverTicks + 15;
        }
        void finish(String state, String message) { if (!finishing) { finishing = true; status = state; detail = message; keys.keySet().forEach(key -> key.setDown(false)); resumeServerTick = serverTicks + 15; } }
        void restoreServer() {
            if (player == null) return;
            ExpandedRagdollRecovery.release(player); player.stopRiding(); player.stopUsingItem();
            if (inventory != null) player.getInventory().load(inventory);
            var cap = AccessoriesCapability.get(player);
            accessories.forEach((name, saved) -> { var c = cap.getContainers().get(name); for (int i = 0; i < saved.normal.size(); i++) {
                c.getAccessories().setItem(i, saved.normal.get(i)); c.getCosmeticAccessories().setItem(i, saved.cosmetic.get(i)); c.renderOptions().set(i, saved.renders.get(i));
            } });
            blocks.forEach((pos, state) -> player.serverLevel().setBlock(pos, state, 2)); player.serverLevel().setDayTime(originalDayTime);
            if (gameType != null) player.setGameMode(gameType); player.getAbilities().flying = originalFlying; player.onUpdateAbilities();
            player.teleportTo(player.serverLevel(), originalPosition.x, originalPosition.y, originalPosition.z, originalYaw, originalPitch);
            player.setDeltaMovement(Vec3.ZERO); player.inventoryMenu.broadcastChanges();
            report.put("serverInventoryRestored", inventory == null || inventory.equals(player.getInventory().save(new ListTag())));
            report.put("blocksRestored", blocks.entrySet().stream().allMatch(e -> player.serverLevel().getBlockState(e.getKey()).equals(e.getValue())));
        }
        void write(String state, String message) throws Exception {
            report.put("status", state); report.put("detail", message);
            Files.writeString(output.resolve("hanging-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }
}
