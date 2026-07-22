package org.mateof24.sce.mixin;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Applies our recipe edits to the map of raw recipe JSON <em>before</em> anything loads it.
 *
 * <p>The old capture hook was at the tail of {@code apply}; KubeJS cancels that method at its head, so the
 * tail never ran. This one is at the head and, crucially, at a higher priority than KubeJS's own head
 * injection, so it runs first and edits the very map KubeJS (or vanilla) then reads. Removing a disabled
 * recipe or adding a generated one here means whoever builds the recipe set builds the edited one — the
 * recipes are in place by the time the game, and every recipe viewer, indexes them, with no post-hoc
 * replacement and no reload race.
 *
 * <p>The map at this point is the full set of datapack recipe sources, which is also captured for the
 * editor to load from. Recipes with no source here — Create's runtime-generated ones, anything built in
 * code — are the reconstruction case, handled elsewhere.
 */
@Mixin(value = RecipeManager.class, priority = 900)
public abstract class RecipeManagerMixin {
    @Inject(
            method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("HEAD")
    )
    private void sce$editRecipesBeforeLoad(Map<ResourceLocation, JsonElement> map,
                                           ResourceManager resourceManager,
                                           ProfilerFiller profiler,
                                           CallbackInfo ci) {
        RecipeStateManager.INSTANCE.beforeRecipeLoad(map);
    }
}
