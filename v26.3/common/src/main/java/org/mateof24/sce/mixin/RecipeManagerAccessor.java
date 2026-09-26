package org.mateof24.sce.mixin;

import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the field that holds the live recipes.
 *
 * <p>Until 1.21.11 the manager had {@code replaceRecipes}, and this mod called it. It is gone: the map
 * is a private field now, and everything derived from it is rebuilt by {@code finalizeRecipeLoading}.
 * So the field is set directly and that method is called after, which is exactly the pair the manager
 * itself uses when a datapack reload lands.
 */
@Mixin(RecipeManager.class)
public interface RecipeManagerAccessor {
    // Final from 26.3, when the map stopped being rebuilt on reload and started being built once.
    @Mutable
    @Accessor("recipes")
    void sce$setRecipes(RecipeMap recipes);
}
