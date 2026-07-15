package org.mateof24.sce.neoforge;

import dev.architectury.platform.forge.EventBuses;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.mateof24.sce.SimpleCraftEditor;

@Mod(SimpleCraftEditor.MOD_ID)
public final class SimpleCraftEditorNeoForge {
    public SimpleCraftEditorNeoForge(IEventBus eventBus) {
        // Register the mod event bus so Architectury can register content at the right time.
        EventBuses.registerModEventBus(SimpleCraftEditor.MOD_ID, eventBus);
        SimpleCraftEditor.init();
    }
}
