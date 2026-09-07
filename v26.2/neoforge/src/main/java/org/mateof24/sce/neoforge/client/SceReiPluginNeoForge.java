package org.mateof24.sce.neoforge.client;

import me.shedaniel.rei.forge.REIPluginClient;
import org.mateof24.sce.client.rei.SceReiPlugin;

/**
 * How REI finds the plugin on NeoForge.
 *
 * <p>JEI's and EMI's marker annotations are the same class on every loader, so their plugins are declared
 * where they are written. REI's is not: {@code @REIPluginClient} only exists in the NeoForge half of its jar,
 * so the plugin itself stays in the shared module and this names it here.
 */
@REIPluginClient
public class SceReiPluginNeoForge extends SceReiPlugin {
}
