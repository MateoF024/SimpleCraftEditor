package org.mateof24.sce.mixin;

import com.google.gson.JsonElement;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Server-side hook. Once vanilla has finished parsing the datapack recipes we filter out disabled
 * ones and inject our generated ones, so both the game (crafting) and every recipe viewer that reads
 * from the {@link RecipeManager} (JEI, EMI) observe the edited set. The manager's captured registry
 * lookup is forwarded so our stored/generated recipe JSON can be decoded with the same codec context.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Shadow
    @Final
    private HolderLookup.Provider registries;

    @Inject(
            method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("TAIL")
    )
    private void sce$applyRecipeState(Map<ResourceLocation, JsonElement> rawRecipes,
                                      ResourceManager resourceManager,
                                      ProfilerFiller profiler,
                                      CallbackInfo ci) {
        RecipeStateManager.INSTANCE.onRecipesReloaded((RecipeManager) (Object) this, rawRecipes, registries);
    }
}
