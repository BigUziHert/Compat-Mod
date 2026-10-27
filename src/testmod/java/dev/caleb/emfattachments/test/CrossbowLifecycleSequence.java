package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import dev.caleb.emfattachments.config.CompatConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Real native crossbow loading/firing/cancellation, independent of combat swing poses. */
public final class CrossbowLifecycleSequence {
    private static Session active;
    private CrossbowLifecycleSequence() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CrossbowLifecycleSequence::tick);
        NeoForge.EVENT_BUS.addListener(CrossbowLifecycleSequence::pre);
        NeoForge.EVENT_BUS.addListener(CrossbowLifecycleSequence::frame);
    }

    private static void tick(ClientTickEvent.Post ignored) {
        var mc = Minecraft.getInstance();
        if (active != null) {
            try { active.tick(); } catch (Throwable error) { active.finish("FAIL", error.toString()); }
            return;
        }
        if (mc.player == null || mc.screen != null || CombatRenderCounters.active || !NativeWorldReadiness.ready()) return;
        Path trigger = mc.gameDirectory.toPath().resolve("emfattachments-crossbow-lifecycle.txt");
        if (!Files.isRegularFile(trigger)) return;
        String label = "crossbow-lifecycle";
        try {
            if (!mc.hasSingleplayerServer() || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local", "client")))
                throw new IllegalStateException("Disposable integrated client required");
            var args = Files.readString(trigger).trim().split("\\s+");
            label = args[0].replaceAll("[^a-zA-Z0-9._-]", "_");
            var options = Set.of(Arrays.copyOfRange(args, 1, args.length));
            if (!Set.of("quick", "crouch", "fpm-disabled", "crossbow-compat-off", "clamp-on").containsAll(options))
                throw new IllegalArgumentException("Unknown crossbow diagnostic option " + options);
            Files.delete(trigger);
            active = new Session(mc, label, options);
            active.prepare();
        } catch (Throwable error) {
            if (active != null) active.finish("FAIL", error.toString());
            else try {
                Path folder = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label);
                Files.createDirectories(folder);
                Files.writeString(folder.resolve("crossbow-lifecycle-report.json"), new GsonBuilder().setPrettyPrinting().create()
                        .toJson(Map.of("status", "FAIL", "detail", error.toString())));
            } catch (Exception ignoredError) {}
        }
    }

    private static void pre(RenderFrameEvent.Pre ignored) {
        if (active != null && !active.finishing) CombatRenderCounters.reset();
    }

    private static void frame(RenderFrameEvent.Post ignored) {
        if (active != null && !active.finishing) {
            try { active.frame(); } catch (Throwable error) { active.finish("FAIL", error.toString()); }
        }
    }

    private record View(InteractionHand hand, float pitch, boolean crouch) {}
    private enum Phase { PREPARE, CHARGE, LOAD_WAIT, LOADED, BACK, FRONT, RETURN, FIRE, IDLE, CANCEL_PREPARE, CANCEL_CHARGE, CANCEL_IDLE }

    private static final class Session {
        final Minecraft mc;
        final LocalPlayer player;
        final Path output;
        final List<View> views = new ArrayList<>();
        final List<Map<String, Object>> cases = new ArrayList<>(), captures = new ArrayList<>();
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<CompletableFuture<Void>> imageWrites = new ArrayList<>();
        final Map<KeyMapping, Boolean> keys = new LinkedHashMap<>();
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final Set<UUID> nativeProjectiles = new HashSet<>(); // Server thread only.
        final CameraType originalCamera;
        final boolean originalGui, originalFlying, fpmPresent, originalFpm, originalCrossbowCompatibility, originalClamp;
        final int originalClientSelected;
        final float yaw, pitch, bodyYaw, headYaw;
        final Vec3 clientPosition, clientMotion, origin;
        final String originalNea, originalBetterCombat;
        final Set<String> options;
        volatile Map<String, Object> observedServer = Map.of();
        volatile List<ItemStack> inventory;
        volatile Vec3 serverPosition, serverMotion;
        volatile int originalServerSelected, restoreStartTick;
        volatile boolean originalServerFlying;
        volatile Map<String, Object> restoration = Map.of();
        CompletableFuture<Void> operation = CompletableFuture.completedFuture(null), observation = CompletableFuture.completedFuture(null);
        int viewIndex, ticks, elapsed, loadedTicks, projectileCountBefore;
        boolean finishing, partialCaptured, lateCaptured, restoreVerificationStarted;
        String finalStatus, finalDetail;
        Phase phase = Phase.PREPARE;
        Map<String, Object> current;

        Session(Minecraft mc, String label, Set<String> options) throws Exception {
            this.mc = mc; this.player = mc.player; this.options = options;
            if (player.isPassenger() || player.isUsingItem() || !player.isAlive() || ExpandedCombatActions.attackActive(player))
                throw new IllegalStateException("Start alive, recovered, idle and unmounted");
            fpmPresent = ModList.get().isLoaded("firstperson"); originalFpm = fpmPresent && Fpm.enabled();
            originalCrossbowCompatibility = CompatConfig.firstPersonCrossbowPose();
            originalClamp = dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.clampCrossbowAnimations;
            if (options.contains("fpm-disabled") && !fpmPresent) throw new IllegalArgumentException("FPM-disabled requires FirstPerson installed");
            originalCamera = mc.options.getCameraType(); originalGui = mc.options.hideGui;
            originalFlying = player.getAbilities().flying; originalClientSelected = player.getInventory().selected;
            yaw = player.getYRot(); pitch = player.getXRot(); bodyYaw = player.yBodyRot; headYaw = player.yHeadRot;
            clientPosition = player.position(); clientMotion = player.getDeltaMovement();
            origin = new Vec3(352.5, mc.level.getMinBuildHeight() + 13, 352.5);
            originalNea = new GsonBuilder().create().toJson(dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config);
            originalBetterCombat = new GsonBuilder().create().toJson(net.bettercombat.client.BetterCombatClientMod.config);
            for (var key : List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                    mc.options.keyJump, mc.options.keySprint, mc.options.keyShift, mc.options.keyAttack, mc.options.keyUse)) keys.put(key, key.isDown());
            if (options.contains("quick")) {
                views.add(new View(InteractionHand.MAIN_HAND, 0, false));
                views.add(new View(InteractionHand.MAIN_HAND, 90, false));
                views.add(new View(InteractionHand.OFF_HAND, 55, true));
            } else {
                for (var hand : InteractionHand.values()) for (float angle : new float[]{0, 55, 90}) {
                    views.add(new View(hand, angle, false));
                    if (options.contains("crouch")) views.add(new View(hand, angle, true));
                }
            }
            output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label); Files.createDirectories(output);
            report.put("label", label); report.put("options", options); report.put("firstPersonInstalled", fpmPresent);
            report.put("firstPersonOriginallyEnabled", originalFpm); report.put("plannedContexts", views.size());
            report.put("originalCrossbowCompatibility", originalCrossbowCompatibility);
            report.put("originalClampCrossbowAnimations", originalClamp);
            report.put("cases", cases); report.put("captures", captures); report.put("status", "PREPARING");
            report.put("preservesNativeConfigurations", "Better Combat is untouched. Optional FPM API-off, compatibility opt-out and native NEA clamp-on are in-memory only and restored with native animation refresh.");
            report.put("diagnosticScope", "Real partial/late/full charge, native release-to-load, loaded hold, F5 views, native projectile fire and partial-charge cancellation. Numerical pose differences diagnose overwrite; screenshots require separate visual review.");
            write();
        }

        ServerPlayer serverPlayer() { return Objects.requireNonNull(mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID())); }
        View view() { return views.get(viewIndex); }
        void server(Runnable work) {
            var pending = new CompletableFuture<Void>(); operation = pending;
            mc.getSingleplayerServer().execute(() -> {
                try { work.run(); pending.complete(null); }
                catch (Throwable error) { pending.completeExceptionally(error); }
            });
        }

        void prepare() {
            keys.keySet().forEach(key -> key.setDown(false)); CombatRenderCounters.active = true;
            mc.options.hideGui = false; mc.options.setCameraType(CameraType.FIRST_PERSON); player.getAbilities().flying = false;
            if (options.contains("fpm-disabled")) Fpm.enabled(false);
            if (options.contains("crossbow-compat-off")) CompatConfig.FIRST_PERSON_CROSSBOW_POSE.set(false);
            if (options.contains("clamp-on")) {
                dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.clampCrossbowAnimations = true;
                dev.tr7zw.notenoughanimations.api.NotEnoughAnimationsApi.refreshEnabledAnimations();
            }
            server(() -> {
                var sp = serverPlayer(); inventory = new ArrayList<>();
                for (int i = 0; i < sp.getInventory().getContainerSize(); i++) inventory.add(sp.getInventory().getItem(i).copy());
                originalServerSelected = sp.getInventory().selected; serverPosition = sp.position(); serverMotion = sp.getDeltaMovement();
                originalServerFlying = sp.getAbilities().flying;
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -1; y <= 5; y++) {
                    var pos = BlockPos.containing(origin).offset(x, y, z); blocks.put(pos, sp.level().getBlockState(pos));
                    sp.level().setBlock(pos, y == -1 ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
                ExpandedRagdollRecovery.release(sp); ExpandedReactionTransition.begin(sp);
                sp.getAbilities().flying = false; sp.onUpdateAbilities();
                sp.teleportTo(origin.x, origin.y, origin.z); sp.setDeltaMovement(Vec3.ZERO); sp.fallDistance = 0;
                ExpandedReactionTransition.displaced(sp);
            });
            beginView();
        }

        void beginView() {
            current = new LinkedHashMap<>(); cases.add(current);
            current.put("hand", view().hand().name()); current.put("pitch", view().pitch()); current.put("crouch", view().crouch());
            current.put("status", "PREPARING"); current.put("posePreservation", "DIAGNOSTIC_NOT_ASSUMED");
            next(Phase.PREPARE);
        }

        void equipFresh() {
            // Canonical slot plus native packets avoids an existing client/server selection mismatch.
            player.getInventory().selected = 0; player.connection.send(new ServerboundSetCarriedItemPacket(0));
            mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(player);
            var hand = view().hand();
            server(() -> {
                var sp = serverPlayer(); sp.stopUsingItem(); sp.getInventory().selected = 0;
                sp.connection.send(new ClientboundSetCarriedItemPacket(0));
                sp.getInventory().setItem(35, new ItemStack(Items.ARROW, 64));
                sp.setItemInHand(InteractionHand.MAIN_HAND, hand == InteractionHand.MAIN_HAND ? Items.CROSSBOW.getDefaultInstance() : ItemStack.EMPTY);
                sp.setItemInHand(InteractionHand.OFF_HAND, hand == InteractionHand.OFF_HAND ? Items.CROSSBOW.getDefaultInstance() : ItemStack.EMPTY);
                sp.inventoryMenu.broadcastChanges();
            });
        }

        void observeServer() {
            if (!observation.isDone()) return;
            if (observation.isCompletedExceptionally()) observation.join();
            var pending = new CompletableFuture<Void>(); observation = pending;
            var hand = view().hand();
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var sp = serverPlayer();
                    for (var entity : sp.serverLevel().getAllEntities())
                        if (entity instanceof Projectile projectile && projectile.getOwner() == sp && entity.distanceToSqr(origin) < 16384)
                            nativeProjectiles.add(entity.getUUID());
                    observedServer = Map.of("usingItem", sp.isUsingItem(), "useElapsed", sp.getTicksUsingItem(),
                            "charged", CrossbowItem.isCharged(sp.getItemInHand(hand)), "crossbow", sp.getItemInHand(hand).is(Items.CROSSBOW),
                            "onGround", sp.onGround(), "crouch", sp.isShiftKeyDown(), "selected", sp.getInventory().selected,
                            "projectiles", nativeProjectiles.size(), "serverTick", mc.getSingleplayerServer().getTickCount());
                    pending.complete(null);
                } catch (Throwable error) { pending.completeExceptionally(error); }
            });
        }

        void tick() {
            ticks++; elapsed++;
            if (operation.isCompletedExceptionally()) operation.join();
            if (finishing) { finishTick(); return; }
            if (mc.player != player || mc.level == null || mc.screen != null || !player.isAlive()) throw new IllegalStateException("World, player or UI changed");
            observeServer();
            mc.options.hideGui = false; mc.options.keyShift.setDown(view().crouch());
            player.setYRot(180); player.yRotO = 180; player.yBodyRot = player.yBodyRotO = 180; player.yHeadRot = player.yHeadRotO = 180;
            player.setXRot(view().pitch()); player.xRotO = view().pitch();
            if (ticks > 220) throw new IllegalStateException("Crossbow phase timeout: " + phase + ", server=" + observedServer);
            if (phase == Phase.PREPARE && operation.isDone() && !Boolean.TRUE.equals(current.get("equipped"))) { equipFresh(); current.put("equipped", true); }
            if (phase == Phase.PREPARE && readyUncharged() && ticks >= 35 && NativeWorldReadiness.ready()) {
                current.put("nativeReadiness", NativeWorldReadiness.evidence()); nativeUse(); next(Phase.CHARGE);
            } else if (phase == Phase.LOAD_WAIT) {
                if (CrossbowItem.isCharged(player.getItemInHand(view().hand())) && Boolean.TRUE.equals(observedServer.get("charged"))) {
                    if (!partialCaptured || !lateCaptured) throw new IllegalStateException("Charging capture window missed");
                    current.put("nativeLoadedOnBothSides", true); mc.options.keyUse.setDown(false); next(Phase.LOADED);
                }
            } else if (phase == Phase.CANCEL_PREPARE && readyUncharged() && ticks >= 15 && NativeWorldReadiness.ready()) {
                projectileCountBefore = ((Number) observedServer.getOrDefault("projectiles", 0)).intValue();
                nativeUse(); next(Phase.CANCEL_CHARGE);
            }
        }

        boolean readyUncharged() {
            return operation.isDone() && player.getInventory().selected == 0 && player.getItemInHand(view().hand()).is(Items.CROSSBOW)
                    && !CrossbowItem.isCharged(player.getItemInHand(view().hand())) && !player.isUsingItem()
                    && Boolean.TRUE.equals(observedServer.get("crossbow")) && Boolean.FALSE.equals(observedServer.get("charged"))
                    && Boolean.TRUE.equals(observedServer.get("onGround")) && Objects.equals(observedServer.get("selected"), 0)
                    && Objects.equals(observedServer.get("crouch"), view().crouch());
        }

        void nativeUse() {
            if (!mc.gameMode.useItem(player, view().hand()).consumesAction()) throw new IllegalStateException("Native crossbow use rejected");
            mc.options.keyUse.setDown(true);
        }
        void next(Phase value) { phase = value; ticks = 0; loadedTicks = 0; current.put("phase", value.name()); }

        void frame() throws Exception {
            switch (phase) {
                case CHARGE -> {
                    int used = player.getTicksUsingItem();
                    int duration = CrossbowItem.getChargeDuration(player.getItemInHand(view().hand()), player);
                    if (player.isUsingItem() && !partialCaptured && used >= 5 && used <= 12) {
                        capture("charge_partial"); partialCaptured = true;
                    }
                    if (player.isUsingItem() && !lateCaptured && used >= duration - 7 && used < duration) {
                        capture("charge_late"); lateCaptured = true;
                    }
                    // Exact installed CrossbowItem.useOnRelease=true; loading happens
                    // in releaseUsing only after both clocks have reached full charge.
                    if (partialCaptured && lateCaptured && player.isUsingItem() && used >= duration
                            && ((Number) observedServer.getOrDefault("useElapsed", 0)).intValue() >= duration) {
                        capture("charge_full"); current.put("nativeFullChargeRelease", observedServer);
                        mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(player); next(Phase.LOAD_WAIT);
                    }
                }
                case LOADED -> { if (ticks >= 12) { capture("loaded_first_person"); mc.options.setCameraType(CameraType.THIRD_PERSON_BACK); next(Phase.BACK); } }
                case BACK -> { if (ticks >= 12) { capture("loaded_f5_back"); mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT); next(Phase.FRONT); } }
                case FRONT -> { if (ticks >= 12) { capture("loaded_f5_front"); mc.options.setCameraType(CameraType.FIRST_PERSON); next(Phase.RETURN); } }
                case RETURN -> { if (ticks >= 12) {
                    capture("loaded_return_first_person"); projectileCountBefore = ((Number) observedServer.get("projectiles")).intValue();
                    if (!mc.gameMode.useItem(player, view().hand()).consumesAction()) throw new IllegalStateException("Native loaded fire rejected");
                    mc.options.keyUse.setDown(false); next(Phase.FIRE);
                } }
                case FIRE -> { if (ticks >= 2 && !CrossbowItem.isCharged(player.getItemInHand(view().hand()))
                        && Boolean.FALSE.equals(observedServer.get("charged")) && ((Number) observedServer.get("projectiles")).intValue() > projectileCountBefore) {
                    capture("native_fire"); current.put("nativeProjectileSpawnVerified", true); next(Phase.IDLE);
                } }
                case IDLE -> { if (ticks >= 20) { capture("after_fire_idle"); equipFresh(); next(Phase.CANCEL_PREPARE); } }
                case CANCEL_CHARGE -> { if (player.isUsingItem() && player.getTicksUsingItem() >= 5 && player.getTicksUsingItem() <= 12) {
                    capture("cancel_partial"); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(player); next(Phase.CANCEL_IDLE);
                } }
                case CANCEL_IDLE -> { if (ticks >= 20 && !player.isUsingItem() && Boolean.FALSE.equals(observedServer.get("usingItem"))) {
                    if (CrossbowItem.isCharged(player.getItemInHand(view().hand())) || Boolean.TRUE.equals(observedServer.get("charged"))
                            || ((Number) observedServer.get("projectiles")).intValue() != projectileCountBefore)
                        throw new AssertionError("Partial cancellation unexpectedly loaded or fired");
                    capture("cancel_recovered_idle"); current.put("nativeCancellationVerified", true); current.put("status", "CAPTURED"); write();
                    if (++viewIndex == views.size()) finish("CAPTURED", "Native charge/load/fire/cancel contexts captured; inspect pose traces and images.");
                    else { partialCaptured = lateCaptured = false; beginView(); }
                } }
                default -> {}
            }
        }

        void capture(String name) throws Exception {
            var row = new LinkedHashMap<String, Object>(CombatRenderCounters.snapshot());
            row.put("name", name); row.put("case", viewIndex + 1); row.put("hand", view().hand().name());
            row.put("pitch", view().pitch()); row.put("crouch", view().crouch()); row.put("camera", mc.options.getCameraType().name());
            row.put("usingItem", player.isUsingItem()); row.put("useElapsed", player.getTicksUsingItem());
            row.put("charged", CrossbowItem.isCharged(player.getItemInHand(view().hand()))); row.put("server", observedServer);
            row.put("selectedSlot", player.getInventory().selected); row.put("actualCameraPosition", mc.gameRenderer.getMainCamera().getPosition().toString());
            var nativePose = NeaProjectilePoseProbe.snapshot(player.getUUID());
            row.put("nativePose", nativePose);
            boolean nativeClamp = dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.clampCrossbowAnimations;
            row.put("nativeClampEnabled", nativeClamp);
            if (options.contains("clamp-on") && (name.startsWith("charge_") || name.startsWith("loaded_") || name.equals("cancel_partial"))) {
                Object selected = nativePose.get("selected");
                if (!(selected instanceof Map<?, ?> parts)) throw new AssertionError("Missing actual native animation selection");
                for (String side : List.of("LEFT_ARM", "RIGHT_ARM")) {
                    Object value = parts.get(side);
                    if (!(value instanceof Map<?, ?> winner) || !Objects.equals(winner.get("animation"),
                            "dev.tr7zw.notenoughanimations.animations.hands.ClampCrossbowAnimations"))
                        throw new AssertionError("Native clamp selection missing for " + side + ": " + value);
                }
                row.put("nativeClampSelectionVerified", true);
            }
            row.put("firstPersonEnabled", fpmPresent && Fpm.enabled());
            row.put("crossbowCompatibilityEnabled", CompatConfig.firstPersonCrossbowPose());
            row.put("currentMainArm", player.getMainArm().name());
            int held = CombatRenderCounters.bodyItems + CombatRenderCounters.vanillaItems + CombatRenderCounters.otherItems;
            if (held != 1 || CombatRenderCounters.bodyPasses > 1) throw new AssertionError("Expected exactly one held crossbow draw: " + row);
            String file = String.format(Locale.ROOT, "%03d-%02d-%s.png", captures.size() + 1, viewIndex + 1, name);
            var pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            imageWrites.add(CompletableFuture.runAsync(() -> {
                try (pixels) { pixels.writeToFile(output.resolve(file)); }
                catch (Exception error) { throw new RuntimeException(error); }
            }));
            row.put("screenshot", file); captures.add(row);
        }

        void finish(String status, String detail) {
            if (finishing) { report.put("restoreFailure", detail); report.put("status", "RESTORE_FAILURE"); write(); active = null; return; }
            finishing = true; finalStatus = status; finalDetail = detail; ticks = 0; CombatRenderCounters.active = false;
            mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(player);
            if (fpmPresent) Fpm.enabled(originalFpm);
            CompatConfig.FIRST_PERSON_CROSSBOW_POSE.set(originalCrossbowCompatibility);
            dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.clampCrossbowAnimations = originalClamp;
            dev.tr7zw.notenoughanimations.api.NotEnoughAnimationsApi.refreshEnabledAnimations();
            server(() -> {
                var sp = serverPlayer(); ExpandedRagdollRecovery.release(sp); ExpandedReactionTransition.begin(sp); sp.stopUsingItem();
                if (inventory != null) for (int i = 0; i < inventory.size(); i++) sp.getInventory().setItem(i, inventory.get(i).copy());
                sp.getInventory().selected = originalServerSelected; sp.connection.send(new ClientboundSetCarriedItemPacket(originalServerSelected));
                for (var entity : sp.serverLevel().getAllEntities())
                    if (entity instanceof Projectile projectile && projectile.getOwner() == sp && nativeProjectiles.contains(entity.getUUID())) entity.discard();
                blocks.forEach((pos, state) -> sp.level().setBlock(pos, state, 3));
                if (serverPosition != null) sp.teleportTo(serverPosition.x, serverPosition.y, serverPosition.z);
                sp.setDeltaMovement(Vec3.ZERO); sp.fallDistance = 0; ExpandedReactionTransition.displaced(sp);
                sp.getAbilities().flying = originalServerFlying; sp.onUpdateAbilities(); sp.inventoryMenu.broadcastChanges();
                restoreStartTick = mc.getSingleplayerServer().getTickCount();
            });
        }

        void finishTick() {
            if (!operation.isDone()) return;
            if (ticks > 240) throw new IllegalStateException("Restoration timeout");
            if (mc.getSingleplayerServer().getTickCount() - restoreStartTick < 35 || ticks < 35) return;
            for (var task : imageWrites) { if (!task.isDone()) return; task.join(); }
            if (!restoreVerificationStarted) {
                restoreVerificationStarted = true;
                server(() -> {
                    var sp = serverPlayer();
                    boolean same = inventory != null;
                    if (same) for (int i = 0; i < inventory.size(); i++) {
                        var expected = inventory.get(i); var actual = sp.getInventory().getItem(i);
                        same &= ItemStack.isSameItemSameComponents(expected, actual) && expected.getCount() == actual.getCount();
                    }
                    restoration = Map.of("serverInventoryRestored", same,
                            "serverSelectedRestored", sp.getInventory().selected == originalServerSelected,
                            "nativeServerSeatReleased", !sp.isPassenger() && !ExpandedRagdollRecovery.active(sp),
                            "nativePendingPoseDrained", !ExpandedRagdollRecovery.pending(sp));
                });
                return;
            }
            if (restoration.isEmpty()) return;
            if (restoration.values().stream().anyMatch(value -> !Boolean.TRUE.equals(value)))
                throw new IllegalStateException("Native restoration verification failed: " + restoration);
            player.getInventory().selected = originalServerSelected; player.connection.send(new ServerboundSetCarriedItemPacket(originalServerSelected));
            player.setYRot(yaw); player.yRotO = yaw; player.setXRot(pitch); player.xRotO = pitch;
            player.yBodyRot = player.yBodyRotO = bodyYaw; player.yHeadRot = player.yHeadRotO = headYaw;
            player.getAbilities().flying = originalFlying; player.setDeltaMovement(clientMotion);
            mc.options.setCameraType(originalCamera); mc.options.hideGui = originalGui; keys.forEach(KeyMapping::setDown);
            report.put("originalClientSelected", originalClientSelected); report.put("originalServerSelected", originalServerSelected);
            report.put("selectionReconciledToOriginalServer", player.getInventory().selected == originalServerSelected);
            report.put("clientOriginalSelectionRestored", originalClientSelected == originalServerSelected);
            report.put("nativeClientSeatReleased", !player.isPassenger()); report.put("clientSettingsRestored", true);
            report.putAll(restoration); report.put("arenaRestored", true);
            report.put("neaConfigurationPreserved", originalNea.equals(new GsonBuilder().create().toJson(dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config)));
            report.put("betterCombatConfigurationPreserved", originalBetterCombat.equals(new GsonBuilder().create().toJson(net.bettercombat.client.BetterCombatClientMod.config)));
            report.put("firstPersonSettingRestored", !fpmPresent || Fpm.enabled() == originalFpm);
            report.put("crossbowCompatibilitySettingRestored", CompatConfig.firstPersonCrossbowPose() == originalCrossbowCompatibility);
            report.put("nativeClampSettingRestored", dev.tr7zw.notenoughanimations.versionless.NEABaseMod.config.clampCrossbowAnimations == originalClamp);
            report.put("status", finalStatus); report.put("detail", finalDetail); write(); active = null;
        }

        void write() {
            try { Files.writeString(output.resolve("crossbow-lifecycle-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch (Exception error) { throw new RuntimeException(error); }
        }
    }

    private static final class Fpm {
        static boolean enabled() { return dev.tr7zw.firstperson.api.FirstPersonAPI.isEnabled(); }
        static void enabled(boolean value) { dev.tr7zw.firstperson.api.FirstPersonAPI.setEnabled(value); }
    }
}
