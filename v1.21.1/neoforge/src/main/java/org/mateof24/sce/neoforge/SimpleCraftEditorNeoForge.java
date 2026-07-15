package org.mateof24.sce.neoforge;

import net.neoforged.fml.common.Mod;
import org.mateof24.sce.SimpleCraftEditor;

@Mod(SimpleCraftEditor.MOD_ID)
public final class SimpleCraftEditorNeoForge {
    public SimpleCraftEditorNeoForge() {
        // Architectury registers the mod event bus automatically on NeoForge,
        // so no manual EventBuses registration is needed here (unlike Forge 1.20.1).
        SimpleCraftEditor.init();
    }
}
