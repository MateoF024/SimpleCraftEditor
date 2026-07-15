package org.mateof24.sce.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.mateof24.sce.SimpleCraftEditor;

@Mod(SimpleCraftEditor.MOD_ID)
public final class SimpleCraftEditorForge {
    public SimpleCraftEditorForge() {
        // Register the mod event bus so Architectury can register content at the right time.
        EventBuses.registerModEventBus(SimpleCraftEditor.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        SimpleCraftEditor.init();
    }
}
