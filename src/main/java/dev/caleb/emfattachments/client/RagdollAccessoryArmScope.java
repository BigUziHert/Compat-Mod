package dev.caleb.emfattachments.client;

import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollPartBlockEntityRenderer;
import java.util.List;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.HumanoidArm;

/** The actual physical arm for one Sable accessory call, not an inferred slot parity. */
public final class RagdollAccessoryArmScope implements AutoCloseable {
    private static final ThreadLocal<RagdollAccessoryArmScope> CURRENT = new ThreadLocal<>();
    private final RagdollAccessoryArmScope previous;
    private final HumanoidArm arm;
    private boolean closed;

    private RagdollAccessoryArmScope(HumanoidArm arm) {
        previous = CURRENT.get();
        this.arm = arm;
        CURRENT.set(this);
    }

    public static RagdollAccessoryArmScope openCurrentPart() {
        RagdollPartBlockEntity.BodyPart part = RagdollPartBlockEntityRenderer.activeBodyPart();
        return open(part == RagdollPartBlockEntity.BodyPart.LEFT_ARM ? HumanoidArm.LEFT
                : part == RagdollPartBlockEntity.BodyPart.RIGHT_ARM ? HumanoidArm.RIGHT : null);
    }

    /** Null is an independent non-arm call and must not inherit an outer mask. */
    public static RagdollAccessoryArmScope open(HumanoidArm arm) {
        return new RagdollAccessoryArmScope(arm);
    }

    public static boolean hides(HumanoidArm selectedArm) {
        RagdollAccessoryArmScope current = CURRENT.get();
        return current != null && current.arm != null && selectedArm != null && current.arm != selectedArm;
    }

    /** Paired glove models share one draw for both arms; keep only this physical part. */
    public static Iterable<ModelPart> visibleGloveArms(HumanoidModel<?> model, Iterable<ModelPart> nativeParts) {
        if (hides(HumanoidArm.LEFT)) return List.of(model.rightArm);
        if (hides(HumanoidArm.RIGHT)) return List.of(model.leftArm);
        return nativeParts;
    }

    @Override public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Ragdoll accessory arm scopes closed out of order");
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
        closed = true;
    }
}
