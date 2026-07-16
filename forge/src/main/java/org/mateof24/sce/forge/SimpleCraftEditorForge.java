package org.mateof24.sce.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.client.SceClient;

@Mod(SimpleCraftEditor.MOD_ID)
public final class SimpleCraftEditorForge {
    public SimpleCraftEditorForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        // Register the mod event bus so Architectury can register content at the right time.
        EventBuses.registerModEventBus(SimpleCraftEditor.MOD_ID, modEventBus);
        SimpleCraftEditor.init();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener((FMLClientSetupEvent event) -> SceClient.init());
        }
    }
}
