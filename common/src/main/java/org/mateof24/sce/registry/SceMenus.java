package org.mateof24.sce.registry;

import dev.architectury.registry.menu.MenuRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.menu.RecipeEditorMenu;

/** Registers the editor's synced container menu type across loaders. */
public final class SceMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(SimpleCraftEditor.MOD_ID, Registries.MENU);

    public static final RegistrySupplier<MenuType<RecipeEditorMenu>> RECIPE_EDITOR =
            MENUS.register("recipe_editor", () -> MenuRegistry.ofExtended(RecipeEditorMenu::new));

    private SceMenus() {
    }

    public static void init() {
        MENUS.register();
    }
}
