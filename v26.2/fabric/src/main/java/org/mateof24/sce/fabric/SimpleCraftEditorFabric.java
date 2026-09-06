package org.mateof24.sce.fabric;

import net.fabricmc.api.ModInitializer;
import org.mateof24.sce.SimpleCraftEditor;

public final class SimpleCraftEditorFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        SimpleCraftEditor.init();
    }
}
