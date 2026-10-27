package dev.caleb.emfattachments.test;

import net.minecraft.world.entity.Entity;
import traben.entity_model_features.models.animation.state.EMFEntityRenderState;
import traben.entity_model_features.models.animation.state.EMFState;
import traben.entity_texture_features.features.state.ETFEntityRenderState;
import traben.entity_texture_features.features.state.ETFState;
import traben.entity_texture_features.utils.ETFEntity;

/** Balanced genuine render-state mounts; never clears the caller's state stack. */
public final class EmfProbeContext implements AutoCloseable {
    private final int depth = ETFState.currentModelPartDepth;
    private final boolean features = ETFState.isRenderingFeatures;
    private final boolean onlyProperties = ETFState.onlyRandomizeViaPropertiesFiles;
    private final boolean patching = ETFState.allowTexturePatching;
    private final boolean layer = EMFState.isLayerPhase, main = EMFState.isMainPhase;
    private final boolean variationVisibility = EMFState.modelVariationIgnoresVisibility;
    private final boolean handLayer = EMFState.isInHandItemLayerTransform;
    private final Boolean left = EMFState.isInLeftHand, arm = EMFState.hasDoneArmOverride;
    private final EMFState.EMFStateStaticSnapshot statics = EMFState.captureStatics();
    private final ETFEntityRenderState prior = ETFState.state();
    private final boolean entityMounted;

    public EmfProbeContext(Entity entity) {
        ETFEntityRenderState state = entity == null ? null : ETFEntityRenderState.forEntity((ETFEntity) (Object) entity);
        if (state != null && !(state instanceof EMFEntityRenderState)) throw new AssertionError("EMF state factory is not active");
        // mount(state) deliberately does nothing when state is already current.
        // An explicit null frame ensures our two mounts always have two matching pops.
        ETFState.mountNone();
        entityMounted = state != null;
        if (entityMounted) ETFState.mount(state);
        ETFState.currentModelPartDepth = 1;
    }

    @Override public void close() {
        if (entityMounted) ETFState.unMount();
        ETFState.unMount();
        statics.restoreStatics();
        EMFState.isLayerPhase = layer; EMFState.isMainPhase = main;
        EMFState.modelVariationIgnoresVisibility = variationVisibility;
        EMFState.isInHandItemLayerTransform = handLayer;
        EMFState.isInLeftHand = left; EMFState.hasDoneArmOverride = arm;
        ETFState.currentModelPartDepth = depth; ETFState.isRenderingFeatures = features;
        ETFState.onlyRandomizeViaPropertiesFiles = onlyProperties; ETFState.allowTexturePatching = patching;
        if (ETFState.state() != prior) throw new AssertionError("Probe leaked the ETF/EMF render-state stack");
    }
}
