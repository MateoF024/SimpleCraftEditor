package org.mateof24.sce;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.registry.ReloadListenerRegistry;
import net.minecraft.server.packs.PackType;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.command.SceCommands;
import org.mateof24.sce.core.state.RecipeReloadListener;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.mateof24.sce.net.SceNetworking;
import org.mateof24.sce.registry.SceMenus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class SimpleCraftEditor {
    public static final String MOD_ID = "sce";
    public static final Logger LOGGER = LoggerFactory.getLogger("Simple Craft Editor");

    private SimpleCraftEditor() {
    }

    public static void init() {
        // Read the debug switch first, so anything the rest of init logs is already instrumented.
        SceDebug.loadStartup();
        SceMenus.init();
        SceNetworking.init();
        SceCommands.register();

        // Capture recipe sources with our own reload listener rather than a mixin on the vanilla load,
        // which another mod can cancel out from under us. Depends on minecraft:recipes so it reads after
        // the recipes are in place. The server reference lets it re-apply our state once a reload settles.
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new RecipeReloadListener(),
                RecipeReloadListener.ID, List.of(net.minecraft.resources.ResourceLocation.tryParse("minecraft:recipes")));
        LifecycleEvent.SERVER_BEFORE_START.register(server -> RecipeStateManager.INSTANCE.setServer(server));
        LifecycleEvent.SERVER_STARTED.register(server -> RecipeStateManager.INSTANCE.onServerStarted(server));
        LifecycleEvent.SERVER_STOPPED.register(server -> RecipeStateManager.INSTANCE.onServerStopped());
        LOGGER.info("Initializing Simple Craft Editor common.");
    }
}
