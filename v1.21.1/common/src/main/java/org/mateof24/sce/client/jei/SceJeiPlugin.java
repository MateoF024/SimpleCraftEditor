package org.mateof24.sce.client.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.client.SceClient;
import org.mateof24.sce.client.screen.RecipeEditorScreen;

/**
 * JEI integration. Discovered via {@code @JeiPlugin} on Forge and the {@code jei_mod_plugin} entrypoint
 * on Fabric. JEI already reacts to added/disabled recipes because those are re-synced to the client as a
 * normal recipe update, which JEI listens for; this plugin adds the drag-from-JEI support in the editor.
 */
@JeiPlugin
@Environment(EnvType.CLIENT)
public class SceJeiPlugin implements IModPlugin {
    private static IJeiRuntime runtime;
    private static boolean providerRegistered;

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(SimpleCraftEditor.MOD_ID, "jei_plugin");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(RecipeEditorScreen.class, new RecipeEditorGhostHandler());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        if (!providerRegistered) {
            providerRegistered = true;
            SceClient.registerHoveredItemProvider(SceJeiPlugin::hoveredItem);
        }
    }

    private static ItemStack hoveredItem() {
        if (runtime == null) {
            return ItemStack.EMPTY;
        }
        ItemStack listed = runtime.getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        if (listed != null && !listed.isEmpty()) {
            return listed;
        }
        ItemStack bookmarked = runtime.getBookmarkOverlay().getItemStackUnderMouse();
        return bookmarked != null ? bookmarked : ItemStack.EMPTY;
    }
}
