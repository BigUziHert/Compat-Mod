package dev.caleb.emfattachments;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import dev.caleb.emfattachments.config.CompatConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only render corrections; no items, packets, or saved data. */
@Mod(value = EmfAttachments.ID, dist = Dist.CLIENT)
public final class EmfAttachments {
    public static final String ID = "emfattachments";

    public EmfAttachments(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, CompatConfig.SPEC, "emf-attachments-client.toml");
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ConfigurationScreen(mod, parent));
    }
}
