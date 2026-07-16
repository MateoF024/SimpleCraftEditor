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
 * Server-side hook. Once vanilla has finished parsing the datapack recipes we filter out disabled
 * ones and inject our generated ones, so both the game (crafting) and every recipe viewer that reads
 * from the {@link RecipeManager} (JEI, EMI) observe the edited set.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Inject(
            method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("TAIL")
    )
    private void sce$applyRecipeState(Map<ResourceLocation, JsonElement> rawRecipes,
                                      ResourceManager resourceManager,
                                      ProfilerFiller profiler,
                                      CallbackInfo ci) {
        RecipeStateManager.INSTANCE.onRecipesReloaded((RecipeManager) (Object) this, rawRecipes);
    }
}
