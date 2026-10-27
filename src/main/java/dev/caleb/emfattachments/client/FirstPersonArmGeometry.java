package dev.caleb.emfattachments.client;

import dev.caleb.emfattachments.compat.BetterCombatAttackAccess;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;

/** Preserve the active native animation's arm flags without moving hands/items. */
public final class FirstPersonArmGeometry {
    private static final ScopedGeometryMask<ModelPart> MASK = new ScopedGeometryMask<>();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final Scope INACTIVE = new Scope(null, null, null, false);
    private FirstPersonArmGeometry() {}

    public static Scope open(LivingEntity entity, EntityModel<?> model, float partial) {
        boolean owned = entity instanceof BetterCombatAttackAccess && model instanceof HumanoidModel<?>
                && FirstPersonRenderPolicy.isLocalBody(entity) && FirstPersonCombat.ownsCombatRendering();
        var visibility = owned ? NativeAttackArmVisibility.from(
                ((BetterCombatAttackAccess) entity).emfattachments$attackStack(), partial) : null;
        // Item flags belong to the native item renderer; this scope only masks
        // arm geometry. With both arms visible, no copy can inherit a mask.
        owned = owned && visibility != null && visibility.hidesAnyArm();
        Scope scope = openScope(entity, owned);
        try {
            if (owned) {
                var humanoid = (HumanoidModel<?>) model;
                if (!visibility.rightArm()) seedTree(humanoid.rightArm);
                if (!visibility.leftArm()) seedTree(humanoid.leftArm);
                if (model instanceof PlayerModel<?> player) {
                    if (!visibility.rightArm()) seedTree(player.rightSleeve);
                    if (!visibility.leftArm()) seedTree(player.leftSleeve);
                }
            }
            return scope;
        } catch (RuntimeException | Error failure) {
            scope.close();
            throw failure;
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
        private boolean closed;
        private Scope(Scope parent, LivingEntity entity, ScopedGeometryMask<ModelPart>.Scope mask, boolean owned) {
            this.parent = parent; this.entity = entity; this.mask = mask; this.owned = owned;
        }
        @Override public void close() {
            if (this == INACTIVE || closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("Arm geometry scopes closed out of order");
            try { mask.close(); }
            finally { CURRENT.set(parent); closed = true; }
        }
    }
}
