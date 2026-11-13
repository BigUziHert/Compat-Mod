package dev.caleb.emfattachments.client;

import io.redspace.irons_artifice.client.gun.GunArmPoses;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import strm.emfcompat.core.EMFCompatCore;
import strm.emfcompat.core.PoseInterpolator;
import strm.emfcompat.core.PoseManager;
import strm.emfcompat.core.PoseSnapshot;
import strm.emfcompat.core.SavedPoses;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the installed bridge's restoration/selection code, rather than a copied pose formula. */
class EmfGunOverlapTest {
    private final UUID player = UUID.randomUUID();
    private final UUID otherPlayer = UUID.randomUUID();
    private boolean originalEnabled;
    private float originalFrameSeconds;
    private long originalFrameId, originalPoseFrame;

    @BeforeEach void isolateBridgeClock() throws Exception {
        originalEnabled = EMFCompatCore.isCompatEnabled();
        EMFCompatCore.setCompatEnabled(true);
        originalFrameSeconds = (float) field(PoseInterpolator.class, "frameSeconds").get(null);
        originalFrameId = (long) field(PoseInterpolator.class, "frameId").get(null);
        originalPoseFrame = PoseManager.currentFrame;
        // A deterministic first animation frame, without creating a Minecraft client or sleeping.
        field(PoseInterpolator.class, "frameSeconds").set(null, 0f);
        field(PoseInterpolator.class, "frameId").set(null, originalFrameId + 1);
    }

    @AfterEach void restoreBridgeState() throws Exception {
        PoseManager.entitySavedPoses.remove(player);
        PoseManager.entitySavedPoses.remove(otherPlayer);
        PoseManager.entitySavedPosesBySource.remove(player);
        PoseManager.entitySavedPosesBySource.remove(otherPlayer);
        Map<?, ?> states = (Map<?, ?>) field(PoseInterpolator.class, "STATES").get(null);
        states.remove(player);
        states.remove(otherPlayer);
        PoseManager.currentFrame = originalPoseFrame;
        field(PoseInterpolator.class, "frameSeconds").set(null, originalFrameSeconds);
        field(PoseInterpolator.class, "frameId").set(null, originalFrameId);
        EMFCompatCore.setCompatEnabled(originalEnabled);
    }

    @Test void installedCoreAndArtificeDoNotRegisterAGunPoseWithEachOther() throws Exception {
        assertFalse(referencesInJar(EMFCompatCore.class).stream()
                .anyMatch(name -> name.startsWith("io/redspace/irons_artifice/")),
                "A newly added upstream Artifice integration needs another overlap audit");
        assertFalse(referencesInJar(GunArmPoses.class).stream()
                .anyMatch(name -> name.startsWith("strm/emfcompat/")),
                "A native Artifice-to-Core integration needs another overlap audit");
        Map<String, ModelPart> parts = gunParts();
        GunArmPoses.animateCrossbowCharge(parts.get("right_arm"), parts.get("left_arm"), 1f, .7f, true);
        assertFalse(PoseManager.hasAnyPose(player));
        assertNull(PoseManager.getSavedPoses(player), "A native gun pose alone does not populate Core's registry");
    }

    @Test void upstreamRotationOnlyClaimDoesNotRestoreNativeGunOffsetsOrScale() throws Exception {
        Map<String, ModelPart> parts = gunParts();
        ModelPart arm = parts.get("right_arm");
        float[] nativeTransform = transform(arm);
        PoseSnapshot snapshot = new PoseSnapshot(arm).blended(1);
        ArtificeGunPose ours = ArtificeGunPose.capture(parts, false, true);
        overwriteByPack(arm);

        upstreamApplyArm(arm, snapshot, null, true);
        assertEquals(nativeTransform[3], arm.xRot, 1e-6f);
        assertEquals(11f, arm.x, "The native bridge has no body-follow position when bodyBase is absent");
        assertEquals(1.6f, arm.xScale);
        ours.restore();
        assertArrayEquals(nativeTransform, transform(arm), 1e-6f);
    }

