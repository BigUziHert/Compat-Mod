package dev.caleb.emfattachments.compat;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.neoforged.fml.loading.FMLLoader;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Checks discovered mod metadata without loading optional classes during mixin discovery. */
public final class OptionalMixinPlugin implements IMixinConfigPlugin {
    private final Predicate<String> discoveredMods;
    private final BiPredicate<String, String> versionAtLeast;

    public OptionalMixinPlugin() {
        this(OptionalMixinPlugin::discoveredModPresent, OptionalMixinPlugin::discoveredVersionAtLeast);
    }

    OptionalMixinPlugin(Predicate<String> discoveredMods) {
        this(discoveredMods, (mod, minimum) -> true);
    }

    OptionalMixinPlugin(Predicate<String> discoveredMods, BiPredicate<String, String> versionAtLeast) {
        this.discoveredMods = discoveredMods;
        this.versionAtLeast = versionAtLeast;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (Boolean.getBoolean("emfattachments.disable")) {
            return false;
        }
        if (mixinClassName.endsWith(".AetherCapeLookupMixin")) {
            return modPresent("aether") && modPresent("accessories")
                    && !Boolean.getBoolean("emfattachments.aetherParallelCapeLookup");
        }
        if (mixinClassName.endsWith(".RagdollHeadwearRoutingMixin")
                || mixinClassName.endsWith(".RagdollCuriosHeadwearRoutingMixin")) {
            return modPresent("sable_player_ragdoll") && modPresent("accessories")
                    && modPresent("curios") && modPresent("accessories_compat_layer")
                    && versionAtLeast.test("sable_player_ragdoll", "0.7.5");
        }
        if (mixinClassName.endsWith(".ArtificeFirstPersonAccessoriesMixin")) {
            return modPresent("irons_artifice") && modPresent("accessories");
        }
        if (mixinClassName.endsWith(".ArtificeSixShooterGunHandMixin")
                || mixinClassName.endsWith(".ArtificeSixShooterOffhandMixin")) {
            return modPresent("irons_artifice");
        }
        if (mixinClassName.endsWith(".ArtificeFirstPersonHandsMixin")
                || mixinClassName.endsWith(".ArtificeFirstPersonFreeArmMixin")) {
            return modPresent("irons_artifice") && modPresent("firstperson");
        }
        if (mixinClassName.endsWith(".ArtificeEmfGunPoseMixin")) {
            return modPresent("irons_artifice") && modPresent("entity_model_features");
        }
        if (mixinClassName.endsWith(".ArtificeSixShooterReloadMixin")) {
            return modPresent("irons_artifice");
        }
        if (mixinClassName.endsWith(".ArtificeArtifactsFirstPersonPoseMixin")) {
            return modPresent("irons_artifice") && modPresent("accessories") && modPresent("artifacts");
        }
        if (mixinClassName.endsWith(".ArtificeSkyjadeFirstPersonPoseMixin")) {
            return modPresent("irons_artifice") && modPresent("accessories")
                    && modPresent("aether") && modPresent("deep_aether");
        }
        if (mixinClassName.endsWith(".SablePlayerEntityCullingMixin")) {
            return modPresent("sable") && modPresent("entityculling");
        }
        if (mixinClassName.endsWith(".SableReducedTickAccessMixin")
                || mixinClassName.endsWith(".SablePlayerReducedTickMixin")) {
            return modPresent("sable") && modPresent("entityculling")
                    && SableReducedTickLinker.supported(versionAtLeast);
        }
        if (mixinClassName.endsWith(".SableSleepingRotationMixin")
                || mixinClassName.endsWith(".SableSleepingNameTagMixin")) {
            return modPresent("sable");
        }
        if (mixinClassName.endsWith(".SableFreshAnimationMixin")
                || mixinClassName.endsWith(".SableFreshAnimationPlayerMixin")
                || mixinClassName.endsWith(".SableFreshAsmEvaluationMixin")
                || mixinClassName.endsWith(".SableFreshInterpreterEvaluationMixin")) {
            // Core 2.4+ owns deck motion; these evaluation/context hooks still
            // protect Fresh against a one-float rounding step in Core's input.
            return modPresent("sable") && modPresent("entity_model_features");
        }
        if (mixinClassName.endsWith(".FirstPersonArmScopeMixin")
                || mixinClassName.endsWith(".FirstPersonArmPartMixin")
                || mixinClassName.endsWith(".FirstPersonHeldItemGeometryMixin")
                || mixinClassName.endsWith(".FirstPersonEmfArmPartMixin")
                || mixinClassName.endsWith(".FirstPersonDefaultAccessoryArmMixin")) {
            return modPresent("firstperson") && (modPresent("irons_artifice")
                    || modPresent("bettercombat") && modPresent("playeranimator"))
                    && (!mixinClassName.endsWith(".FirstPersonEmfArmPartMixin") || modPresent("entity_model_features"))
                    && (!mixinClassName.endsWith(".FirstPersonDefaultAccessoryArmMixin") || modPresent("accessories"));
        }
        if (mixinClassName.endsWith(".BetterCombatZeroAxisMixin")) {
            return modPresent("bettercombat");
        }
        if (mixinClassName.endsWith(".ArtifactsNecklacePoseMixin")) return modPresent("artifacts");
        if (mixinClassName.endsWith(".AetherNativeAttackGlovesMixin")
                || mixinClassName.endsWith(".AetherNativeAttackGloveVisibilityMixin")) {
            return !modPresent("firstperson") && modPresent("aether") && modPresent("accessories")
                    && modPresent("playeranimator") && modPresent("bettercombat")
                    && modPresent("entity_model_features");
        }
        if (mixinClassName.endsWith(".ArtifactsFirstPersonArmSourceMixin")
                || mixinClassName.endsWith(".ArtifactsFirstPersonGlovePoseMixin")) {
            return modPresent("artifacts") && modPresent("accessories") && modPresent("entity_model_features");
        }
        if (mixinClassName.endsWith(".RagdollArtifactArmMixin") || mixinClassName.endsWith(".RagdollCataclysmArmMixin")) {
            return modPresent("sable_player_ragdoll") && modPresent("accessories") && modPresent("curios")
                    && modPresent("accessories_compat_layer")
                    && modPresent(mixinClassName.endsWith(".RagdollArtifactArmMixin") ? "artifacts" : "cataclysm");
        }
        if (mixinClassName.endsWith(".RagdollAetherArmsMixin")) {
            return modPresent("sable_player_ragdoll") && modPresent("accessories") && modPresent("curios")
                    && modPresent("accessories_compat_layer") && modPresent("aether");
        }
        if (mixinClassName.endsWith(".TidalHookStateAccessor")
                || mixinClassName.endsWith(".TidalGrappleImpactMixin")) {
            return modPresent("cataclysm") && modPresent("ragdoll_reactions");
        }
        if (mixinClassName.endsWith(".FirstPersonIrisRenderGroupMixin")) {
            return modPresent("firstperson") && modPresent("iris");
        }
        if (mixinClassName.endsWith(".NeaBowPoseMixin")) {
            return modPresent("emf_compat_not_enough_animations") && modPresent("emf_compat_core")
                    && modPresent("notenoughanimations") && modPresent("entity_model_features");
        }
        if (mixinClassName.endsWith(".FirstPersonNeaBowPoseMixin")
                || mixinClassName.endsWith(".FirstPersonNeaCrossbowPoseMixin")) {
            return modPresent("firstperson") && modPresent("notenoughanimations")
                    && modPresent("entity_model_features") && modPresent("emf_compat_core")
                    && modPresent("emf_compat_not_enough_animations");
        }
        if (mixinClassName.endsWith(".FirstPersonPoseInterpolatorMixin")) {
            return modPresent("firstperson") && modPresent("emf_compat_core")
                    && modPresent("entity_model_features");
        }
        if (mixinClassName.endsWith(".RagdollBlockOverlayOriginMixin")) {
            return modPresent("sable_player_ragdoll");
        }
        if (mixinClassName.endsWith(".RagdollAccessoryScopeMixin")
                || mixinClassName.endsWith(".RagdollAccessoriesDedupMixin")
                || mixinClassName.endsWith(".RagdollCuriosDedupMixin")) {
            return modPresent("sable_player_ragdoll") && modPresent("accessories")
                    && modPresent("curios") && modPresent("accessories_compat_layer");
        }
        if (mixinClassName.endsWith(".WardenHelmetMixin")) {
            return modPresent("deeperdarker");
        }
        if (mixinClassName.endsWith(".FirstPersonAetherShieldMixin")) {
            return modPresent("firstperson") && modPresent("accessories") && modPresent("aether");
        }
        if (mixinClassName.endsWith(".FirstPersonWindShieldMixin")) {
            return modPresent("firstperson") && modPresent("accessories") && modPresent("deep_aether");
        }
        if (mixinClassName.endsWith(".FirstPersonEmfPartMixin")) {
            return modPresent("firstperson") && modPresent("entity_model_features");
        }
        if (mixinClassName.endsWith(".FirstPersonCosmeticArmorMixin")) {
            return modPresent("firstperson") && modPresent("accessories");
        }
        if (mixinClassName.endsWith(".FirstPersonAccessoriesMixin")) {
            return modPresent("accessories");
        }
        if (mixinClassName.endsWith(".FirstPersonCuriosMixin")) {
            return modPresent("curios");
        }
        if (mixinClassName.endsWith(".SleepingBodyRenderScopeMixin")) {
            return modPresent("accessories") || modPresent("curios");
        }
        if (mixinClassName.endsWith(".FirstPersonScaleOffsetMixin")) {
            return modPresent("firstperson");
        }
        if (mixinClassName.endsWith(".FirstPersonHeadSlotScopeMixin")) {
            return modPresent("firstperson");
        }
        if (mixinClassName.endsWith(".BetterCombatAttackAccessMixin")
                || mixinClassName.endsWith(".FirstPersonCombatLogicMixin")
                || mixinClassName.endsWith(".FirstPersonCombatAnimationMixin")
                || mixinClassName.endsWith(".AnimationLayerAccessor")
                || mixinClassName.endsWith(".AnimationFadeAccessor")
                || mixinClassName.endsWith(".SpeedModifierAccessor")) {
            return modPresent("firstperson") && modPresent("bettercombat") && modPresent("playeranimator");
        }
        if (mixinClassName.endsWith(".FlowerHeadwearRegistryMixin")) {
            return modPresent("the_bumblezone") && modPresent("accessories");
        }
        if (targetClassName.startsWith("com.titammods.aeronautics_curios_compat.")) {
            return modPresent("aeronautics_curios_compat") && modPresent("curios");
        }
        if (targetClassName.startsWith("com.faboslav.friendsandfoes.")) {
            return modPresent("friendsandfoes") && modPresent("curios");
        }
        if (targetClassName.startsWith("io.github.razordevs.deep_aether.")) {
            return modPresent("deep_aether") && modPresent("accessories");
        }
        return true;
    }

