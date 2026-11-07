package dev.caleb.emfattachments.client;

import dev.caleb.emfattachments.compat.BetterCombatAttackAccess;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

/** Preserve the selected first-person renderer's arm flags without moving hands/items. */
public final class FirstPersonArmGeometry {
    private static final ScopedGeometryMask<ModelPart> MASK = new ScopedGeometryMask<>();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final Scope INACTIVE = new Scope(null, null, null, false);
    private FirstPersonArmGeometry() {}

    public static Scope open(LivingEntity entity, EntityModel<?> model, float partial) {
        int hiddenArms = 0;
        int artificeArms = 0;
        if (model instanceof HumanoidModel<?> && FirstPersonRenderPolicy.isLocalBody(entity)) {
            if (OptionalIntegrations.ARTIFICE) artificeArms = ArtificeFirstPersonBodyArms.nativeArmMask(entity);
            if (artificeArms != 0) {
                hiddenArms = artificeArms;
            } else if (OptionalIntegrations.BETTER_COMBAT) {
                hiddenArms = CombatArms.hiddenArms(entity, partial);
            }
        }
        // Held items have their own native selection. Mask only body/worn geometry;
        // neither shadow renders nor the separate Iron hand pass owns this scope.
        boolean owned = hiddenArms != 0;
        Scope scope = openScope(entity, owned);
        try {
            if (owned) seedArms((HumanoidModel<?>) model, hiddenArms);
            if (artificeArms != 0) scope.keepFreeArm((HumanoidModel<?>) model, artificeArms);
            return scope;
        } catch (RuntimeException | Error failure) {
            scope.close();
            throw failure;
        }
    }

    /** Bit 1 is the right arm; bit 2 is the left arm. Include every copied child/sleeve. */
    static void seedArms(HumanoidModel<?> model, int hiddenArms) {
        if ((hiddenArms & 1) != 0) seedTree(model.rightArm);
        if ((hiddenArms & 2) != 0) seedTree(model.leftArm);
        if (model instanceof PlayerModel<?> player) {
            if ((hiddenArms & 1) != 0) seedTree(player.rightSleeve);
            if ((hiddenArms & 2) != 0) seedTree(player.leftSleeve);
        }
    }

    // Resolve optional APIs only on a live render, not when scope-only tests load
    // this class. An Iron-only installation must never link Better Combat's stack.
    private static final class OptionalIntegrations {
        static final boolean ARTIFICE = ModList.get().isLoaded("irons_artifice");
        static final boolean BETTER_COMBAT = ModList.get().isLoaded("bettercombat")
                && ModList.get().isLoaded("playeranimator");
    }

    private static final class CombatArms {
        static int hiddenArms(LivingEntity entity, float partial) {
            if (!(entity instanceof BetterCombatAttackAccess access) || !FirstPersonCombat.ownsCombatRendering()) return 0;
            var visibility = NativeAttackArmVisibility.from(access.emfattachments$attackStack(), partial);
            return visibility == null ? 0 : (visibility.rightArm() ? 0 : 1) | (visibility.leftArm() ? 0 : 2);
        }
    }

    private static void seedTree(ModelPart part) { part.getAllParts().forEach(MASK::seed); }

    /** Establishes the boundary independently of Minecraft's animation selection. */
    static Scope openScope(LivingEntity entity, boolean owned) {
        Scope parent = CURRENT.get();
        if (!owned && (parent == null || !parent.owned)) return INACTIVE;
        Scope scope = new Scope(parent, entity, MASK.open(owned), owned);
        CURRENT.set(scope); // A nonlocal child must suspend an active outer mask.
        return scope;
    }

    /** A weapon's own model may copy a hand part; never treat it as worn arm geometry. */
    public static Scope suspendForHeldItems() {
        return openScope(null, false);
    }

    public static boolean hides(ModelPart part) {
        Scope scope = CURRENT.get();
        return scope != null && scope.owned && MASK.contains(part)
                && FirstPersonRenderPolicy.isLocalBody(scope.entity);
    }

    /** Exempt only the free pistol arm/sleeve from FirstPerson's native hide call. */
    public static boolean keepsNativeBodyArm(Object part) {
        Scope scope = CURRENT.get();
        return scope != null && scope.owned && scope.keeps(part)
                && FirstPersonRenderPolicy.isLocalBody(scope.entity);
    }

    public static void copied(ModelPart source, ModelPart target) {
        Scope scope = CURRENT.get();
        if (scope == null || !scope.owned || !FirstPersonRenderPolicy.isLocalBody(scope.entity)) return;
        boolean masked = MASK.contains(source);
        target.getAllParts().forEach(part -> MASK.copied(part, masked));
    }

    /** Exact native DefaultAccessoryRenderer helpers; never guess from an item ID. */
    public static boolean hidesDefaultAnchor(String slotName, int index, HumanoidModel<?> model) {
        if (!slotName.equals("hand") && !slotName.equals("wrist") && !slotName.equals("ring")) return false;
        return hides(index % 2 == 0 ? model.rightArm : model.leftArm);
    }

    public static final class Scope implements AutoCloseable {
        private final Scope parent;
        private final LivingEntity entity;
        private final ScopedGeometryMask<ModelPart>.Scope mask;
        private final boolean owned;
        private ModelPart freeArm, freeSleeve;
        private boolean closed;
        private Scope(Scope parent, LivingEntity entity, ScopedGeometryMask<ModelPart>.Scope mask, boolean owned) {
            this.parent = parent; this.entity = entity; this.mask = mask; this.owned = owned;
        }
        void keepFreeArm(HumanoidModel<?> model, int hiddenArms) {
            if (hiddenArms != 1 && hiddenArms != 2) return;
            freeArm = hiddenArms == 1 ? model.leftArm : model.rightArm;
            if (model instanceof PlayerModel<?> player) {
                freeSleeve = hiddenArms == 1 ? player.leftSleeve : player.rightSleeve;
            }
        }
        boolean keeps(Object part) { return part != null && (part == freeArm || part == freeSleeve); }
        @Override public void close() {
            if (this == INACTIVE || closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("Arm geometry scopes closed out of order");
            try { mask.close(); }
            finally { CURRENT.set(parent); freeArm = null; freeSleeve = null; closed = true; }
        }
    }
}
