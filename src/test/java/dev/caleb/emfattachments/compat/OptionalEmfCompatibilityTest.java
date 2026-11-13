package dev.caleb.emfattachments.compat;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises discovery without loading Minecraft, EMF, ETF or their bridge classes. */
class OptionalEmfCompatibilityTest {
    private static final String OWN_PACKAGE = "dev/caleb/emfattachments/";
    private static final String EMF = "entity_model_features";
    private static final String ETF = "entity_texture_features";
    // These support an EMF integration without directly linking its API.
    private static final Set<String> EMF_SUPPORT_MIXINS = Set.of("SableFreshAnimationPlayerMixin",
            "FirstPersonNeaBowPoseMixin", "FirstPersonNeaCrossbowPoseMixin");
    private final Map<String, Set<String>> references = new HashMap<>();

    @Test void metadataAllowsBothModsToBeAbsent() throws IOException {
        String metadata = new String(resource("META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        String emfDependency = null;
        for (String block : metadata.split(Pattern.quote("[[dependencies.emfattachments]]"))) {
            if (hasSetting(block, "modId", EMF)) emfDependency = block;
            if (hasSetting(block, "modId", ETF)) {
                assertTrue(hasSetting(block, "type", "optional"), "ETF must not become mandatory");
            }
        }
        assertNotNull(emfDependency, "Keep the supported EMF version constraint when installed");
        assertTrue(hasSetting(emfDependency, "type", "optional"));
        assertTrue(hasSetting(emfDependency, "versionRange", "[3.3.12,)"));
    }

    @Test void everyEmfDependentMixinIsSkippedWithoutEmfAndAvailableWithIt() throws IOException {
        Set<String> covered = new LinkedHashSet<>();
        for (String mixin : registeredMixins()) {
            String shortName = mixin.substring(mixin.lastIndexOf('/') + 1);
            if (emfDependencyPath(mixin, new HashSet<>()) == null && !EMF_SUPPORT_MIXINS.contains(shortName)) continue;
            covered.add(shortName);
            // These hooks intentionally serve native hands only when FirstPerson is absent.
            boolean nativeHands = mixin.contains("/AetherNativeAttackGlove");
            Predicate<String> withEmf = mod -> !ETF.equals(mod) && !(nativeHands && "firstperson".equals(mod));
            Predicate<String> withoutEmf = mod -> withEmf.test(mod) && !EMF.equals(mod);
            assertFalse(applies(mixin, withoutEmf), mixin + " requires EMF classes");
            assertTrue(applies(mixin, withEmf), mixin + " should remain available with EMF installed");
        }
        assertTrue(covered.containsAll(Set.of("SableFreshAnimationMixin", "SableFreshAnimationPlayerMixin", "SableFreshAsmEvaluationMixin",
                "SableFreshInterpreterEvaluationMixin", "FirstPersonEmfPartMixin", "FirstPersonEmfArmPartMixin",
                "ArtifactsFirstPersonArmSourceMixin", "ArtifactsFirstPersonGlovePoseMixin",
                "AetherNativeAttackGlovesMixin", "AetherNativeAttackGloveVisibilityMixin", "NeaBowPoseMixin",
                "FirstPersonNeaBowPoseMixin", "FirstPersonNeaCrossbowPoseMixin", "FirstPersonPoseInterpolatorMixin")),
                "The class-reference audit must cover the existing EMF integrations: " + covered);
    }

    @Test void independentFixesStayActiveAndCannotReachEmfClassesWhenBothModsAreAbsent() throws IOException {
        assertNull(emfDependencyPath(OWN_PACKAGE + "EmfAttachments", new HashSet<>()),
                "The entrypoint must initialize without EMF or ETF");
        for (boolean firstPersonPresent : List.of(false, true)) {
            Predicate<String> noEmfOrEtf = mod -> !EMF.equals(mod) && !ETF.equals(mod)
                    && (firstPersonPresent || !"firstperson".equals(mod));
            Set<String> active = new LinkedHashSet<>();
            for (String mixin : registeredMixins()) {
                if (!applies(mixin, noEmfOrEtf)) continue;
                active.add(mixin.substring(mixin.lastIndexOf('/') + 1));
                assertNull(emfDependencyPath(mixin, new HashSet<>()),
                        "An enabled mixin or its own helpers must not link EMF/ETF classes: " + mixin);
            }
            assertTrue(active.containsAll(Set.of("SablePlayerEntityCullingMixin", "SableSleepingRotationMixin",
                    "SableSleepingNameTagMixin", "BetterCombatZeroAxisMixin", "FirstPersonAccessoriesMixin",
                    "RagdollAccessoriesDedupMixin", "TidalGrappleImpactMixin", "ArtifactsNecklacePoseMixin",
                    "WardenHelmetMixin")), "Independent fixes must remain enabled: " + active);
            if (firstPersonPresent) {
                assertTrue(active.containsAll(Set.of("FirstPersonScaleOffsetMixin", "FirstPersonCombatAnimationMixin",
                        "FirstPersonCombatLogicMixin", "FirstPersonArmScopeMixin")),
                        "FirstPerson's native fixes must remain enabled: " + active);
            } else {
                assertFalse(active.contains("FirstPersonScaleOffsetMixin"));
                assertFalse(active.contains("AetherNativeAttackGlovesMixin"));
            }
        }
    }

    private boolean applies(String mixin, Predicate<String> installed) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(resource(mixin + ".class")).accept(node, ClassReader.SKIP_CODE);
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
        for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                String key = (String) annotation.values.get(i);
                if (!key.equals("value") && !key.equals("targets")) continue;
                for (Object target : (List<?>) annotation.values.get(i + 1)) {
                    String targetName = target instanceof Type type ? type.getClassName() : (String) target;
                    // The Fresh hooks are available with either height source; the runtime
                    // helper selects legacy deck motion or upstream precision protection.
                    return new OptionalMixinPlugin(installed, (mod, minimum) -> !mod.equals("emf_compat_core")
                            || new DefaultArtifactVersion("2.3.2").compareTo(new DefaultArtifactVersion(minimum)) >= 0)
                            .shouldApplyMixin(targetName, mixin.replace('/', '.'));
                }
            }
        }
        throw new AssertionError("No mixin target found: " + mixin);
    }

    private List<String> registeredMixins() throws IOException {
        var config = JsonParser.parseString(new String(resource("emfattachments.mixins.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<String> mixins = new ArrayList<>();
        for (String section : List.of("client", "mixins")) {
            for (var name : config.getAsJsonArray(section)) {
                mixins.add(config.get("package").getAsString().replace('.', '/') + "/" + name.getAsString());
            }
        }
        return mixins;
    }

    private String emfDependencyPath(String name, Set<String> visited) throws IOException {
        // These optional calls are guarded by discovered Core version before
        // their nested classes are linked. The matching compatibility tests
        // exercise the production guards while forbidding absent/older Core APIs.
        if (name.equals(OWN_PACKAGE + "compat/EmfCrouchCompatibility$Core")
                || name.equals(OWN_PACKAGE + "compat/EmfSableCompatibility$Core")) return null;
        if (name.startsWith("traben/entity_model_features/") || name.startsWith("traben/entity_texture_features/")
                || name.startsWith("strm/emfcompat/") || name.startsWith("strm/neaemfcompat/")) return name;
        if (!name.startsWith(OWN_PACKAGE) || !visited.add(name)) return null;
        if (!references.containsKey(name)) {
            Set<String> dependencies = new LinkedHashSet<>();
            new ClassReader(resource(name + ".class")).accept(new ClassRemapper(new ClassNode(),
                    new Remapper() {
                        @Override public String map(String internalName) {
                            dependencies.add(internalName);
                            return internalName;
                        }
                    }), 0);
            references.put(name, dependencies);
        }
        for (String dependency : references.get(name)) {
            String path = emfDependencyPath(dependency, visited);
            if (path != null) return name + " -> " + path;
        }
        return null;
    }

    private static boolean hasSetting(String block, String key, String value) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*\"" + Pattern.quote(value) + "\"\\s*$")
                .matcher(block).find();
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream stream = OptionalEmfCompatibilityTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return stream.readAllBytes();
        }
    }
}
