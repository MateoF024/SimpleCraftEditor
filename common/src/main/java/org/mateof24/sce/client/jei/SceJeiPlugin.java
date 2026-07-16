package org.mateof24.sce.client.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.client.screen.RecipeEditorScreen;

/**
 * JEI integration. Discovered via {@code @JeiPlugin} on Forge and the {@code jei_mod_plugin} entrypoint
 * on Fabric. JEI already reacts to added/disabled recipes because those are re-synced to the client as a
 * normal recipe update, which JEI listens for; this plugin adds the drag-from-JEI support in the editor.
 */
@JeiPlugin
@Environment(EnvType.CLIENT)
public class SceJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(SimpleCraftEditor.MOD_ID, "jei_plugin");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(RecipeEditorScreen.class, new RecipeEditorGhostHandler());
    }
}
