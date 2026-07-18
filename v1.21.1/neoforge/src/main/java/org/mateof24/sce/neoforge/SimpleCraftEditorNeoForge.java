package org.mateof24.sce.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.client.SceClient;

@Mod(SimpleCraftEditor.MOD_ID)
public final class SimpleCraftEditorNeoForge {
    public SimpleCraftEditorNeoForge(IEventBus modEventBus) {
        SimpleCraftEditor.init();
        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener((FMLClientSetupEvent event) -> SceClient.init());
        }
    }
}
