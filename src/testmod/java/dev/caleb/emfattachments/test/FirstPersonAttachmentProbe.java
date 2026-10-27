package dev.caleb.emfattachments.test;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import dev.caleb.emfattachments.config.CompatConfig;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.client.AccessoriesRenderLayer;
import io.wispforest.accessories.menu.ArmorSlotTypes;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** Exercises the transformed installed layer, rather than only the policy predicate. */
public final class FirstPersonAttachmentProbe {
    private FirstPersonAttachmentProbe() {}

    private record ItemCase(String id, boolean hidden) {}
    private static final List<ItemCase> ITEMS = List.of(
            new ItemCase("aeronautics:aviators_goggles", true),
            new ItemCase("create:goggles", true),
            new ItemCase("the_bumblezone:flower_headwear", true),
            new ItemCase("artifacts:lucky_scarf", true),
            new ItemCase("artifacts:scarf_of_invisibility", true),
            new ItemCase("deep_aether:floaty_scarf", true),
            new ItemCase("artifacts:fire_gauntlet", false),
            new ItemCase("sophisticatedbackpacks:backpack", false));
    private record Mode(String name, boolean local, boolean body, boolean extraction,
                        boolean enabled, CameraType camera, boolean foreignCamera) {}
    private static final List<Mode> MODES = List.of(
            new Mode("local-normal", true, false, false, true, CameraType.FIRST_PERSON, false),
            new Mode("local-body", true, true, false, true, CameraType.FIRST_PERSON, false),
            new Mode("remote-normal", false, false, false, true, CameraType.FIRST_PERSON, false),
            new Mode("remote-during-local-body", false, true, false, true, CameraType.FIRST_PERSON, false),
            new Mode("camera-extraction", true, false, true, true, CameraType.FIRST_PERSON, false),
            new Mode("body-and-camera-extraction", true, true, true, true, CameraType.FIRST_PERSON, false),
            new Mode("third-person", true, true, false, true, CameraType.THIRD_PERSON_BACK, false),
            new Mode("different-camera-entity", true, true, false, true, CameraType.FIRST_PERSON, true),
            new Mode("firstperson-disabled", true, true, false, false, CameraType.FIRST_PERSON, false),
            new Mode("shadow-pass", true, true, false, true, CameraType.FIRST_PERSON, false),
            new Mode("local-body-head-option-off", true, true, false, true, CameraType.FIRST_PERSON, false),
            new Mode("local-body-scarf-option-off", true, true, false, true, CameraType.FIRST_PERSON, false),
            new Mode("local-body-allowlist", true, true, false, true, CameraType.FIRST_PERSON, false),
            new Mode("local-body-custom-hidden-item", true, true, false, true, CameraType.FIRST_PERSON, false));

