package org.mateof24.sce.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.mateof24.sce.SimpleCraftEditor;

/**
 * Takes the mod event bus in its constructor, and keeps taking it however small this class gets: the
 * editor's screen can only be registered from {@code RegisterMenuScreensEvent}, which is a mod-bus
 * event, so the bus has to reach here.
 */
@Mod(SimpleCraftEditor.MOD_ID)
public final class SimpleCraftEditorNeoForge {
    public SimpleCraftEditorNeoForge(IEventBus modEventBus) {
        SimpleCraftEditor.init();
    }
}
