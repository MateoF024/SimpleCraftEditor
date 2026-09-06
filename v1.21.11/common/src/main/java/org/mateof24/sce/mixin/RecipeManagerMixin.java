package org.mateof24.sce.mixin;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Applies this mod's edits to the recipe set <em>before</em> anything reads it.
 *
 * <p>The old capture hook was at the tail of {@code apply}; KubeJS cancels that method at its head, so
 * the tail never ran. This one is at the head and at a higher priority than KubeJS's own head injection,
 * so it runs first and hands back the set everyone else then works from. Removing a disabled recipe or
 * adding an authored one here means the recipes are in place by the time the game, and every recipe
 * viewer, indexes them, with no post-hoc replacement and no reload race.
 *
 * <p>What that edit looks like changed at 1.21.11. Until then {@code apply} was handed the raw recipe
 * JSON and the map could be edited in place; now it is handed a {@code RecipeMap} of recipes already
 * built, so the argument is swapped for a different map instead. It is always a new map, never a mutated
 * one: FastSuite hangs its cache off the map object, and building a new one is what throws that cache
 * away — mutating in place would leave it standing and the edit would not show until a reload.
 */
@Mixin(value = RecipeManager.class, priority = 900)
public abstract class RecipeManagerMixin {
    @Shadow
    @Final
    private HolderLookup.Provider registries;

    @ModifyVariable(
            method = "apply(Lnet/minecraft/world/item/crafting/RecipeMap;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 1
    )
    private RecipeMap sce$editRecipesBeforeLoad(RecipeMap incoming, RecipeMap ignored,
                                                ResourceManager resourceManager, ProfilerFiller profiler) {
        return RecipeStateManager.INSTANCE.beforeRecipeLoad(incoming, resourceManager, registries);
    }
}
