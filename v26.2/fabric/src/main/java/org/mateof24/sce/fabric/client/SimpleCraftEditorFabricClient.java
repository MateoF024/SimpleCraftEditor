package org.mateof24.sce.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;
import org.mateof24.sce.client.SceClient;
import org.mateof24.sce.client.screen.RecipeEditorScreen;
import org.mateof24.sce.registry.SceMenus;

public final class SimpleCraftEditorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SceClient.init();
        // Registered here rather than in the shared module for two reasons: Architectury 19 dropped the
        // loader-agnostic way of doing it, and the vanilla call is only reachable where the Fabric API's
        // access widener applies, which is this project and not the common one. NeoForge registers the
        // same screen from RegisterMenuScreensEvent, which is where that loader insists it happens.
        MenuScreens.register(SceMenus.RECIPE_EDITOR.get(), RecipeEditorScreen::new);
    }
}
