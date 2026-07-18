package org.mateof24.sce.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.client.SceClient;
import org.mateof24.sce.client.screen.RecipeEditorScreen;
import org.mateof24.sce.registry.SceMenus;

@Mod(SimpleCraftEditor.MOD_ID)
public final class SimpleCraftEditorNeoForge {
    public SimpleCraftEditorNeoForge(IEventBus modEventBus) {
        SimpleCraftEditor.init();
        // Client wiring runs during construction (keybinds and network receivers register through mod-bus
        // events that fire later, so registering their listeners now catches them). The editor screen must
        // wait for RegisterMenuScreensEvent, where the menu type is already registered and resolvable.
        if (FMLEnvironment.dist.isClient()) {
            SceClient.init();
            modEventBus.addListener((RegisterMenuScreensEvent event) ->
                    event.register(SceMenus.RECIPE_EDITOR.get(), RecipeEditorScreen::new));
        }
    }
}
