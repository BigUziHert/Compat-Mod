package dev.caleb.emfattachments.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.tr7zw.firstperson.FirstPersonModelCore;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.menu.ArmorSlotTypes;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** All wearable slots through actual transformed local PlayerRenderer, with explicit render contexts. */
public final class ExpandedLocalEquipmentProbe {
    private record Case(String id, String slot, int index, EquipmentSlot armor) {}
    private ExpandedLocalEquipmentProbe() {}
    public static void register() { NeoForge.EVENT_BUS.addListener(ExpandedLocalEquipmentProbe::tick); }
    private static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || LiveFirstPersonSequence.isActive() || CombatRenderCounters.active) return;
        Path shieldTrigger = mc.gameDirectory.toPath().resolve("emfattachments-shield-probe.txt");
        boolean shieldOnly = Files.isRegularFile(shieldTrigger);
        Path trigger = shieldOnly ? shieldTrigger : mc.gameDirectory.toPath().resolve("emfattachments-expanded-local.txt");
        if (!Files.isRegularFile(trigger)) return;
        try {
            if (!mc.hasSingleplayerServer() || !mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of(".local","client")))
                throw new IllegalStateException("Requires isolated integrated client");
            String label = Files.readString(trigger).trim().replaceAll("[^a-zA-Z0-9._-]", "_"); Files.delete(trigger);
            Path output = mc.gameDirectory.toPath().resolve("emfattachments-tests").resolve(label); Files.createDirectories(output);
            Files.writeString(output.resolve(shieldOnly ? "shield-report.json" : "expanded-local-report.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(shieldOnly ? ShieldVisibilityProbe.run() : run()));
        } catch (Throwable error) { com.mojang.logging.LogUtils.getLogger().error("Expanded local matrix failed", error); }
    }
    public static Map<String,Object> run() {
        var mc = Minecraft.getInstance(); var player = mc.player; var core = FirstPersonModelCore.instance;
        Map<String,Object> report = new LinkedHashMap<>(); List<Map<String,Object>> rows = new ArrayList<>(); List<String> failures = new ArrayList<>();
        report.put("scope", "Every discovered wearable item/slot/index through actual local PlayerRenderer with FirstPerson body and third-person contexts; idle-down and native17-use-tick food fixture. Controlled direct renders, not live gameplay screenshots.");
        report.put("rows",rows); report.put("failures",failures);
        require(!player.isUsingItem(), "Local player must not already be using an item");
        EntityState original = new EntityState(player); var originalCurrent = AttachmentTestMod.current;
        boolean enabled=FirstPersonAPI.isEnabled(), raw=core.isRenderingPlayerRaw(), extract=FirstPersonModelCore.cameraEntityExtract;
        CameraType camera = mc.options.getCameraType(); var cameraEntity = mc.getCameraEntity();
        float pitch=player.getXRot(), pitchO=player.xRotO;
        boolean iris=net.neoforged.fml.ModList.get().isLoaded("iris"), shadow=iris && net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE;
        var quiverOwner = (net.mehvahdjukaar.supplementaries.common.entities.IQuiverEntity) (Object) player;
        ItemStack originalQuiver=quiverOwner.supplementaries$getQuiver().copy();
        var renderer=(PlayerRenderer)(Object)mc.getEntityRenderDispatcher().getRenderer(player);
        var originalModel=snapshot(renderer.getModel());
        List<Case> cases=new ArrayList<>();
        Map<String,Integer> normalAccessoryVertices = new HashMap<>();
        try {
            for (var item : BuiltInRegistries.ITEM) {
                ItemStack stack=item.getDefaultInstance(); if(stack.isEmpty()) continue;
                String id=BuiltInRegistries.ITEM.getKey(item).toString();
                EquipmentSlot armor=player.getEquipmentSlotForItem(stack);
                if(armor==EquipmentSlot.HEAD||armor==EquipmentSlot.CHEST||armor==EquipmentSlot.LEGS||armor==EquipmentSlot.FEET) cases.add(new Case(id,"equipment:"+armor.getName(),0,armor));
                for(var slot:AccessoriesAPI.getStackSlotTypes(player,stack)) {
                    if(ArmorSlotTypes.isArmorType(slot.name())) continue;
                    var container=AccessoriesCapability.get(player).getContainer(slot);
                    if(container==null) { failures.add("Missing container:"+id+":"+slot.name()); continue; }
                    for(int i=0;i<container.getSize();i++) cases.add(new Case(id,slot.name(),i,null));
                }
            }
            cases.sort(Comparator.comparing(Case::id).thenComparing(Case::slot).thenComparingInt(Case::index));
            report.put("uniqueItems",cases.stream().map(Case::id).distinct().count()); report.put("slotCases",cases.size());
            setScarfWrapped(player,true); FirstPersonAPI.setEnabled(true); mc.setCameraEntity(player);
            if(iris) net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE=false;
            player.setXRot(60); player.xRotO=60;
            for(Case c:cases) for(boolean cosmetic : c.armor==null ? new boolean[]{false,true} : new boolean[]{false}) {
                for(boolean eating:new boolean[]{false,true}) for(boolean body:new boolean[]{false,true}) {
                    Map<String,Object> row=new LinkedHashMap<>(); rows.add(row);
                    row.put("item",c.id);row.put("slot",c.slot);row.put("slotIndex",c.index);row.put("cosmetic",cosmetic);
                    row.put("action",eating?"native_eat_17ticks":"look_down");row.put("context",body?"local-firstperson-body":"third-person");
                    try {
                        player.stopUsingItem(); original.clear();
                        ItemStack stack=BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(c.id)).getDefaultInstance();
                        if(c.armor!=null) player.setItemSlot(c.armor,stack);
                        else {
                            var container=AccessoriesCapability.get(player).getContainers().get(c.slot);
                            (cosmetic?container.getCosmeticAccessories():container.getAccessories()).setItem(c.index,stack);
                            if(c.index<container.renderOptions().size()) container.renderOptions().set(c.index,true);
                        }
                        quiverOwner.supplementaries$setQuiver(c.id.equals("supplementaries:quiver")?stack:ItemStack.EMPTY);
                        if(eating) {
                            ItemStack food=net.minecraft.world.item.Items.BREAD.getDefaultInstance();
                            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,food);
                            player.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                            var update=net.minecraft.world.entity.LivingEntity.class.getDeclaredMethod("updateUsingItem",ItemStack.class);update.setAccessible(true);
                            for(int i=0;i<17;i++) update.invoke(player,food);
                            require(player.isUsingItem() && player.getUseItemRemainingTicks()==food.getUseDuration(player)-17,"Native local use fixture did not advance17ticks");
                        }
                        row.put("usingItem",player.isUsingItem());row.put("useTicks",player.getTicksUsingItem());
                        EntityState expected=new EntityState(player);
                        core.setRenderingPlayer(body);FirstPersonModelCore.cameraEntityExtract=false;
                        mc.options.setCameraType(body?CameraType.FIRST_PERSON:CameraType.THIRD_PERSON_BACK);
                        mc.gameRenderer.getMainCamera().setup(mc.level,player,!body,false,0F);
                        if(body) require(dev.caleb.emfattachments.client.FirstPersonRenderPolicy.isLocalBody(player),"Local body scope inactive");
                        boolean expectedHidden = c.armor == null && body && dev.caleb.emfattachments.client.FirstPersonRenderPolicy.shouldHide(player,c.slot,stack);
                        row.put("expectedAccessoryHidden",expectedHidden);
                        var r=new AttachmentTestMod.TestResult();r.item=c.id;r.slot=c.slot;r.slotIndex=c.index;r.pose=eating?"eat":"down";r.variant="default";
                        AttachmentTestMod.current=r;
                        Counter counter=new Counter();MultiBufferSource buffers=counter.buffers();
                        try(var emf=new EmfProbeContext(player)) { renderer.render(player,player.getYRot(),0F,new PoseStack(),buffers,0xF000F0); }
                        r.headPartClass=renderer.getModel().head.getClass().getName();r.playerModelClass=renderer.getModel().getClass().getName();
                        r.renderedHead=ModelState.state(renderer.getModel().head);
                        row.put("renderedRightArm",ModelState.state(renderer.getModel().rightArm));row.put("renderedLeftArm",ModelState.state(renderer.getModel().leftArm));
                        expected.assertSame(player);
                        require(counter.invalid==0,"Non-finite full-player geometry");
                        require(r.invalidVertices==0 && r.mutatedParts==0,"Accessory render changed parent or emitted invalid geometry");
                        require(r.gogglesAnchorFailures+r.torsoAnchorFailures+r.flowerAnchorFailures+r.flowerColorFailures==0,"Attachment anchor/color failed");
                        if(expectedHidden) require(r.vertices==0,"Hidden accessory still emitted geometry");
                        String baselineKey=c.id+":"+c.slot+":"+c.index+":"+cosmetic+":"+eating;
                        if(!body) normalAccessoryVertices.put(baselineKey,r.vertices);
                        else {
                            int baseline=normalAccessoryVertices.getOrDefault(baselineKey,0);
                            row.put("normalAccessoryVertices",baseline);row.put("zeroGeometryInNormalContext",baseline==0);
                            core.setRenderingPlayer(false);mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                            mc.gameRenderer.getMainCamera().setup(mc.level,player,true,false,0F);
                            var restored=new AttachmentTestMod.TestResult();restored.item=c.id;restored.slot=c.slot;restored.slotIndex=c.index;restored.pose="restored-thirdperson";restored.variant="default";
                            AttachmentTestMod.current=restored;Counter restoredCounter=new Counter();MultiBufferSource restoredBuffers=restoredCounter.buffers();
                            try(var emf=new EmfProbeContext(player)) { renderer.render(player,player.getYRot(),0F,new PoseStack(),restoredBuffers,0xF000F0); }
                            expected.assertSame(player);require(restoredCounter.invalid==0,"Restored third-person geometry invalid");
                            row.put("restoredThirdPersonAccessoryVertices",restored.vertices);
                            require(baseline==0 || restored.vertices>0,"Previously visible accessory disappeared in restored third person");
                        }
                        r.completed=true;row.put("attachmentChecks",r);row.put("fullPlayerVertices",counter.vertices);row.put("invalidVertices",counter.invalid);row.put("status","PASS");
                    } catch(Throwable error) { row.put("status","FAIL");row.put("error",error.toString());failures.add(c.id+":"+c.slot+":"+error); }
                    finally { player.stopUsingItem();core.setRenderingPlayer(false);FirstPersonModelCore.cameraEntityExtract=false;restoreModel(originalModel);AttachmentTestMod.current=null; }
                }
            }
        } finally {
            player.stopUsingItem();original.restore(player);quiverOwner.supplementaries$setQuiver(originalQuiver);
            player.setXRot(pitch);player.xRotO=pitchO;restoreModel(originalModel);
            FirstPersonAPI.setEnabled(enabled);core.setRenderingPlayer(raw);FirstPersonModelCore.cameraEntityExtract=extract;
            mc.options.setCameraType(camera);mc.setCameraEntity(cameraEntity);if(iris)net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE=shadow;
            mc.gameRenderer.getMainCamera().setup(mc.level,cameraEntity,!camera.isFirstPerson(),camera.isMirrored(),0F);
            AttachmentTestMod.current=originalCurrent;original.assertSame(player);report.put("stateRestored",true);
        }
        report.put("status",failures.isEmpty()?"PASS":"FAIL");report.put("renderCases",rows.size());return report;
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
        final Counter totals;
        Counter() { totals=this; }
        Counter(Counter totals) { this.totals=totals; }
        MultiBufferSource buffers() {
            var delegates=new HashMap<net.minecraft.client.renderer.RenderType,VertexConsumer>();
            return type->delegates.computeIfAbsent(type,ignored->new Counter(this));
        }
        @Override public VertexConsumer addVertex(float x,float y,float z) { totals.vertices++; if (!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)) totals.invalid++; return this; }
        @Override public VertexConsumer setColor(int r,int g,int b,int a) { return this; }
        @Override public VertexConsumer setUv(float u,float v) { return this; }
        @Override public VertexConsumer setUv1(int u,int v) { return this; }
        @Override public VertexConsumer setUv2(int u,int v) { return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) { return this; }
    }
}
