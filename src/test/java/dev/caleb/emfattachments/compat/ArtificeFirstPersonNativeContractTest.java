package dev.caleb.emfattachments.compat;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** Guard the installed optional API contract used to select exactly one hand renderer. */
class ArtificeFirstPersonNativeContractTest {
    @Test void supportBoneAndVanillaOffhandUseSeparateNativeRenderDecisions() throws Exception {
        String gun = "io/redspace/irons_artifice/item/GunItem";
        String renderer = "io/redspace/irons_artifice/client/gun/GunInHandRenderer";
        String client = "io/redspace/irons_artifice/client/ClientHelper";
        String occupancy = "Lio/redspace/irons_artifice/data/HandOccupancy;";
        String entity = "(Lnet/minecraft/world/entity/LivingEntity;";
        String hand = "Lnet/minecraft/world/InteractionHand;";
        assertEquals(1, calls(renderer, "handleFirstPersonHandRendering", renderer, "currentOccupancy",
                entity + ")" + occupancy));
        assertEquals(1, calls(renderer, "currentOccupancy", gun, "currentOccupancy",
                entity + "Lnet/minecraft/world/item/ItemStack;)" + occupancy));
        assertEquals(2, calls(client, "getHandHoldingTwoHandedGun", gun, "currentOccupancy",
                entity + hand + ")" + occupancy), "Iron checks the main hand and offhand independently");
        assertEquals(1, calls("io/redspace/irons_artifice/mixin/ItemInHandRendererMixin",
                "irons_artifice$hideOtherHandForTwoHandedGun", client, "getHandHoldingTwoHandedGun",
                "(Lnet/minecraft/client/player/LocalPlayer;)" + hand));
    }

    private static long calls(String owner, String method, String target, String name, String descriptor)
            throws Exception {
        MethodNode node = classNode(owner).methods.stream().filter(m -> m.name.equals(method))
                .findFirst().orElseThrow();
        return Arrays.stream(node.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                && call.owner.equals(target) && call.name.equals(name) && call.desc.equals(descriptor)).count();
    }

    private static ClassNode nativeLogic() throws Exception {
        return classNode("dev/tr7zw/firstperson/LogicHandler");
    }

    private static ClassNode classNode(String name) throws Exception {
        try (InputStream bytes = ArtificeFirstPersonNativeContractTest.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
            assertNotNull(bytes);
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    @Test void bodyGunDecisionUsesTheInventoryThatSurvivesNativeHiddenHandGetters() throws Exception {
        // The installed mod masks equipment getters during its body render. A
        // normal entity hand lookup is therefore not a valid ownership signal.
        ClassNode playerMixin = classNode("dev/tr7zw/firstperson/mixins/PlayerMixin");
        MethodNode hiddenEquipment = playerMixin.methods.stream()
                .filter(m -> m.name.equals("getItemBySlot")).findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(hiddenEquipment.instructions.toArray())
                .anyMatch(i -> i instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals("net/minecraft/world/item/ItemStack") && field.name.equals("EMPTY")));
        assertTrue(java.util.Arrays.stream(hiddenEquipment.instructions.toArray())
                .anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals("hideArmsAndItems")));

        MethodNode bodyDecision = classNode("dev/caleb/emfattachments/client/ArtificeFirstPersonBodyArms")
                .methods.stream().filter(m -> m.name.equals("nativeArmMask")).findFirst().orElseThrow();
        for (String accessor : List.of("getInventory", "getSelected", "getOffhand")) {
            assertTrue(java.util.Arrays.stream(bodyDecision.instructions.toArray())
                    .anyMatch(i -> i instanceof MethodInsnNode call
                            && call.owner.equals("dev/tr7zw/firstperson/InventoryUtil") && call.name.equals(accessor)),
                    "The gun body decision must use FirstPerson's unmasked " + accessor + " accessor");
        }
        for (AbstractInsnNode instruction : bodyDecision.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertFalse(List.of("getMainHandItem", "getOffhandItem", "getItemBySlot").contains(call.name),
                        "FirstPerson substitutes EMPTY for these getters during the body pass");
            }
        }
        assertTrue(java.util.Arrays.stream(bodyDecision.instructions.toArray())
                .anyMatch(i -> i instanceof MethodInsnNode call
                        && call.owner.equals("io/redspace/irons_artifice/item/GunItem")
                        && call.name.equals("currentOccupancy")
                        && call.desc.contains("Lnet/minecraft/world/item/ItemStack;")),
                "Use Iron's native reload/fire/default occupancy with the unmasked stack");
    }

    @Test void nativeItemCacheControlsHandPassBodyArmsAndDynamicHandoffForBothHands() throws Exception {
        ClassNode node = nativeLogic();
        for (String decision : List.of("showVanillaHands", "hideArmsAndItems", "dynamicHandsEnabled")) {
            MethodNode method = node.methods.stream().filter(m -> m.name.equals(decision)
                    && m.desc.contains("Lnet/minecraft/world/item/ItemStack;")).findFirst().orElseThrow();
            int itemCacheReads = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && field.name.equals("autoVanillaHandItems")) itemCacheReads++;
            }
            assertEquals(2, itemCacheReads, decision + " must consult the registered items for each hand");
        }
    }

    @Test void nativeRegistrationChangesOnlyRuntimeItemCacheAndReloadClearsIt() throws Exception {
        ClassNode node = nativeLogic();
        MethodNode add = node.methods.stream().filter(m -> m.name.equals("addAutoVanillaHandsItem"))
                .findFirst().orElseThrow();
        boolean addsToSet = false;
        for (AbstractInsnNode instruction : add.instructions) {
            if (instruction instanceof FieldInsnNode field) {
                assertEquals("autoVanillaHandItems", field.name,
                        "The native registration API must not mutate enabled state or persistent options");
            }
            if (instruction instanceof MethodInsnNode call) {
                assertEquals("java/util/Set", call.owner);
                assertEquals("add", call.name);
                addsToSet = true;
            }
        }
        assertTrue(addsToSet);
        MethodNode reload = node.methods.stream().filter(m -> m.name.equals("reloadAutoVanillaHandsSettings"))
                .findFirst().orElseThrow();
        boolean resetsCache = false;
        for (AbstractInsnNode instruction : reload.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("java/util/Set")
                    && call.name.equals("clear")) resetsCache = true;
        }
        assertTrue(resetsCache, "Gun registrations must be restored after every native settings reload");
    }
}
