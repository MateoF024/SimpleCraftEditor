package org.mateof24.sce.mixin;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Applies this mod's edits to the recipe set <em>before</em> anything reads it.
 *
 * <p>The shape of this hook has changed with the recipe engine three times, and 26.3 is the third.
 * Until 1.21.11 the manager was handed the raw recipe JSON and the map could be edited in place; from
 * 1.21.11 it was handed a {@code RecipeMap} of recipes already built, and the argument was swapped for a
 * different map. In 26.3 recipes are a dynamic registry and the manager is no longer a reload listener
 * at all: {@code apply} is gone, and the map is built once, in the constructor, out of
 * {@code Registries.RECIPE}.
 *
 * <p>So the call that builds it is what gets wrapped. It has to be that call rather than the end of the
 * constructor, because the very next thing the constructor does is derive {@code learnableRecipes} from
 * the map it just built — swapping the field afterwards would leave the recipe book listing recipes
 * this mod had already taken away.
 *
 * <p>It is always a new map, never a mutated one: FastSuite hangs its cache off the map object, and
 * building a new one is what throws that cache away.
 *
 * <p>{@code /reload} still reaches this, which is what keeps the whole model working: recipes are in
 * {@code RegistryDataLoader.RELOADABLE_REGISTRIES}, so a reload rebuilds the registry and with it the
 * manager, and this runs again.
 */
@Mixin(value = RecipeManager.class, priority = 900)
public abstract class RecipeManagerMixin {
    @Redirect(
            method = "<init>(Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/crafting/RecipeMap;create"
                            + "(Lnet/minecraft/core/HolderLookup;)Lnet/minecraft/world/item/crafting/RecipeMap;"
            )
    )
    private RecipeMap sce$editRecipesBeforeLoad(HolderLookup<Recipe<?>> lookup,
                                                HolderLookup.Provider registries) {
        return RecipeStateManager.INSTANCE.beforeRecipeLoad(RecipeMap.create(lookup), registries);
    }
}
