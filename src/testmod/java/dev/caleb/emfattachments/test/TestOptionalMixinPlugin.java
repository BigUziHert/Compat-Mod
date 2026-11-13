package dev.caleb.emfattachments.test;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Keep opt-in diagnostics inert when their optional renderer is absent. */
public final class TestOptionalMixinPlugin implements IMixinConfigPlugin {
    private static boolean loaded(String id) {
        return FMLLoader.getLoadingModList().getModFileById(id) != null;
    }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (Boolean.getBoolean("emfattachments.sableAuditOnly")
                && !Boolean.getBoolean("emfattachments.artificeOnly")) {
            return mixin.endsWith(".NativeWorldReadinessMixin")
                    || mixin.contains("SableOcclusion") && loaded("sable") && loaded("entityculling");
        }
        if (mixin.contains("OverlapArtifice")) return Boolean.getBoolean("emfattachments.overlapGunBypass")
                && loaded("emfattachments") && loaded("irons_artifice");
        if (mixin.contains("Overlap")) return Boolean.getBoolean("emfattachments.overlapAudit")
                && loaded("emfattachments") && loaded("firstperson") && loaded("bettercombat")
                && loaded("playeranimator") && loaded("emf_compat_core") && loaded("entity_model_features");
        if (Boolean.getBoolean("emfattachments.artificeOnly") && !mixin.contains("Artifice")
                && !mixin.contains("NativeHandAccessory")) return mixin.endsWith(".NativeWorldReadinessMixin");
        if (mixin.contains("ArtificeBodyMask")) return loaded("emfattachments") && loaded("firstperson") && loaded("irons_artifice");
        if (mixin.contains("ArtificeBodyGeometry")) return loaded("emfattachments") && loaded("firstperson") && loaded("irons_artifice")
                && (!mixin.contains("Emf")||loaded("entity_model_features"));
        if (mixin.contains("ArtificeEmf")) return loaded("irons_artifice") && loaded("entity_model_features");
        if (mixin.contains("ArtificeRagdoll")) return loaded("sable_player_ragdoll") && loaded("accessories") && loaded("curios");
        if (mixin.contains("ArtificeWearable")) return loaded("accessories");
        if (mixin.contains("Artifice")) return loaded("irons_artifice");
        if (mixin.contains("SableSleep")) return loaded("sable") && loaded("artifacts") && loaded("accessories");
        if (mixin.contains("NativeAetherHand")) return loaded("aether") && loaded("accessories") && loaded("entity_model_features");
        if (mixin.contains("NativeHands")) return loaded("entity_model_features");
        if (mixin.contains("SableOcclusion")) return loaded("sable") && loaded("entityculling");
        if (mixin.contains("SableDeck")) return loaded("sable") && loaded("entity_model_features");
        if (mixin.contains("NativeHandAccessory")) return loaded("artifacts") && loaded("accessories") && loaded("entity_model_features");
        if (mixin.contains("RevivalAccessoriesHelper")) return loaded("sable_player_ragdoll") && loaded("accessories");
        if (mixin.contains("RevivalCuriosHelper")) return loaded("sable_player_ragdoll") && loaded("curios");
        if (mixin.contains("Necklace")) return loaded("artifacts");
        if (mixin.endsWith(".NeaNativeArmorPoseProbeMixin") || mixin.contains(".ArmorMesh")) return loaded("notenoughanimations")
                && loaded("entity_model_features") && loaded("emf_compat_core")
                && loaded("emf_compat_not_enough_animations");
        if (mixin.contains("BowVisibility") || mixin.contains("BowHeadAnchor")) return loaded("firstperson")
                && loaded("notenoughanimations") && loaded("entity_model_features")
                && loaded("emf_compat_core") && loaded("emf_compat_not_enough_animations")
                && loaded("bettercombat") && loaded("emfattachments");
        return true;
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
