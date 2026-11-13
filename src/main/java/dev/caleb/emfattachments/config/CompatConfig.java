package dev.caleb.emfattachments.config;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Live client settings, exposed by NeoForge's normal Mods configuration screen. */
public final class CompatConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue HIDE_HEAD_ACCESSORIES;
    public static final ModConfigSpec.BooleanValue HIDE_SCARVES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> HIDDEN_SLOTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> HIDDEN_ITEMS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> VISIBLE_ITEMS;
    public static final ModConfigSpec.BooleanValue FIRST_PERSON_BOW_POSE;
    public static final ModConfigSpec.BooleanValue FIRST_PERSON_CROSSBOW_POSE;
    public static final ModConfigSpec.BooleanValue FIRST_PERSON_POSES;
    public static final ModConfigSpec.BooleanValue COMBAT_COMPATIBILITY;
    public static final ModConfigSpec.BooleanValue SCALE_BODY_OFFSET;
    public static final ModConfigSpec.DoubleValue DOWNWARD_BODY_CLEARANCE;
    public static final ModConfigSpec.DoubleValue COMBAT_BODY_Y_OFFSET;
    public static final ModConfigSpec.DoubleValue ARM_PITCH_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue ARM_PITCH_OFFSET;
    public static final ModConfigSpec.DoubleValue ARM_POSITION_X;
    public static final ModConfigSpec.DoubleValue ARM_POSITION_Y;
    public static final ModConfigSpec.DoubleValue ARM_POSITION_Z;
    public static final ModConfigSpec.DoubleValue LOOK_UP_Z_SHIFT;

    static {
        var b = new ModConfigSpec.Builder();
        b.comment("Affects the local FirstPerson body and native first-person sleeping view. Equipment and other players are unchanged.")
                .translation("emfattachments.configuration.accessories").push("accessories");
        HIDE_HEAD_ACCESSORIES = b.comment("Hide equipment in configured head slots while viewing your own body.")
                .translation("emfattachments.configuration.hideHeadAccessories").define("hideHeadAccessories", true);
        HIDE_SCARVES = b.comment("Hide Artifacts lucky/invisibility scarves and Deep Aether's floaty scarf near the camera.")
                .translation("emfattachments.configuration.hideScarves").define("hideScarves", true);
        HIDDEN_SLOTS = b.comment("Exact slot paths or separator-delimited slot words; namespace text is not matched.")
                .translation("emfattachments.configuration.hiddenSlots")
                .defineListAllowEmpty("hiddenSlots", List.of("head", "hat", "face", "mask", "goggle", "goggles"),
                        () -> "head", CompatConfig::validSlot);
        HIDDEN_ITEMS = b.comment("Additional exact namespace:item IDs to hide, independent of the two switches above.")
                .translation("emfattachments.configuration.hiddenItems")
                .defineListAllowEmpty("hiddenItems", List.<String>of(), () -> "minecraft:carved_pumpkin", CompatConfig::validItem);
        VISIBLE_ITEMS = b.comment("Exact namespace:item IDs to keep visible. Overrides hidden slots, scarves and hiddenItems.")
                .translation("emfattachments.configuration.visibleItems")
                .defineListAllowEmpty("visibleItems", List.<String>of(), () -> "create:goggles", CompatConfig::validItem);
        b.pop();
        b.comment("FirstPerson and installed animation integrations; never writes another mod's configuration.")
                .translation("emfattachments.configuration.animation").push("animation");
        FIRST_PERSON_BOW_POSE = b.comment("Use the native bow draw on your FirstPerson body; keeps NEA Custom V1 in third person and on other players.")
                .translation("emfattachments.configuration.firstPersonBowPose").define("firstPersonBowPose", true);
        FIRST_PERSON_CROSSBOW_POSE = b.comment("Aim the local FirstPerson crossbow charging pose along the player's view while retaining the native pulling motion.")
                .translation("emfattachments.configuration.firstPersonCrossbowPose").define("firstPersonCrossbowPose", true);
        FIRST_PERSON_POSES = b.comment("Keep first-person animation interpolation separate from shadow and third-person draws.")
                .translation("emfattachments.configuration.separateFirstPersonPoses").define("separateFirstPersonPoses", true);
        COMBAT_COMPATIBILITY = b.comment("Coordinate Better Combat's complete attack animation with FirstPerson.")
                .translation("emfattachments.configuration.combatCompatibility").define("combatCompatibility", true);
        SCALE_BODY_OFFSET = b.comment("Scale FirstPerson's own body offset with the vanilla player scale attribute.")
                .translation("emfattachments.configuration.scaleBodyOffset").define("scaleBodyOffset", true);
        DOWNWARD_BODY_CLEARANCE = b.comment("Additional body distance behind the camera when looking straight down, in blocks at normal size.",
                        "Blends smoothly from 40 to 90 degrees and follows player scale. Zero disables. The camera and interaction rays are unchanged.")
                .translation("emfattachments.configuration.downwardBodyClearance").defineInRange("downwardBodyClearance", 0.45, 0.0, 1.0);
        COMBAT_BODY_Y_OFFSET = b.comment("Crouched combat body height correction, in blocks. Used only when EMF Compat Core's crouch normalizer is inactive or unavailable.")
                .translation("emfattachments.configuration.combatBodyYOffset")
                .defineInRange("combatBodyYOffset", -0.1, -8.0, 8.0);
        ARM_PITCH_MULTIPLIER = b.comment("Camera pitch contribution during combat; 0 disables pitch following.")
                .translation("emfattachments.configuration.armPitchMultiplier")
                .defineInRange("armPitchMultiplier", 0.7, -4.0, 4.0);
        ARM_PITCH_OFFSET = b.comment("Additional combat arm rotation, in radians.")
                .translation("emfattachments.configuration.armPitchOffset")
                .defineInRange("armPitchOffset", 0.15, -6.283185307179586, 6.283185307179586);
        ARM_POSITION_X = position(b, "armPositionX", 0.0, "Horizontal arm correction, in model pixels. Zero preserves the native attachment point.");
        ARM_POSITION_Y = position(b, "armPositionY", 0.0, "Vertical arm correction, in model pixels. Zero preserves the native attachment point.");
        ARM_POSITION_Z = position(b, "armPositionZ", 0.0, "Forward arm correction, in model pixels. Zero preserves the native attachment point.");
        LOOK_UP_Z_SHIFT = position(b, "lookUpZShift", 0.0, "Additional forward shift when looking up, in model pixels. Zero preserves the native attachment point.");
        b.pop();
        SPEC = b.build();
    }

    private CompatConfig() {}

    private static ModConfigSpec.DoubleValue position(ModConfigSpec.Builder b, String key, double value, String help) {
        return b.comment(help, "Range: -32 to 32, including offsets below -4.")
                .translation("emfattachments.configuration." + key).defineInRange(key, value, -32.0, 32.0);
    }

    private static boolean validSlot(Object value) {
        return value instanceof String s && !s.isBlank() && s.length() <= 128
                && s.matches("[a-z0-9_.:/-]+");
    }

    private static boolean validItem(Object value) {
        return value instanceof String s && s.length() <= 256 && s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }

    public static boolean hideHeadAccessories() { return HIDE_HEAD_ACCESSORIES.get(); }
    public static boolean hideScarves() { return HIDE_SCARVES.get(); }
    public static List<? extends String> hiddenSlots() { return HIDDEN_SLOTS.get(); }
    public static List<? extends String> hiddenItems() { return HIDDEN_ITEMS.get(); }
    public static List<? extends String> visibleItems() { return VISIBLE_ITEMS.get(); }
    public static boolean firstPersonBowPose() { return FIRST_PERSON_BOW_POSE.get(); }
    public static boolean firstPersonCrossbowPose() { return FIRST_PERSON_CROSSBOW_POSE.get(); }
    public static boolean firstPersonPoses() { return FIRST_PERSON_POSES.get(); }
    public static boolean combatCompatibility() { return COMBAT_COMPATIBILITY.get(); }
    public static boolean scaleBodyOffset() { return SCALE_BODY_OFFSET.get(); }
    public static double downwardBodyClearance() { return DOWNWARD_BODY_CLEARANCE.get(); }
    public static double combatBodyYOffset() { return COMBAT_BODY_Y_OFFSET.get(); }
    public static double armPitchMultiplier() { return ARM_PITCH_MULTIPLIER.get(); }
    public static double armPitchOffset() { return ARM_PITCH_OFFSET.get(); }
    public static double armPositionX() { return ARM_POSITION_X.get(); }
    public static double armPositionY() { return ARM_POSITION_Y.get(); }
    public static double armPositionZ() { return ARM_POSITION_Z.get(); }
    public static double lookUpZShift() { return LOOK_UP_Z_SHIFT.get(); }
}
