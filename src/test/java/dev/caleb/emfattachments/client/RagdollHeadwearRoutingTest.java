package dev.caleb.emfattachments.client;

import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class RagdollHeadwearRoutingTest {
    @Test void oneFlexibleHeadItemMovesFromTwoLegsAndTorsoToOnlyTheHead() {
        assertTrue(AccessoryVisibilityPolicy.isHeadModel("artifacts:whoopee_cushion"));
        int accessoryNative = 0, curioNative = 0, correctedAccessories = 0, correctedCurios = 0;
        for (BodyPart part : BodyPart.values()) {
            boolean a = RagdollAccessoriesSlotRouting.resolveSlot("anklet").contains(part);
            boolean c = RagdollHeadwearRouting.curioBelongsToPart("anklet", part);
            if (a) accessoryNative++;
            if (c) curioNative++;
            boolean routedA = RagdollHeadwearRouting.belongsToPart(true, part, a);
            boolean routedC = RagdollHeadwearRouting.belongsToPart(true, part, c);
            assertEquals(part == BodyPart.HEAD, routedA);
            assertEquals(part == BodyPart.HEAD, routedC);
            if (routedA) correctedAccessories++;
            if (routedC) correctedCurios++;
        }
        assertEquals(2, accessoryNative, "The installed anklet classifier renders both physical legs");
        assertEquals(1, curioNative, "The bridge's unknown slot originally renders again on the torso");
        assertEquals(1, correctedAccessories);
        assertEquals(1, correctedCurios, "The existing slot/index/stack ledger removes this bridged repeat");
    }

    @Test void ordinaryShoesGlovesAndNecklacesKeepTheirInstalledNativePhysicalRouting() {
        for (String item : List.of("artifacts:flippers", "artifacts:power_glove", "artifacts:cross_necklace")) {
            assertFalse(AccessoryVisibilityPolicy.isHeadModel(item));
            for (String slot : List.of("shoes", "anklet", "hand", "ring", "necklace", "face", "unknown_slot")) {
                for (BodyPart part : BodyPart.values()) {
                    boolean nativeResult = RagdollAccessoriesSlotRouting.resolveSlot(slot).contains(part);
                    assertEquals(nativeResult, RagdollHeadwearRouting.belongsToPart(false, part, nativeResult));
                    boolean nativeCurio = RagdollHeadwearRouting.curioBelongsToPart(slot, part);
                    assertEquals(nativeCurio, RagdollHeadwearRouting.belongsToPart(false, part, nativeCurio));
                }
            }
        }
    }

    @Test void distinctHeadStacksRemainIndependentInsteadOfAnItemIdGlobalDeduplication() {
        List<String> selectedSlots = List.of("artifacts:whoopee_cushion", "artifacts:whoopee_cushion",
                "artifacts:snorkel", "artifacts:cross_necklace");
        long headCalls = selectedSlots.stream().filter(item -> RagdollHeadwearRouting.belongsToPart(
                AccessoryVisibilityPolicy.isHeadModel(item), BodyPart.HEAD, false)).count();
        assertEquals(3, headCalls, "Separate slots keep each independently equipped head model");
        assertEquals(1, selectedSlots.stream().filter(item -> RagdollHeadwearRouting.belongsToPart(
                AccessoryVisibilityPolicy.isHeadModel(item), BodyPart.TORSO, true)).count());
    }

    @Test void installedStoredPathsStillCheckNativeHiddenFlagsAfterTheirSlotFilter() throws Exception {
        for (String helper : List.of("AccessoriesRenderHelper", "CuriosRenderHelper")) {
            List<String> operations = new ArrayList<>();
            String resource = "dev/leo/sableplayerragdoll/neoforge/client/" + helper + ".class";
            try (InputStream bytes = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(bytes);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        if (!name.equals("renderFromStored")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) { operations.add(name); }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            String filter = helper.startsWith("Accessories") ? "slotIndexBelongsToPart" : "slotBelongsToPart";
            assertTrue(operations.indexOf(filter) >= 0);
            assertTrue(operations.indexOf("storedShouldRender") > operations.indexOf(filter),
                    "New routing must still pass through the installed per-slot hidden flag");
            assertTrue(operations.contains("storedEffectiveStack"), "Native cosmetic selection remains in place");
        }
    }
}