    public static Map<String, Object> run() {
        var mc = Minecraft.getInstance();
        var core = FirstPersonModelCore.instance;
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        report.put("cases", rows);
        report.put("failures", failures);
        boolean raw = core.isRenderingPlayerRaw(), extract = FirstPersonModelCore.cameraEntityExtract;
        boolean enabled = FirstPersonAPI.isEnabled();
        CameraType camera = mc.options.getCameraType();
        Entity cameraEntity = mc.getCameraEntity();
        boolean iris = net.neoforged.fml.ModList.get().isLoaded("iris");
        boolean originalShadow = iris && net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE;
        ConfigState config = new ConfigState();
        AttachmentTestMod.TestResult current = AttachmentTestMod.current;
        var remote = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), "ScopeProbe"));
        remote.setPos(mc.player.position());
        EntityState localState = new EntityState(mc.player);
        EntityState remoteState = new EntityState(remote);
        Map<PlayerRenderer, Map<ModelPart, PartState>> savedModels = new IdentityHashMap<>();
        try {
            require(mc.isSameThread(), "Probe must run on the client thread");
            require(!net.neoforged.fml.ModList.get().isLoaded("fpm_x_compat"), "Rejected vendor addon is still loaded");
            AttachmentTestMod.current = null;
            setScarfWrapped(mc.player, true); setScarfWrapped(remote, true);
            for (ItemCase itemCase : ITEMS) {
                ItemStack stack = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemCase.id)).getDefaultInstance();
                require(!stack.isEmpty(), "Missing probe item " + itemCase.id);
                List<String> slots = AccessoriesAPI.getStackSlotTypes(mc.player, stack).stream()
                        .map(slot -> slot.name()).filter(name -> !ArmorSlotTypes.isArmorType(name))
                        .sorted().toList();
                require(!slots.isEmpty(), "No real slot for " + itemCase.id);
                for (String slot : slots) {
                    for (boolean cosmetic : new boolean[]{false, true}) {
                        localState.clear(); remoteState.clear();
                        equip(mc.player, slot, stack, cosmetic); equip(remote, slot, stack, cosmetic);
                        EntityState expectedLocal = new EntityState(mc.player);
                        EntityState expectedRemote = new EntityState(remote);
                        Map<String, Integer> baselines = new LinkedHashMap<>();
                        Map<String, Integer> nativeBaselines = new LinkedHashMap<>();
                        for (Mode mode : MODES) {
                            if (mode.name.equals("shadow-pass") && !iris) continue;
                            var row = new LinkedHashMap<String, Object>();
                            rows.add(row);
                            row.put("item", itemCase.id); row.put("slot", slot); row.put("cosmetic", cosmetic); row.put("mode", mode.name);
                            AbstractClientPlayer entity = mode.local ? mc.player : remote;
                            PlayerRenderer renderer = (PlayerRenderer) (Object) mc.getEntityRenderDispatcher().getRenderer(entity);
                            savedModels.computeIfAbsent(renderer, ignored -> snapshot(renderer.getModel()));
                            Map<ModelPart, PartState> before = snapshot(renderer.getModel());
                            try {
                                core.setRenderingPlayer(mode.body);
                                FirstPersonModelCore.cameraEntityExtract = mode.extraction;
                                FirstPersonAPI.setEnabled(mode.enabled);
                                mc.options.setCameraType(mode.camera);
                                mc.setCameraEntity(mode.foreignCamera ? remote : mc.player);
                                if (iris) net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE = mode.name.equals("shadow-pass");
                                configure(mode.name, itemCase.id);
                                Counter vertices = new Counter();
                                MultiBufferSource buffers = type -> vertices;
                                var layer = new AccessoriesRenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>(renderer);
                                // Two consecutive draws catch temporary render-flag/inventory mutations.
                                for (int repeat = 0; repeat < 2; repeat++) {
                                    try (var emf = new EmfProbeContext(entity)) {
                                    layer.render(new PoseStack(), buffers, 0xF000F0, entity, 0, 0, 0, entity.tickCount, 0, 0);
                                    }
                                    expectedLocal.assertSame(mc.player); expectedRemote.assertSame(remote);
                                    require(sameModel(before), "Layer changed the parent model state");
                                }
                                boolean scarf = itemCase.id.equals("artifacts:lucky_scarf") || itemCase.id.equals("artifacts:scarf_of_invisibility") || itemCase.id.equals("deep_aether:floaty_scarf");
                                boolean hidden = switch (mode.name) {
                                    case "local-body" -> itemCase.hidden;
                                    case "local-body-head-option-off" -> scarf;
                                    case "local-body-scarf-option-off" -> itemCase.hidden && !scarf;
                                    case "local-body-custom-hidden-item" -> true;
                                    default -> false;
                                };
                                row.put("vertices", vertices.vertices); row.put("expectedHidden", hidden);
                                require(vertices.invalid == 0, "Non-finite vertices");
                                require(hidden ? vertices.vertices == 0 : vertices.vertices > 0,
                                        "Expected " + (hidden ? "hidden" : "visible") + " but emitted " + vertices.vertices + " vertices");
                                String entityKey = mode.local ? "local" : "remote";
                                if (mode.name.endsWith("-normal")) baselines.put(entityKey, vertices.vertices);
                                if (!hidden && baselines.containsKey(entityKey)) require(vertices.vertices == baselines.get(entityKey), "Visible geometry differs from same-entity normal baseline");
                                var nativeRenderer = top.theillusivec4.curios.api.client.CuriosRendererRegistry.getRenderer(stack.getItem());
                                if (nativeRenderer.isPresent()) {
                                    Counter nativeVertices = new Counter();
                                    // The installed bridge replaces normal Curios layer routing. Invoke
                                    // the transformed native callback to test its own hook separately.
                                    var nativeLayer = new top.theillusivec4.curios.client.render.CuriosLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>(renderer);
                                    String nativeSlot = slot.equals("hat") ? "head" : slot;
                                    var context = new top.theillusivec4.curios.api.SlotContext(nativeSlot, entity, 0, cosmetic, !cosmetic);
                                    for (int repeat = 0; repeat < 2; repeat++) {
                                        try (var emf = new EmfProbeContext(entity)) {
                                            NativeCuriosCallback.invoke(nativeLayer, stack, context, nativeVertices, nativeRenderer.get(), entity.tickCount);
                                        }
                                        expectedLocal.assertSame(mc.player); expectedRemote.assertSame(remote);
                                        require(sameModel(before), "Native Curios callback changed parent model state");
                                    }
                                    row.put("nativeCuriosHookVertices", nativeVertices.vertices);
                                    row.put("nativeCuriosHookSlot", nativeSlot);
                                    row.put("nativeCuriosContextVisible", !cosmetic);
                                    require(nativeVertices.invalid == 0, "Native Curios callback emitted non-finite vertices");
                                    require(hidden ? nativeVertices.vertices == 0 : nativeVertices.vertices > 0, "Native Curios callback had incorrect visibility");
                                    if (mode.name.endsWith("-normal")) nativeBaselines.put(entityKey, nativeVertices.vertices);
                                    if (!hidden && nativeBaselines.containsKey(entityKey)) require(nativeVertices.vertices == nativeBaselines.get(entityKey), "Native Curios visible geometry differs from baseline");
                                    row.put("nativeCuriosHookStatus", "PASS");
                                }
                                row.put("inventoryUnchanged", true); row.put("modelUnchanged", true); row.put("status", "PASS");
                            } catch (Throwable failure) {
                                row.put("status", "FAIL"); row.put("error", failure.toString());
                                failures.add(itemCase.id + "/" + slot + "/" + cosmetic + "/" + mode.name + ": " + failure);
                            } finally { restoreModel(before); }
                        }
                    }
                }
            }
            report.put("status", failures.isEmpty() ? "PASS" : "FAIL");
        } catch (Throwable failure) {
            failures.add(failure.toString()); report.put("status", "FAIL");
        } finally {
            core.setRenderingPlayer(false); FirstPersonModelCore.cameraEntityExtract = false;
            localState.restore(mc.player); remoteState.restore(remote);
            savedModels.values().forEach(FirstPersonAttachmentProbe::restoreModel);
            config.restore();
            if (iris) net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE = originalShadow;
            mc.options.setCameraType(camera); mc.setCameraEntity(cameraEntity);
            FirstPersonAPI.setEnabled(enabled); core.setRenderingPlayer(raw);
            FirstPersonModelCore.cameraEntityExtract = extract;
            AttachmentTestMod.current = current;
            localState.assertSame(mc.player); remoteState.assertSame(remote);
            require(config.same(), "Probe failed to restore in-memory configuration");
            report.put("fixtureStateRestored", true);
        }
        report.put("caseCount", rows.size());
        report.put("repeatsPerCase", 2);
        report.put("nativeCuriosHookCases", rows.stream().filter(row -> row.containsKey("nativeCuriosHookStatus")).count());
        report.put("vendorAbsent", !net.neoforged.fml.ModList.get().isLoaded("fpm_x_compat"));
        return report;
    }

    private static void configure(String mode, String item) {
        CompatConfig.HIDE_HEAD_ACCESSORIES.set(!mode.equals("local-body-head-option-off") && !mode.equals("local-body-custom-hidden-item"));
        CompatConfig.HIDE_SCARVES.set(!mode.equals("local-body-scarf-option-off") && !mode.equals("local-body-custom-hidden-item"));
        CompatConfig.HIDDEN_SLOTS.set(List.of("head", "hat", "face", "mask", "goggle", "goggles"));
        CompatConfig.HIDDEN_ITEMS.set(mode.equals("local-body-custom-hidden-item") ? List.of(item) : List.of());
        CompatConfig.VISIBLE_ITEMS.set(mode.equals("local-body-allowlist") ? List.of(item) : List.of());
    }
    private static final class NativeCuriosCallback {
        private static final java.lang.reflect.Method DRAW;
        static {
            try {
                DRAW = top.theillusivec4.curios.client.render.CuriosLayer.class.getDeclaredMethod("lambda$render$0",
                        ItemStack.class, top.theillusivec4.curios.api.SlotContext.class, PoseStack.class,
                        MultiBufferSource.class, int.class, float.class, float.class, float.class,
                        float.class, float.class, float.class, top.theillusivec4.curios.api.client.ICurioRenderer.class);
                DRAW.setAccessible(true);
            } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
        }
        static void invoke(Object layer, ItemStack stack, top.theillusivec4.curios.api.SlotContext context,
                Counter vertices, top.theillusivec4.curios.api.client.ICurioRenderer renderer, float age) throws Throwable {
            try {
                MultiBufferSource buffers = type -> vertices;
                DRAW.invoke(layer, stack, context, new PoseStack(), buffers, 0xF000F0, 0F, 0F, 0F, age, 0F, 0F, renderer);
            } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
        }
    }
    private static final class ConfigState {
        final boolean heads = CompatConfig.hideHeadAccessories(), scarves = CompatConfig.hideScarves();
        final List<? extends String> slots = List.copyOf(CompatConfig.hiddenSlots()), hidden = List.copyOf(CompatConfig.hiddenItems()), visible = List.copyOf(CompatConfig.visibleItems());
        void restore() {
            // ConfigValue.set is explicitly in-memory; no save() is called by this probe.
            CompatConfig.HIDE_HEAD_ACCESSORIES.set(heads); CompatConfig.HIDE_SCARVES.set(scarves);
            CompatConfig.HIDDEN_SLOTS.set(slots); CompatConfig.HIDDEN_ITEMS.set(hidden); CompatConfig.VISIBLE_ITEMS.set(visible);
        }
        boolean same() { return CompatConfig.hideHeadAccessories() == heads && CompatConfig.hideScarves() == scarves && slots.equals(CompatConfig.hiddenSlots()) && hidden.equals(CompatConfig.hiddenItems()) && visible.equals(CompatConfig.visibleItems()); }
    }

    private static void equip(AbstractClientPlayer player, String slot, ItemStack stack, boolean cosmetic) {
        var container = AccessoriesCapability.get(player).getContainers().get(slot);
        require(container != null && container.getSize() > 0, "Missing container " + slot);
        (cosmetic ? container.getCosmeticAccessories() : container.getAccessories()).setItem(0, stack.copy());
        if (!container.renderOptions().isEmpty()) container.renderOptions().set(0, true);
    }

    private record ContainerState(List<ItemStack> normal, List<ItemStack> cosmetic, List<Boolean> render) {}
    private static final class EntityState {
        private final AbstractClientPlayer owner;
        private final List<ItemStack> items, armor, offhand;
        private final boolean scarfWrapped;
        private final Map<String, ContainerState> containers = new LinkedHashMap<>();
        EntityState(AbstractClientPlayer player) {
            owner = player;
            scarfWrapped = isScarfWrapped(player);
            items = copies(player.getInventory().items); armor = copies(player.getInventory().armor); offhand = copies(player.getInventory().offhand);
            var capability = AccessoriesCapability.get(player);
            require(capability != null, "No Accessories capability");
            capability.getContainers().forEach((name, container) -> containers.put(name,
                    new ContainerState(containerCopies(container, false), containerCopies(container, true), new ArrayList<>(container.renderOptions()))));
        }
        void clear() {
            owner.getInventory().items.replaceAll(stack -> ItemStack.EMPTY);
            owner.getInventory().armor.replaceAll(stack -> ItemStack.EMPTY);
            owner.getInventory().offhand.replaceAll(stack -> ItemStack.EMPTY);
            AccessoriesCapability.get(owner).getContainers().values().forEach(container -> {
                container.getAccessories().clearContent(); container.getCosmeticAccessories().clearContent();
            });
        }
        void assertSame(AbstractClientPlayer player) {
            require(isScarfWrapped(player) == scarfWrapped, "Scarf attachment state changed during draw");
            require(sameStacks(items, player.getInventory().items) && sameStacks(armor, player.getInventory().armor) && sameStacks(offhand, player.getInventory().offhand), "Player inventory changed during draw");
            containers.forEach((name, state) -> {
                var container = AccessoriesCapability.get(player).getContainers().get(name);
                require(sameStacks(state.normal, containerCopies(container, false)) && sameStacks(state.cosmetic, containerCopies(container, true)) && state.render.equals(container.renderOptions()), "Accessory/cosmetic inventory or render flags changed: " + name);
            });
        }
        void restore(AbstractClientPlayer player) {
            setScarfWrapped(player, scarfWrapped);
            restoreStacks(player.getInventory().items, items); restoreStacks(player.getInventory().armor, armor); restoreStacks(player.getInventory().offhand, offhand);
            containers.forEach((name, state) -> {
                var container = AccessoriesCapability.get(player).getContainers().get(name);
                for (int i = 0; i < state.normal.size(); i++) container.getAccessories().setItem(i, state.normal.get(i).copy());
                for (int i = 0; i < state.cosmetic.size(); i++) container.getCosmeticAccessories().setItem(i, state.cosmetic.get(i).copy());
                for (int i = 0; i < state.render.size(); i++) container.renderOptions().set(i, state.render.get(i));
            });
        }
    }
    private static Object scarfAttachment(AbstractClientPlayer player) {
        try {
            var holder = (java.util.function.Supplier<?>) Class.forName("io.github.razordevs.deep_aether.networking.attachment.DAAttachments").getField("PLAYER").get(null);
            return player.getData((net.neoforged.neoforge.attachment.AttachmentType<?>) holder.get());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot access installed scarf attachment", error); }
    }
    private static boolean isScarfWrapped(AbstractClientPlayer player) {
        try {
            Object attachment = scarfAttachment(player);
            return (boolean) attachment.getClass().getMethod("isFloatyScarfWrappedAroundNeck").invoke(attachment);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot read scarf attachment", error); }
    }
    private static void setScarfWrapped(AbstractClientPlayer player, boolean value) {
        try {
            Object attachment = scarfAttachment(player);
            attachment.getClass().getMethod("setFloatyScarfWrappedAroundNeck", boolean.class).invoke(attachment, value);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot set scarf attachment", error); }
    }
    private static List<ItemStack> containerCopies(AccessoriesContainer container, boolean cosmetic) {
        var inventory = cosmetic ? container.getCosmeticAccessories() : container.getAccessories();
        List<ItemStack> values = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) values.add(inventory.getItem(i).copy());
        return values;
    }
    private static List<ItemStack> copies(List<ItemStack> stacks) { return stacks.stream().map(ItemStack::copy).toList(); }
    private static boolean sameStacks(List<ItemStack> expected, List<ItemStack> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) if (!ItemStack.matches(expected.get(i), actual.get(i))) return false;
        return true;
    }
    private static void restoreStacks(List<ItemStack> target, List<ItemStack> source) { for (int i = 0; i < source.size(); i++) target.set(i, source.get(i).copy()); }
    private record PartState(float[] transform, boolean visible, boolean skipDraw) {}
    private static Map<ModelPart, PartState> snapshot(PlayerModel<?> model) {
        Map<ModelPart, PartState> parts = new IdentityHashMap<>();
        try {
            for (Class<?> type = model.getClass(); type != null; type = type.getSuperclass()) {
                for (var field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !ModelPart.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    ModelPart root = (ModelPart) field.get(model);
                    if (root != null) root.getAllParts().forEach(part -> parts.put(part, new PartState(ModelState.state(part), part.visible, part.skipDraw)));
                }
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot snapshot complete player model", failure); }
        return parts;
    }
    private static boolean sameModel(Map<ModelPart, PartState> saved) { return saved.entrySet().stream().allMatch(e -> ModelState.same(e.getValue().transform, ModelState.state(e.getKey())) && e.getValue().visible == e.getKey().visible && e.getValue().skipDraw == e.getKey().skipDraw); }
    private static void restoreModel(Map<ModelPart, PartState> saved) {
        saved.forEach((part, state) -> {
            float[] s = state.transform;
            part.x=s[0]; part.y=s[1]; part.z=s[2]; part.xRot=s[3]; part.yRot=s[4]; part.zRot=s[5]; part.xScale=s[6]; part.yScale=s[7]; part.zScale=s[8]; part.visible=state.visible; part.skipDraw=state.skipDraw;
        });
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Counter implements VertexConsumer {
        int vertices, invalid;
        @Override public VertexConsumer addVertex(float x,float y,float z) { vertices++; if (!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)) invalid++; return this; }
        @Override public VertexConsumer setColor(int r,int g,int b,int a) { return this; }
        @Override public VertexConsumer setUv(float u,float v) { return this; }
        @Override public VertexConsumer setUv1(int u,int v) { return this; }
        @Override public VertexConsumer setUv2(int u,int v) { return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) { return this; }
    }
}
