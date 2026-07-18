package org.mateof24.sce.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import org.mateof24.sce.client.SceClient;

public final class SimpleCraftEditorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SceClient.init();
    }
}
