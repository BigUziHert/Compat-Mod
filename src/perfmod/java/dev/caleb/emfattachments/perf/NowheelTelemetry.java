package dev.caleb.emfattachments.perf;

import dev.tr7zw.entityculling.EntityCullingModBase;
import dev.tr7zw.entityculling.NMSCullingHelper;
import dev.tr7zw.entityculling.access.Cullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

/** Read-only optional-mod telemetry. Nothing from Nowheel is linked by the verifier. */
final class NowheelTelemetry {
    final boolean installed = ModList.get().isLoaded("nowheel");
    final List<String> errors = new ArrayList<>();
    private Method predicate;

    NowheelTelemetry() {
        if (installed) try {
            predicate = Class.forName("dev.lapt.nowheel.compat.sable.SableCompat").getMethod("onSubLevel", Entity.class);
        } catch (ReflectiveOperationException error) { errors.add(error.toString()); }
    }

    Boolean predicate(Entity entity) {
        if (!installed || predicate == null) return null;
        try { return (Boolean)predicate.invoke(null, entity); }
        catch (ReflectiveOperationException error) {
            if (errors.isEmpty()) errors.add(error.toString());
            return null;
        }
    }

    static Map<String, String> versions() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String id : List.of("emfattachments", "nowheel", "entityculling", "sable", "sablecompanion", "firstperson"))
            result.put(id, ModList.get().getModContainerById(id).map(c -> c.getModInfo().getVersion().toString()).orElse(""));
        return result;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> describe(Entity plotResidentControl, Entity worldControl) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("installed", installed);
        out.put("predicateMethod", installed ? "dev.lapt.nowheel.compat.sable.SableCompat.onSubLevel(Entity)" : "");
        Map<String, Object> config = new LinkedHashMap<>();
        List<Map<String, Object>> registered = new ArrayList<>();
        boolean sableRegistered = false;
        if (installed) try {
            Object value = Class.forName("dev.lapt.nowheel.config.NowheelConfig").getMethod("get").invoke(null);
            for (Field field : value.getClass().getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == boolean.class)
                    config.put(field.getName(), field.getBoolean(value));
            }
        } catch (ReflectiveOperationException error) { errors.add(error.toString()); }
        try {
            Field field = EntityCullingModBase.class.getDeclaredField("dynamicEntityWhitelist");
            field.setAccessible(true);
            var predicates = (Iterable<Function<Entity, Boolean>>)field.get(EntityCullingModBase.instance);
            for (Function<Entity, Boolean> function : predicates) {
                String name = function.getClass().getName();
                boolean plot = function.apply(plotResidentControl), world = function.apply(worldControl);
                boolean origin = name.startsWith("dev.lapt.nowheel.NowheelMod$$Lambda");
                boolean directPlot = Boolean.TRUE.equals(predicate(plotResidentControl));
                boolean directWorld = Boolean.TRUE.equals(predicate(worldControl));
                boolean match = installed && origin && plot && !world && directPlot && !directWorld;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("class", name); row.put("nowheelOrigin", origin);
                row.put("plotResidentControl", plot); row.put("ordinaryWorldControl", world);
                row.put("matchesDirectSablePredicateControls", match);
                registered.add(row); sableRegistered |= match;
            }
        } catch (ReflectiveOperationException | RuntimeException error) { errors.add(error.toString()); }
        out.put("config", config); out.put("registeredDynamicPredicates", registered);
        out.put("sablePredicateRegistered", sableRegistered);
        out.put("registrationEvidence", "Read-only native dynamic whitelist inspection: Nowheel-origin lambda must accept an uninserted plot-resident control, reject an ordinary-world control, and agree with the installed direct Sable predicate on both. Neither control is added to the world.");
        out.put("errors", List.copyOf(errors));
        out.put("configMutatedByFixture", false);
        return out;
    }

    static Map<String, Object> culling(Entity entity) {
        Cullable c = (Cullable)entity;
        return Map.of("culled", c.isCulled(), "outOfCamera", c.isOutOfCamera(), "forcedVisible", c.isForcedVisible(),
                "nativeIgnoresCulling", NMSCullingHelper.ignoresCulling(entity),
                "nativeDynamicWhitelisted", EntityCullingModBase.instance.isEntityDynamicWhitelisted(c));
    }

    static boolean dynamic(Entity entity) {
        return EntityCullingModBase.instance.isEntityDynamicWhitelisted((Cullable)entity);
    }
}
