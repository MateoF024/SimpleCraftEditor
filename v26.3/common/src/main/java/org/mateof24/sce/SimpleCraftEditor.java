package org.mateof24.sce;

import dev.architectury.event.events.common.LifecycleEvent;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.anvil.AnvilRules;
import org.mateof24.sce.core.command.SceCommands;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.mateof24.sce.net.SceNetworking;
import org.mateof24.sce.registry.SceMenus;
import org.mateof24.sce.registry.SceRecipeSerializers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SimpleCraftEditor {
    public static final String MOD_ID = "sce";
    public static final Logger LOGGER = LoggerFactory.getLogger("Simple Craft Editor");

    private SimpleCraftEditor() {
    }

    public static void init() {
        // Read the debug switch first, so anything the rest of init logs is already instrumented.
        SceDebug.loadStartup();
        SceMenus.init();
        SceRecipeSerializers.init();
        SceNetworking.init();
        SceCommands.register();
        // Read on the server and only on the server. A client has a config folder of its own, and a
        // rule file left there by a single-player world must never decide what a server's anvil does;
        // what a connected client knows about the rules is what the sync packet told it.
        LifecycleEvent.SERVER_STARTING.register(server -> {
            AnvilRules.INSTANCE.load();
            // And the pack the recipes came from: in 26.3 the recipe load is handed a registry lookup
            // and nothing else, so the only thing that still knows where the files are is the server.
            RecipeStateManager.INSTANCE.onServerStarting(server);
        });
        LifecycleEvent.SERVER_STOPPED.register(server -> RecipeStateManager.INSTANCE.onServerStopped());
        LOGGER.info("Initializing Simple Craft Editor common.");
    }
}