    private boolean modPresent(String modId) {
        return discoveredMods.test(modId);
    }

    private static boolean discoveredModPresent(String modId) {
        // ModLauncher cannot supply untransformed class nodes here. Reading its
        // completed discovery metadata avoids both that unsupported API and
        // recursive transformation of the very class to which this mixin applies.
        var loadingMods = FMLLoader.getLoadingModList();
        return loadingMods != null && loadingMods.getModFileById(modId) != null;
    }

    private static boolean discoveredVersionAtLeast(String modId, String minimum) {
        var loadingMods = FMLLoader.getLoadingModList();
        var file = loadingMods == null ? null : loadingMods.getModFileById(modId);
        return file != null && file.getMods().stream().anyMatch(mod -> mod.getModId().equals(modId)
                && mod.getVersion().compareTo(new DefaultArtifactVersion(minimum)) >= 0);
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                                   IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                                    IMixinInfo mixinInfo) {
        if (mixinClassName.endsWith(".SableReducedTickAccessMixin")) {
            var logger = org.slf4j.LoggerFactory.getLogger("emfattachments");
            if (SableReducedTickLinker.link(targetClass)) {
                logger.info("Linked native Sable reduced-tick maintenance; full-tick control: emfattachments.sableFullTicks");
            } else {
                logger.warn("Could not link native Sable reduced-tick maintenance; preserving full player ticks");
            }
        }
    }
}
