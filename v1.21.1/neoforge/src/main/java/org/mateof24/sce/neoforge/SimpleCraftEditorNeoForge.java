package org.mateof24.sce.neoforge;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.client.SceClient;

@Mod(SimpleCraftEditor.MOD_ID)
public final class SimpleCraftEditorNeoForge {
    public SimpleCraftEditorNeoForge() {
        SimpleCraftEditor.init();
        // Client setup must run during mod construction, not in FMLClientSetupEvent: Architectury
        // registers the screen factory and keybinds through mod-bus events (RegisterMenuScreensEvent,
        // RegisterKeyMappingsEvent) that have already fired by the time client setup runs, so deferring
        // it leaves the editor menu without a screen and it never opens.
        if (FMLEnvironment.dist.isClient()) {
            SceClient.init();
        }
    }
}
