package dev.caleb.emfattachments.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import strm.emfcompat.core.CrouchNormalizer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays installed transform instructions, not a reimplementation of their formulas.
 * Only environment boundaries (entity lookup, mod settings, pass ownership and fade
 * weight) are controlled. This does not establish pixel-equivalence in a live client.
 */
public class EmfCombatOverlapTest {
    private static final String PAL = "dev/kosmx/playerAnim/mixin/PlayerRendererMixin";
    private static final String CORE = "strm/emfcompat/core/mixin/SetupRotationsMixin";
    private static final String BRIDGE = "strm/emfcompat/bettercombat/mixin/AttackAnimationSubStackMixin";
    private static final String OURS = "dev/caleb/emfattachments/mixin/FirstPersonCombatAnimationMixin";
    private static final String SETUP = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;"
            + "setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V";

    @Test void installedInjectionAnchorsPutCoreAfterPlayerAnimatorsBodyTransform() throws Exception {
        AnnotationNode pal = inject(method(classNode(PAL), "applyBodyTransforms"));
        assertEquals(List.of("setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;"
                + "Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V"), value(pal, "method"));
        assertEquals("RETURN", value(at(pal), "value"));

        AnnotationNode before = at(inject(method(classNode(CORE), "emfcompat$beforeSetupRotations")));
        AnnotationNode after = at(inject(method(classNode(CORE), "emfcompat$afterSetupRotations")));
        assertEquals("INVOKE", value(before, "value"));
        assertEquals(SETUP, value(before, "target"));
        assertNull(value(before, "shift"), "The native default anchor snapshots before setupRotations");
        assertEquals(SETUP, value(after, "target"));
        assertEquals("AFTER", ((String[]) value(after, "shift"))[1]);
    }

    @Test void nativeCoreMakesTheCrouchedYOffsetUnobservableAfterSetupRotations() throws Exception {
        NativeChain chain = new NativeChain();
        for (boolean bridgeEnabled : List.of(false, true)) {
            Boundary.bridgeEnabled = bridgeEnabled;
            for (float weight : new float[]{1, .5f, .1f}) {
                Boundary.weight = weight;
                for (Matrix4f before : List.of(new Matrix4f(),
                        new Matrix4f().translate(3, 7, -4).rotateY(.8f).scale(.75f))) {
                    Boundary.yOffset = -.1;
                    Matrix4f withOffset = chain.render(before, true, CrouchNormalizer.Mode.ALL);
                    Boundary.yOffset = 0;
                    Matrix4f withoutOffset = chain.render(before, true, CrouchNormalizer.Mode.ALL);
                    assertMatrixEquals(withOffset, withoutOffset,
                            "Core owns setupRotations Y with bridge=" + bridgeEnabled + ", weight=" + weight);
                }
            }
        }
    }

    @Test void absentOrDisabledCoreStillNeedsTheConfiguredCrouchedYOffset() throws Exception {
        NativeChain chain = new NativeChain();
        for (CrouchNormalizer.Mode mode : new CrouchNormalizer.Mode[]{null, CrouchNormalizer.Mode.NONE}) {
            Boundary.yOffset = -.1;
            Matrix4f withOffset = chain.render(new Matrix4f(), true, mode);
            Boundary.yOffset = 0;
            Matrix4f withoutOffset = chain.render(new Matrix4f(), true, mode);
            assertEquals(-.1f, withOffset.m31(), 1e-6f);
            assertEquals(0, withoutOffset.m31(), 1e-6f);
            assertFalse(withOffset.equals(withoutOffset, 1e-5f),
                    "Removing the fallback changes the body when Core is absent/disabled");
        }
    }

