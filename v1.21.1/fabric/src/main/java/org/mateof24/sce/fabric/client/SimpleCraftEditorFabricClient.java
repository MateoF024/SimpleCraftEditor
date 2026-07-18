package org.mateof24.sce.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import org.mateof24.sce.client.SceClient;

public final class SimpleCraftEditorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SceClient.init();
        // On Fabric the menu type is already registered by the time client init runs, so the screen
        // factory can be registered here directly.
        SceClient.registerEditorScreen();
    }
}
