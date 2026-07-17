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
    private Consumer<MinecraftServer> changeListener;

    private RecipeStateManager() {
    }

    /** Set by the networking layer so client editor state is re-synced after every mutation. */
    public void setChangeListener(Consumer<MinecraftServer> listener) {
        this.changeListener = listener;
    }

    public RecipeState state() {
        if (state == null) {
            state = RecipeStore.load();
        }
        return state;
    }

    /** Called from {@code RecipeManagerMixin} once vanilla has finished loading the datapack recipes. */
    public void onRecipesReloaded(RecipeManager manager, Map<ResourceLocation, JsonElement> rawRecipes) {
        RecipeState s = state();
        rawJsonCache.clear();
        rawJsonCache.putAll(rawRecipes);
        baseSnapshot = List.copyOf(manager.getRecipes());
        applyTo(manager);
        SimpleCraftEditor.LOGGER.info("Applied recipe state: {} disabled, {} generated (from {} base recipes).",
                s.disabled().size(), s.generated().size(), baseSnapshot.size());
    }

    private void applyTo(RecipeManager manager) {
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
            Recipe<?> parsed = parse(entry.getKey(), entry.getValue());
            if (parsed != null) {
                result.add(parsed);
            }
        }
        manager.replaceRecipes(result);
    }

    // ------------------------------------------------------------------ runtime mutations (server thread)

    public boolean disable(MinecraftServer server, ResourceLocation id) {
        RecipeState s = state();
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
        if (!state().enable(id)) {
            return false;
        }
        reapplyAndSync(server, server.getRecipeManager());
        return true;
    }

    /** Stores an authored recipe (create or edit-as-override), rejecting JSON that does not parse. */
    public boolean saveGenerated(MinecraftServer server, ResourceLocation id, JsonObject json) {
        try {
            RecipeManager.fromJson(id, json);
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Rejected authored recipe '{}': {}", id, e.getMessage());
            return false;
        }
        state().putGenerated(id, json);
        reapplyAndSync(server, server.getRecipeManager());
        return true;
    }

    public JsonObject rawJson(ResourceLocation id) {
        JsonElement raw = rawJsonCache.get(id);
        return raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : null;
    }

    /** JSON to prefill the editor with: a generated recipe's own definition, else the datapack original. */
    public JsonObject editorJson(ResourceLocation id) {
        JsonObject generated = state().generated().get(id);
        return generated != null ? generated : rawJson(id);
    }

    /** Result stack of a currently-loaded recipe, or {@link ItemStack#EMPTY} if absent. */
    public ItemStack resultOf(MinecraftServer server, ResourceLocation id) {
        return server.getRecipeManager().byKey(id)
                .map(recipe -> recipe.getResultItem(server.registryAccess()))
                .orElse(ItemStack.EMPTY);
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
            return RecipeManager.fromJson(id, json);
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Skipping recipe '{}' that could not be parsed: {}", id, e.getMessage());
            state().markBroken(id);
            return null;
        }
    }

    /** Clears session-scoped caches when a server stops (so singleplayer world switches start clean). */
    public void onServerStopped() {
        rawJsonCache.clear();
        baseSnapshot = List.of();
        state = null; // re-read from the global config on next use
    }
}
