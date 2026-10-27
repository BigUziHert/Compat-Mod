package dev.caleb.emfattachments.client;

import dev.caleb.emfattachments.EmfAttachments;
import dev.caleb.emfattachments.config.CompatConfig;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Separate native bridge interpolation history for the local body's main-camera pass. */
@EventBusSubscriber(modid = EmfAttachments.ID, value = Dist.CLIENT)
public final class FirstPersonPoseStates {
    // Values contain the bridge's private PartFade type; this class never reads or changes one.
    private static final RenderContextStateStore<UUID, Map<String, ?>> STATES = new RenderContextStateStore<>();

    private FirstPersonPoseStates() {}

    public static Map<UUID, Map<String, ?>> select(Map<UUID, Map<String, ?>> ordinaryStates, UUID uuid) {
        Minecraft minecraft = refresh();
        var player = minecraft.player;
        boolean localBody = player != null && uuid != null && uuid.equals(player.getUUID())
                && FirstPersonRenderPolicy.isLocalBody(player);
        return STATES.select(ordinaryStates, uuid, player == null ? null : player.getUUID(), localBody);
    }

    public static void retainOnly(Collection<UUID> activeEntities) {
        refresh();
        STATES.retainOnly(activeEntities);
    }

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        refresh();
    }

    private static Minecraft refresh() {
        Minecraft minecraft = Minecraft.getInstance();
        // Client ticks also run on the loading screen, before FirstPerson initializes its API.
        boolean enabled = minecraft.level != null && minecraft.player != null
                && ModList.get().isLoaded("firstperson") && ModList.get().isLoaded("emf_compat_core")
                && CompatConfig.firstPersonPoses() && OptionalFirstPerson.enabled();
        // These persistent settings are intentionally distinct from BodyRenderScope. Shadows,
        // camera extraction, detached cameras and another entity do not discard body history.
        STATES.refresh(minecraft.level, minecraft.player, enabled, minecraft.options.getCameraType().isFirstPerson());
        return minecraft;
    }

    private static final class OptionalFirstPerson {
        private static boolean enabled() {
            return dev.tr7zw.firstperson.FirstPersonModelCore.instance != null
                    && dev.tr7zw.firstperson.api.FirstPersonAPI.isEnabled();
        }
    }
}