    @Test void productionOwnershipGateRemovesOnlyTheOffsetThatCoreAlreadyNormalizes() throws Exception {
        NativeChain chain = new NativeChain();
        Boundary.upstreamOwnsHeight = false; // Execute the legacy callback with its added offset.
        Matrix4f legacyFinal = chain.render(new Matrix4f(), true, CrouchNormalizer.Mode.ALL);
        Matrix4f legacyBeforeCore = chain.render(new Matrix4f(), true, null);
        Boundary.upstreamOwnsHeight = true;
        Matrix4f gatedFinal = chain.render(new Matrix4f(), true, CrouchNormalizer.Mode.ALL);
        Matrix4f gatedBeforeCore = chain.render(new Matrix4f(), true, null);
        assertMatrixEquals(legacyFinal, gatedFinal, "The actual new callback must preserve Core's final transform");
        assertEquals(-.1f, legacyBeforeCore.m31(), 1e-6f);
        assertEquals(0, gatedBeforeCore.m31(), 1e-6f, "The new callback must stop contributing redundant height");
        assertVector(0, .3f, 0, chain.transform("body", TransformType.ROTATION, true));
        assertVector(0, .2f, 0, chain.transform("torso", TransformType.ROTATION, true));
        Boundary.upstreamOwnsHeight = false;
        assertMatrixEquals(legacyBeforeCore, chain.render(new Matrix4f(), true, CrouchNormalizer.Mode.NONE),
                "The same compiled callback restores its fallback when upstream correction is disabled");
    }

    @Test void standingAndReleasedAttackControlsDoNotUseOurCrouchOffset() throws Exception {
        NativeChain chain = new NativeChain();
        Boundary.crouching = false;
        Boundary.yOffset = -.1;
        Matrix4f standing = chain.render(new Matrix4f(), true, null);
        Boundary.yOffset = 0;
        assertMatrixEquals(standing, chain.render(new Matrix4f(), true, null), "Standing control");
        Boundary.crouching = true;
        Boundary.weight = 0;
        Boundary.yOffset = -.1;
        Matrix4f released = chain.render(new Matrix4f(), true, null);
        Boundary.yOffset = 0;
        assertMatrixEquals(released, chain.render(new Matrix4f(), true, null), "Inactive correction control");
    }

    @Test void bridgePitchReductionDoesNotReplaceOurTotalBodyAndTorsoSuppression() throws Exception {
        NativeChain chain = new NativeChain();
        Vec3f upstreamBody = chain.transform("body", TransformType.ROTATION, false);
        assertVector(.45f, .3f, .5f, upstreamBody);
        assertVector(0, .3f, 0, chain.transform("body", TransformType.ROTATION, true));
        assertVector(.4f, .2f, .3f, chain.transform("torso", TransformType.ROTATION, false));
        assertVector(0, .2f, 0, chain.transform("torso", TransformType.ROTATION, true));

        Matrix4f upstreamOnly = chain.render(new Matrix4f(), false, CrouchNormalizer.Mode.ALL);
        Matrix4f coordinated = chain.render(new Matrix4f(), true, CrouchNormalizer.Mode.ALL);
        assertFalse(upstreamOnly.equals(coordinated, 1e-5f),
                "Core removes Y translation, not the remaining keyframe rotation and roll");
        assertEquals(0, upstreamOnly.m31(), 1e-6f);
        assertEquals(0, coordinated.m31(), 1e-6f);
    }

    @Test void bridgeDisabledAndNativeFirstPersonPassPreserveFullNativeAdjustment() throws Exception {
        NativeChain chain = new NativeChain();
        Boundary.bridgeEnabled = false;
        assertVector(1.15f, .3f, .5f, chain.transform("body", TransformType.ROTATION, false));
        Boundary.bridgeEnabled = true;
        Boundary.nativeFirstPersonPass = true;
        assertVector(1.15f, .3f, .5f, chain.transform("body", TransformType.ROTATION, false));
        Boundary.nativeFirstPersonPass = false;
        Boundary.weight = .5f;
        assertVector(.225f, .3f, .25f, chain.transform("body", TransformType.ROTATION, true));
        Boundary.weight = 0;
        assertVector(.45f, .3f, .5f, chain.transform("body", TransformType.ROTATION, true));
    }

    @Test void coreNormalisationPreservesHorizontalTranslationAndRotation() throws Exception {
        NativeChain chain = new NativeChain();
        Matrix4f raw = chain.render(new Matrix4f(), false, null);
        Matrix4f normalized = chain.render(new Matrix4f(), false, CrouchNormalizer.Mode.ALL);
        assertNotEquals(0, raw.m31(), "The native fixture must exercise vertical motion");
        assertEquals(0, normalized.m31(), 1e-6f);
        Matrix4f expected = new Matrix4f(raw).m31(0);
        assertMatrixEquals(expected, normalized, "Only the native local Y delta is normalized");
    }

