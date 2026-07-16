package org.mateof24.sce;

import dev.architectury.event.events.common.LifecycleEvent;
import org.mateof24.sce.core.command.SceCommands;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SimpleCraftEditor {
    public static final String MOD_ID = "sce";
    public static final Logger LOGGER = LoggerFactory.getLogger("Simple Craft Editor");

    private SimpleCraftEditor() {
    }

    public static void init() {
        SceCommands.register();
        LifecycleEvent.SERVER_STOPPED.register(server -> RecipeStateManager.INSTANCE.onServerStopped());
        LOGGER.info("Initializing Simple Craft Editor common.");
    }
}