    @Test void identicalFullWeightBodyFollowIsTheNarrowTransformOverlap() throws Exception {
        for (boolean smooth : List.of(false, true)) {
            Map<String, ModelPart> oursParts = gunParts(), upstreamParts = gunParts();
            ModelPart oursArm = oursParts.get("right_arm"), upstreamArm = upstreamParts.get("right_arm");
            PoseSnapshot snapshot = new PoseSnapshot(upstreamArm).blended(1);
            ArtificeGunPose ours = ArtificeGunPose.capture(oursParts, false, true);
            oursParts.get("body").setPos(3, 5, -7);
            overwriteByPack(oursArm);
            overwriteByPack(upstreamArm);
            oursArm.visible = upstreamArm.visible = false;
            oursArm.skipDraw = upstreamArm.skipDraw = true;

            upstreamApplyArm(upstreamArm, snapshot, new Vector3f(1, 2, -3), smooth);
            ours.restore();

            assertArrayEquals(transform(oursArm), transform(upstreamArm), 1e-6f,
                    "Transform equality requires the identical native snapshot, body delta, and full weight");
            assertFalse(oursArm.visible, "Our gun preservation does not unhide an EMF/FirstPerson part");
            assertTrue(upstreamArm.visible, "Core's arm restoration also restores its captured visible flag");
            assertTrue(oursArm.skipDraw);
            assertTrue(upstreamArm.skipDraw, "Core's arm path leaves the current skipDraw flag alone");
        }
    }

    @Test void genericSmoothTransitionDoesNotPreserveImmediateNativeReloadTiming() throws Exception {
        Map<String, ModelPart> parts = gunParts();
        ModelPart arm = parts.get("right_arm");
        GunArmPoses.animateCrossbowCharge(arm, parts.get("left_arm"), 1f, .25f, true);
        float nativePitch = arm.xRot;
        PoseSnapshot snapshot = new PoseSnapshot(arm);
        ArtificeGunPose ours = ArtificeGunPose.capture(parts, false, true);
        overwriteByPack(arm);
        float packPitch = arm.xRot;

        upstreamApplyArm(arm, snapshot, new Vector3f(), true);
        assertEquals(packPitch, arm.xRot, 1e-6f, "The first ordinary smooth claim starts at its live base");
        assertNotEquals(nativePitch, arm.xRot);
        ours.restore();
        assertEquals(nativePitch, arm.xRot, 1e-6f);
    }

    @Test void mixedSourceArmClaimsDoNotIdentifyTheGunOrItsBodyReference() {
        Map<String, ModelPart> parts = gunParts();
        PoseSnapshot gunLeft = new PoseSnapshot(parts.get("left_arm"));
        ModelPart carryArm = new ModelPart(List.of(), Map.of());
        carryArm.setRotation(.4f, -.8f, .2f);
        PoseSnapshot carryRight = new PoseSnapshot(carryArm);
        Vector3f gunBody = new Vector3f(2, 3, -4), otherBody = new Vector3f(-3, 8, 1);
        // Equal default priorities use the bridge's own stable source-name ordering.
        PoseManager.savePoses(player, "overlap_a_nea", new SavedPoses(gunLeft, null, null, gunBody));
        PoseManager.savePoses(player, "overlap_z_carry", new SavedPoses(null, null,
                Map.of("right_arm", carryRight), otherBody));

        SavedPoses merged = PoseManager.getSavedPoses(player);
        assertTrue(PoseManager.hasAnyPose(player));
        assertTrue(PoseManager.hasArmPoseExcept(player, "artifice"));
        assertSame(gunLeft, merged.leftArm());
        assertNull(merged.rightArm());
        assertSame(carryRight, merged.parts().get("right_arm"));
        assertSame(otherBody, merged.bodyBase(), "Body-follow provenance is shared across merged sources");
        assertNotSame(gunBody, merged.bodyBase());
        EMFCompatCore.setCompatEnabled(false);
        assertFalse(PoseManager.hasAnyPose(player));
        assertNull(PoseManager.getSavedPoses(player), "Installed Core can have no active restoration ownership");
    }

    @Test void partsMapPoseHasNoArmBodyFollowEvenWithAnAvailableBodyDelta() {
        Map<String, ModelPart> parts = gunParts();
        ModelPart arm = parts.get("right_arm");
        PoseSnapshot snapshot = new PoseSnapshot(arm).blended(1);
        ArtificeGunPose ours = ArtificeGunPose.capture(parts, false, true);
        parts.get("body").setPos(3, 5, -7);
        overwriteByPack(arm);
        PoseInterpolator.applyPart(player, "right_arm", arm, snapshot);
        assertEquals(snapshot.x, arm.x, 1e-6f);
        assertEquals(snapshot.y, arm.y, 1e-6f);
        ours.restore();
        assertNotEquals(snapshot.x, arm.x);
        assertNotEquals(snapshot.y, arm.y);
    }