    private static final class NativeChain {
        private final Object pal, core, ours;
        private final Method palTransform, coreAfter, correction, bridgeAdjustment;
        private final Field coreBefore, coreMode;

        NativeChain() throws Exception {
            Boundary.reset();
            Map<String, String> boundaries = Map.of(
                    "dev/caleb/emfattachments/client/FirstPersonCombat", internal(Boundary.class),
                    "dev/caleb/emfattachments/config/CompatConfig", internal(Boundary.class),
                    "dev/caleb/emfattachments/compat/EmfCrouchCompatibility", internal(Boundary.class),
                    "net/minecraft/client/Minecraft", internal(ClientBoundary.class),
                    "net/minecraft/client/player/LocalPlayer", internal(PlayerBoundary.class));
            Class<?> ownType = extracted(OURS, "ProductionCombat", m -> m.name.equals("emfattachments$cameraRelativeAttack"), boundaries, true);
            ours = ownType.getConstructor().newInstance();
            correction = accessible(ownType, "emfattachments$cameraRelativeAttack");

            Class<?> palType = extracted(PAL, "PlayerTransforms", m -> m.name.equals("applyBodyTransforms"),
                    Map.of("net/minecraft/client/player/AbstractClientPlayer", internal(IAnimatedPlayer.class)), true);
            pal = palType.getConstructor().newInstance();
            palTransform = accessible(palType, "applyBodyTransforms");

            Class<?> coreType = extracted(CORE, "CoreTransforms",
                    m -> m.name.equals("emfcompat$afterSetupRotations") || m.name.equals("<clinit>"), Map.of(), true);
            core = coreType.getConstructor().newInstance();
            coreAfter = accessible(coreType, "emfcompat$afterSetupRotations");
            coreBefore = field(coreType, "EMFCOMPAT$BEFORE");
            coreMode = field(coreType, "emfcompat$normalise");

            Class<?> bridgeType = extracted(BRIDGE, "BridgeAdjustment", m -> true,
                    Map.of("strm/emfcompat/bettercombat/EMFCompatBetterCombatMod", internal(Boundary.class),
                            "dev/kosmx/playerAnim/api/firstPerson/FirstPersonMode", internal(Boundary.class)), false);
            bridgeAdjustment = accessible(bridgeType, "emfcompat$reduceGlobalAttackAdjustment");
        }

        private IAnimation animation() throws Exception {
            IAnimation keyframes = new IAnimation() {
                @Override public boolean isActive() { return true; }
                @Override public void setupAnim(float delta) {}
                @Override public Vec3f get3DTransform(String part, TransformType type, float delta, Vec3f initial) {
                    return switch (type) {
                        case ROTATION -> new Vec3f(.4f, .2f, .3f);
                        case POSITION -> new Vec3f(.12f, .25f, -.16f);
                        case SCALE -> new Vec3f(1, 1, 1);
                        default -> initial;
                    };
                }
            };
            var original = new AdjustmentModifier(part -> part.equals("body")
                    ? Optional.of(new AdjustmentModifier.PartModifier(new Vec3f(.75f, .1f, .2f), Vec3f.ZERO))
                    : Optional.empty());
            var adjusted = (AdjustmentModifier) bridgeAdjustment.invoke(null, original);
            adjusted.setAnim(keyframes);
            return adjusted;
        }

        Vec3f transform(String part, TransformType type, boolean applyCorrection) throws Exception {
            var applier = new AnimationApplier(animation());
            Vec3f upstream = applier.get3DTransform(part, type, Vec3f.ZERO);
            return applyCorrection ? corrected(part, type, Vec3f.ZERO, upstream) : upstream;
        }

        private Vec3f corrected(String part, TransformType type, Vec3f initial, Vec3f upstream) {
            try {
                var result = new CallbackInfoReturnable<Vec3f>("get3DTransform", true, upstream);
                correction.invoke(ours, part, type, initial, result);
                return result.getReturnValue();
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Installed production correction could not execute", error);
            }
        }

