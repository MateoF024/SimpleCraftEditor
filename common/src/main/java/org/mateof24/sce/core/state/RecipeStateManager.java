package org.mateof24.sce.core.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.edit.RecipeDraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Authoritative, server-side coordinator that applies the persisted {@link RecipeState} to the live
 * {@link RecipeManager}. Both crafting and every recipe viewer read from that manager, so removing a
 * recipe here removes it everywhere (JEI, EMI and the game itself), and injecting one adds it everywhere.
 *
 * <p>The set of datapack-loaded recipes is snapshotted on every reload ({@link #baseSnapshot}) so that
 * runtime edits (disable/enable/…) always recompute from the original set rather than from an
 * already-filtered one — this is what lets a recipe be re-enabled precisely within the same session.
 */
public final class RecipeStateManager {
    public static final RecipeStateManager INSTANCE = new RecipeStateManager();

    private RecipeState state;
    private final Map<ResourceLocation, JsonElement> rawJsonCache = new HashMap<>();
    private List<Recipe<?>> baseSnapshot = List.of();
    /**
     * Whether {@link #baseSnapshot} was ever actually captured. Distinct from "empty": a real reload with
     * zero recipes and a reload that never ran both leave the list empty, and the difference is the whole
     * safety of {@link #applyTo}. False means we hold no picture of the base set, so replacing the live
     * recipes with our filtered list would wipe everything the game loaded — which is exactly what happened
     * under KubeJS, whose recipe hook cancels the vanilla load our capture was riding on.
     */
    private boolean baseCaptured;
    private Consumer<MinecraftServer> changeListener;
    /** The running server, so the reload listener can re-apply state after the recipes finish loading. */
    private MinecraftServer server;

    private RecipeStateManager() {
    }

    /** Set by the networking layer so client editor state is re-synced after every mutation. */
    public void setChangeListener(Consumer<MinecraftServer> listener) {
        this.changeListener = listener;
    }

    /** Held from server start so the reload listener can re-apply state once recipes are loaded. */
    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Applies our state once the server has fully started. The reload listener covers later {@code /reload}s,
     * but on the initial load it can fire before the server reference exists (the first datapack load runs
     * during world setup), so nothing re-applied and generated recipes were missing until the first edit.
     * By SERVER_STARTED the recipes are loaded — including by any mod that took the load over — so applying
     * now lands our disabled/generated set on top of the finished recipe manager.
     */
    public void onServerStarted(MinecraftServer server) {
        this.server = server;
        RecipeManager manager = server.getRecipeManager();
        applyTo(manager);
        server.getPlayerList().broadcastAll(new ClientboundUpdateRecipesPacket(manager.getRecipes()));
    }

    /**
     * Called by {@link RecipeReloadListener} once the recipe source JSON has been read from the datapacks.
     * This is the capture path that survives KubeJS. It refreshes the source cache and, since a reload has
     * just replaced the recipe set, forgets the old base snapshot and re-applies our disabled/generated
     * state onto the freshly loaded recipes — on the server thread, after the reload has fully settled.
     */
    public void onDatapackRecipesScanned(Map<ResourceLocation, JsonElement> sources) {
        SceDebug.reportEnvironment();
        rawJsonCache.clear();
        rawJsonCache.putAll(sources);
        baseCaptured = false; // a reload replaced the recipes; the next apply must snapshot them anew
        SceDebug.log(SceDebug.Category.RELOAD, "Recipe sources scanned: {} entries", sources.size());
        if (server != null) {
            // Defer to the server thread: the reload is still finishing, and the recipe manager must be
            // fully populated (by vanilla or by whoever took over) before we snapshot and re-apply.
            MinecraftServer captured = server;
            captured.execute(() -> {
                RecipeManager manager = captured.getRecipeManager();
                applyTo(manager);
                captured.getPlayerList().broadcastAll(new ClientboundUpdateRecipesPacket(manager.getRecipes()));
                if (changeListener != null) {
                    changeListener.accept(captured);
                }
            });
        }
    }

    public RecipeState state() {
        if (state == null) {
            state = RecipeStore.load();
        }
        return state;
    }

    private void applyTo(RecipeManager manager) {
        if (!baseCaptured) {
            // Our reload hook never ran — another mod (KubeJS) took over recipe loading and cancelled the
            // vanilla load our capture rode on. Rather than replace the live recipes with only our
            // generated ones — which is exactly the wipe that destroyed a pack's recipes — treat the live
            // set as the base. At the first edit after a reload it is the full, unfiltered set the game
            // just loaded, which is what a snapshot would have held anyway. This both prevents the wipe and
            // lets disable/generate work under KubeJS.
            baseSnapshot = List.copyOf(manager.getRecipes());
            baseCaptured = true;
            SceDebug.log(SceDebug.Category.RELOAD,
                    "Base not captured by the reload hook; falling back to the live set of {} recipes.",
                    baseSnapshot.size());
        }
        RecipeState s = state();
        List<Recipe<?>> result = new ArrayList<>(baseSnapshot.size());
        for (Recipe<?> recipe : baseSnapshot) {
            ResourceLocation id = recipe.getId();
            if (s.isDisabled(id)) {
                continue;
            }
            if (s.isGenerated(id)) {
                continue; // a generated entry with the same id overrides the base recipe
            }
            result.add(recipe);
        }
        for (Map.Entry<ResourceLocation, JsonObject> entry : s.generated().entrySet()) {
            if (s.isGeneratedDisabled(entry.getKey())) {
                continue; // a generated recipe that is toggled off is kept but not injected
            }
            Recipe<?> parsed = parse(entry.getKey(), entry.getValue());
            if (parsed != null) {
                result.add(parsed);
            }
        }
        int injected = result.size() - (baseSnapshot.size() - s.disabled().size());
        SceDebug.log(SceDebug.Category.RELOAD, "applyTo: base={}, disabled={}, generated={} -> result={}",
                baseSnapshot.size(), s.disabled().size(), s.generated().size(), result.size());
        manager.replaceRecipes(result);
        SceDebug.log(SceDebug.Category.RELOAD, "replaceRecipes done: live now={} ({} generated injected)",
                manager.getRecipes().size(), injected);
    }

    // ------------------------------------------------------------------ runtime mutations (server thread)

    public boolean disable(MinecraftServer server, ResourceLocation id) {
        RecipeState s = state();
        // A generated recipe is toggled off in place rather than added to the datapack-disabled set,
        // which would leave it both injected and "disabled" (the duplicate bug).
        if (s.isGenerated(id)) {
            boolean changed = s.disabled().remove(id) != null; // clean any stray datapack-disabled entry
            if (!s.isGeneratedDisabled(id)) {
                s.setGeneratedDisabled(id, true);
                changed = true;
            }
            if (changed) {
                reapplyAndSync(server, server.getRecipeManager());
            }
            return changed;
        }
        if (s.isDisabled(id)) {
            return false;
        }
        RecipeManager manager = server.getRecipeManager();
        if (manager.byKey(id).isEmpty()) {
            return false; // no such recipe currently loaded
        }
        JsonElement raw = rawJsonCache.get(id);
        s.disable(id, raw != null && raw.isJsonObject() ? raw.getAsJsonObject().deepCopy() : null);
        reapplyAndSync(server, manager);
        return true;
    }

    public boolean enable(MinecraftServer server, ResourceLocation id) {
        RecipeState s = state();
        boolean changed = false;
        if (s.isGeneratedDisabled(id)) {
            s.setGeneratedDisabled(id, false);
            changed = true;
        }
        if (s.enable(id)) { // removes any datapack-disabled entry (also cleans legacy duplicates)
            changed = true;
        }
        if (changed) {
            reapplyAndSync(server, server.getRecipeManager());
        }
        return changed;
    }

    /** Stores an authored recipe (create or edit-as-override), rejecting JSON that does not parse. */
    /**
     * Why this recipe cannot be stored, or null if it can. A cooking recipe with no time loads and even
     * crafts, so the recipe loader raises nothing, but no viewer can draw it: EMI divides by that time to
     * animate its progress arrow and throws. Refusing it here reports the real problem to whoever is
     * authoring it instead of substituting a time they did not pick.
     */
    private String rejectionReason(JsonObject json) {
        if (!json.has("type")) {
            return null;
        }
        RecipeDraft.Cooking cooking = RecipeDraft.Cooking.fromType(json.get("type").getAsString());
        if (cooking == null) {
            return null;
        }
        try {
            if (json.has("cookingtime") && json.get("cookingtime").getAsInt() > 0) {
                return null;
            }
        } catch (RuntimeException e) {
            return "cooking time must be a whole number greater than 0";
        }
        return "cooking time must be greater than 0";
    }

    public boolean saveGenerated(MinecraftServer server, ResourceLocation id, JsonObject json) {
        SceDebug.dump(SceDebug.Category.EDIT, () -> "saveGenerated '" + id + "': " + json);
        String rejection = rejectionReason(json);
        if (rejection != null) {
            SimpleCraftEditor.LOGGER.warn("Rejected authored recipe '{}': {}", id, rejection);
            return false;
        }
        try {
            RecipeManager.fromJson(id, json);
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Rejected authored recipe '{}': {}", id, e.getMessage());
            return false;
        }
        state().putGenerated(id, json);
        reapplyAndSync(server, server.getRecipeManager());
        SceDebug.log(SceDebug.Category.EDIT, "Saved '{}'; baseSnapshot={}, generated={}",
                id, baseSnapshot.size(), state().generated().size());
        return true;
    }

    public JsonObject rawJson(ResourceLocation id) {
        JsonElement raw = rawJsonCache.get(id);
        return raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : null;
    }

    /** How many datapack recipe sources were captured — zero here is the load-a-recipe bug. */
    public int rawJsonCacheSize() {
        return rawJsonCache.size();
    }

    public int baseSnapshotSize() {
        return baseSnapshot.size();
    }

    /** JSON to prefill the editor with: a generated recipe's own definition, else the datapack original. */
    public JsonObject editorJson(ResourceLocation id) {
        JsonObject generated = state().generated().get(id);
        JsonObject result = generated != null ? generated : rawJson(id);
        SceDebug.log(SceDebug.Category.EDIT, "editorJson '{}': source={}, rawCacheSize={}",
                id, generated != null ? "generated" : (result != null ? "rawCache" : "NONE (opens empty)"),
                rawJsonCache.size());
        return result;
    }

    /** Result stack of a currently-loaded recipe, or {@link ItemStack#EMPTY} if absent. */
    public ItemStack resultOf(MinecraftServer server, ResourceLocation id) {
        return server.getRecipeManager().byKey(id)
                .map(recipe -> recipe.getResultItem(server.registryAccess()))
                .orElse(ItemStack.EMPTY);
    }

    /** Result stack of a generated recipe from its stored JSON (works even while it is toggled off). */
    public ItemStack generatedResultOf(MinecraftServer server, ResourceLocation id) {
        JsonObject json = state().generated().get(id);
        if (json == null) {
            return ItemStack.EMPTY;
        }
        try {
            return RecipeManager.fromJson(id, json).getResultItem(server.registryAccess());
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** True if a datapack recipe with this id existed before our edits (a generated recipe is then an edit). */
    public boolean wasBaseRecipe(ResourceLocation id) {
        for (Recipe<?> recipe : baseSnapshot) {
            if (recipe.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public boolean cloneRecipe(MinecraftServer server, ResourceLocation source, ResourceLocation target) {
        JsonElement raw = rawJsonCache.get(source);
        if (raw == null || !raw.isJsonObject()) {
            return false;
        }
        state().putGenerated(target, raw.getAsJsonObject().deepCopy());
        reapplyAndSync(server, server.getRecipeManager());
        return true;
    }

    public boolean deleteGenerated(MinecraftServer server, ResourceLocation id) {
        if (!state().removeGenerated(id)) {
            return false;
        }
        reapplyAndSync(server, server.getRecipeManager());
        return true;
    }

    public void forceReapply(MinecraftServer server) {
        reapplyAndSync(server, server.getRecipeManager());
    }

    private void reapplyAndSync(MinecraftServer server, RecipeManager manager) {
        applyTo(manager);
        server.getPlayerList().broadcastAll(new ClientboundUpdateRecipesPacket(manager.getRecipes()));
        RecipeStore.save(state());
        if (changeListener != null) {
            changeListener.accept(server);
        }
    }

    private Recipe<?> parse(ResourceLocation id, JsonObject json) {
        try {
            repairCookingTime(id, json);
            return RecipeManager.fromJson(id, json);
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Skipping recipe '{}' that could not be parsed: {}", id, e.getMessage());
            state().markBroken(id);
            return null;
        }
    }

    /**
     * Gives a stored cooking recipe a usable cooking time. The editor used to save these with a time of
     * zero, which crafts but makes a recipe viewer divide by it to animate its progress arrow — EMI throws
     * and draws "Error Rendering" instead of the recipe. Recipes written back then are still on disk, so
     * they are repaired here rather than only on the next save.
     */
    private void repairCookingTime(ResourceLocation id, JsonObject json) {
        if (!json.has("type")) {
            return;
        }
        RecipeDraft.Cooking cooking = RecipeDraft.Cooking.fromType(json.get("type").getAsString());
        if (cooking == null) {
            return;
        }
        int time = json.has("cookingtime") ? json.get("cookingtime").getAsInt() : 0;
        if (time > 0) {
            return;
        }
        json.addProperty("cookingtime", cooking.defaultTime);
        SimpleCraftEditor.LOGGER.info("Gave recipe '{}' the default cooking time of {} ticks; it had none",
                id, cooking.defaultTime);
    }

    /** Clears session-scoped caches when a server stops (so singleplayer world switches start clean). */
    public void onServerStopped() {
        rawJsonCache.clear();
        baseSnapshot = List.of();
        baseCaptured = false;
        server = null;
        state = null; // re-read from the global config on next use
    }
}
