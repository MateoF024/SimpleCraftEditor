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
            // The key has to be registered now: Forge collects key mappings before client setup runs, so
            // registering it there had it show up in the controls screen and do nothing when pressed. The
            // rest of the client wiring still waits for setup, where the menu type is resolvable.
            SceClient.registerKeyMappings();
            modEventBus.addListener((FMLClientSetupEvent event) -> SceClient.init());
        }
    }
}