    @Test void nativeInterpolationSharesOnePosePerEntityAndPartWithinAFrame() {
        ModelPart first = new ModelPart(List.of(), Map.of()), second = new ModelPart(List.of(), Map.of());
        first.xRot = -.5f;
        second.xRot = -1.4f;
        PoseSnapshot shadowPose = new PoseSnapshot(first).blended(1);
        PoseSnapshot bodyPose = new PoseSnapshot(second).blended(1);
        first.xRot = second.xRot = 0;

        PoseInterpolator.applyArm(player, "right_arm", first, shadowPose, null);
        PoseInterpolator.applyArm(player, "right_arm", second, bodyPose, null);
        assertEquals(first.xRot, second.xRot, 1e-6f,
                "Core's UUID/part history cannot distinguish body and shadow passes for the same player");
        assertNotEquals(bodyPose.xRot, second.xRot);
        PoseInterpolator.applyArm(otherPlayer, "right_arm", second, bodyPose, null);
        assertEquals(bodyPose.xRot, second.xRot, 1e-6f);
    }

    private void upstreamApplyArm(ModelPart arm, PoseSnapshot snapshot, Vector3f delta, boolean smooth)
            throws Exception {
        NativeArmHook.APPLY.invoke(null, player, "right_arm", arm, snapshot, delta, smooth);
    }

    /**
     * Run the installed private arm helper verbatim. Its EMF subtype only supplies ModelPart fields;
     * remapping that parameter/field owner permits a plain model without constructing an EMF runtime.
     * No instructions, constants, branches, or external calls in the helper are replaced.
     */
    private static final class NativeArmHook extends ClassLoader {
        static final Method APPLY = loadMethod();

        private NativeArmHook() {
            super(EmfGunOverlapTest.class.getClassLoader());
        }

        private static Method loadMethod() {
            try {
                ClassNode source = classNode("strm/emfcompat/core/EMFCompatAnimationHook");
                var method = source.methods.stream().filter(m -> m.name.equals("emfcompat$applyArm"))
                        .findFirst().orElseThrow();
                ClassNode fixture = new ClassNode();
                fixture.version = source.version;
                fixture.access = Opcodes.ACC_PUBLIC;
                fixture.name = "dev/caleb/emfattachments/client/InstalledCoreArmHook";
                fixture.superName = "java/lang/Object";
                method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
                fixture.methods.add(method);
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                fixture.accept(new ClassRemapper(writer, new Remapper() {
                    @Override public String map(String name) {
                        return name.equals("traben/entity_model_features/models/parts/EMFModelPartVanilla")
                                ? "net/minecraft/client/model/geom/ModelPart" : name;
                    }
                }));
                byte[] bytes = writer.toByteArray();
                Class<?> type = new NativeArmHook().defineClass(null, bytes, 0, bytes.length);
                return type.getMethod("emfcompat$applyArm", UUID.class, String.class, ModelPart.class,
                        PoseSnapshot.class, Vector3f.class, boolean.class);
            } catch (Exception error) {
                throw new ExceptionInInitializerError(error);
            }
        }
    }

    private static Set<String> referencesInJar(Class<?> type) throws Exception {
        Set<String> references = new HashSet<>();
        Path path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile jar = new JarFile(path.toFile())) {
            for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                try (InputStream bytes = jar.getInputStream(entry)) {
                    new ClassReader(bytes).accept(new ClassRemapper(new ClassNode(), new Remapper() {
                        @Override public String map(String name) { references.add(name); return name; }
                    }), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
        return references;
    }

    private static ClassNode classNode(String name) throws Exception {
        try (InputStream bytes = EmfGunOverlapTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(bytes);
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Map<String, ModelPart> gunParts() {
        Map<String, ModelPart> parts = new HashMap<>();
        for (String name : List.of("body", "left_arm", "right_arm", "left_sleeve", "right_sleeve"))
            parts.put(name, new ModelPart(List.of(), Map.of()));
        parts.get("body").setPos(2, 3, -4);
        parts.get("left_arm").setPos(5, 3, -3);
        parts.get("left_arm").setRotation(-1.5f, .8f, .1f);
        ModelPart right = parts.get("right_arm");
        right.setPos(-5, 4, 2);
        right.setRotation(-1.47f, .15f, -.1f);
        right.xScale = .9f;
        return parts;
    }

    private static void overwriteByPack(ModelPart arm) {
        arm.setPos(11, 8, 5);
        arm.setRotation(.7f, .4f, .2f);
        arm.xScale = 1.6f;
    }

    private static float[] transform(ModelPart part) {
        return new float[]{part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                part.xScale, part.yScale, part.zScale};
    }
}
