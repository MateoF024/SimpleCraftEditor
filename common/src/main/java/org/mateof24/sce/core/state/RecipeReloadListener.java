package org.mateof24.sce.core.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.core.SceDebug;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the recipe source JSON straight from the datapacks, as a reload listener of our own.
 *
 * <p>This replaces the mixin that used to capture recipes at the tail of {@code RecipeManager.apply}. That
 * mixin was fragile: KubeJS injects at the head of the same method with {@code cancellable=true} and, when
 * it has recipe scripts, cancels the vanilla load and builds the recipes itself — so the tail our capture
 * rode on never ran, the recipe cache stayed empty, and editing wiped every recipe the pack had.
 *
 * <p>A separate reload listener cannot be cancelled by another mod's injection: it runs in its own right
 * during the datapack reload. It reads the {@code recipes} directory the same way vanilla does, so the
 * editor gets the exact source of every datapack recipe regardless of who ends up loading them into the
 * game. Recipes with no file — Create's runtime-generated ones, anything KubeJS or CraftTweaker builds in
 * code — are not here and never can be; those are the reconstruction case, handled elsewhere.
 */
public final class RecipeReloadListener extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {
    /** The datapack directory recipes live in — {@code recipes} on 1.20.1, {@code recipe} on 1.21.1. */
    private static final String DIRECTORY = "recipes";
    private static final String SUFFIX = ".json";

    public static final ResourceLocation ID = ResourceLocation.tryParse("sce:recipe_source");

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> parsed = new HashMap<>();
        String prefix = DIRECTORY + "/";
        int prefixLength = prefix.length();
        int suffixLength = SUFFIX.length();
        for (Map.Entry<ResourceLocation, Resource> entry :
                manager.listResources(DIRECTORY, path -> path.getPath().endsWith(SUFFIX)).entrySet()) {
            ResourceLocation file = entry.getKey();
            String path = file.getPath();
            // data/<namespace>/recipes/<path>.json  ->  recipe id <namespace>:<path>
            ResourceLocation recipeId = ResourceLocation.tryParse(
                    file.getNamespace() + ":" + path.substring(prefixLength, path.length() - suffixLength));
            if (recipeId == null) {
                continue;
            }
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                if (json != null && json.isJsonObject()) {
                    parsed.put(recipeId, json);
                }
            } catch (Exception e) {
                SceDebug.log(SceDebug.Category.RELOAD, "Could not read recipe source '{}': {}", recipeId, e.toString());
            }
        }
        return parsed;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> parsed, ResourceManager manager, ProfilerFiller profiler) {
        SimpleCraftEditor.LOGGER.info("Read {} recipe sources from datapacks.", parsed.size());
        RecipeStateManager.INSTANCE.onDatapackRecipesScanned(parsed);
    }
}