        Matrix4f render(Matrix4f before, boolean applyCorrection, CrouchNormalizer.Mode mode) throws Exception {
            var applier = new AnimationApplier(animation()) {
                @Override public Vec3f get3DTransform(String part, TransformType type, Vec3f initial) {
                    Vec3f upstream = super.get3DTransform(part, type, initial);
                    return applyCorrection ? corrected(part, type, initial, upstream) : upstream;
                }
            };
            IAnimatedPlayer entity = (IAnimatedPlayer) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IAnimatedPlayer.class}, (proxy, method, args) -> {
                        if (method.getName().equals("playerAnimator_getAnimation") && method.getParameterCount() == 0) return applier;
                        throw new AssertionError("Unexpected entity boundary: " + method);
                    });
            var poses = new PoseStack();
            poses.last().pose().set(before);
            palTransform.invoke(pal, entity, poses, 0F, 0F, .5F, 1F, new CallbackInfo("setupRotations", false));
            if (mode != null) {
                ((Matrix4f) coreBefore.get(null)).set(before);
                coreMode.set(null, mode);
                coreAfter.invoke(core, null, 0F, .5F, poses, null, 0, new CallbackInfo("render", false));
                assertEquals(CrouchNormalizer.Mode.NONE, coreMode.get(null), "Native hook resets its decision after use");
            }
            return new Matrix4f(poses.last().pose());
        }
    }

    /** Boundary replacements deliberately contain no transform math. */
    public static final class Boundary {
        static boolean bridgeEnabled, nativeFirstPersonPass, crouching, upstreamOwnsHeight;
        static float weight;
        static double yOffset;
        static void reset() {
            bridgeEnabled = true; nativeFirstPersonPass = false; crouching = true;
            upstreamOwnsHeight = false; weight = 1; yOffset = -.1;
        }
        public static boolean isEnabled() { return bridgeEnabled; }
        public static boolean ownsCrouchHeight() { return upstreamOwnsHeight; }
        public static boolean isFirstPersonPass() { return nativeFirstPersonPass; }
        public static boolean modifiesBodyProcessor(Object ignored) { return true; }
        public static float correctionWeight(String ignored, TransformType type, float delta) { return weight; }
        public static double combatBodyYOffset() { return yOffset; }
        public static double armPitchMultiplier() { return .7; }
        public static double armPitchOffset() { return .15; }
        public static double armPositionX() { return 0; }
        public static double armPositionY() { return 0; }
        public static double armPositionZ() { return 0; }
        public static double lookUpZShift() { return 0; }
    }

    public static final class ClientBoundary {
        private static final ClientBoundary INSTANCE = new ClientBoundary();
        public PlayerBoundary player = new PlayerBoundary();
        public static ClientBoundary getInstance() { return INSTANCE; }
    }

    public static final class PlayerBoundary {
        public boolean isCrouching() { return Boundary.crouching; }
        public float getXRot() { return 30; }
    }

    private static Class<?> extracted(String source, String suffix, Predicate<MethodNode> keep,
                                      Map<String, String> replacements, boolean plainObject) throws Exception {
        ClassNode node = classNode(source);
        String target = "dev/caleb/emfattachments/compat/generated/" + suffix;
        node.methods.removeIf(m -> !keep.test(m));
        if (plainObject) {
            node.superName = "java/lang/Object";
            node.access = Opcodes.ACC_PUBLIC;
            node.signature = null;
            node.interfaces.clear();
            MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
            constructor.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(constructor);
        }
        Map<String, String> names = new HashMap<>(replacements);
        names.put(source, target);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new Remapper() {
            @Override public String map(String name) { return names.getOrDefault(name, name); }
        }));
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(EmfCombatOverlapTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(target.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
    }

    private static ClassNode classNode(String name) throws Exception {
        try (InputStream bytes = EmfCombatOverlapTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(bytes, "Installed contract missing: " + name);
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static Method accessible(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) if (method.getName().equals(name)) {
            method.setAccessible(true);
            return method;
        }
        throw new AssertionError(type + "." + name);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static AnnotationNode inject(MethodNode method) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
                .findFirst().orElseThrow();
    }

    private static AnnotationNode at(AnnotationNode injection) {
        return (AnnotationNode) ((List<?>) value(injection, "at")).getFirst();
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }

    private static void assertVector(float x, float y, float z, Vec3f actual) {
        assertArrayEquals(new float[]{x, y, z}, new float[]{actual.getX(), actual.getY(), actual.getZ()}, 1e-6f);
    }

    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual, String message) {
        assertArrayEquals(expected.get(new float[16]), actual.get(new float[16]), 2e-6f, message);
    }
}
