package org.mateof24.sce.core.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
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
 *
 * <p>On 1.21.1 recipes are wrapped in {@link RecipeHolder} and are (de)serialized with the registry-aware
 * {@link Recipe#CODEC} rather than the old Gson serializers, so we hold on to the {@link HolderLookup.Provider}
 * captured at reload time to decode our stored/generated recipe JSON.
 */
public final class RecipeStateManager {
    public static final RecipeStateManager INSTANCE = new RecipeStateManager();

    private RecipeState state;
    private final Map<ResourceLocation, JsonElement> rawJsonCache = new HashMap<>();
    private HolderLookup.Provider registries;
    private Consumer<MinecraftServer> changeListener;

    private RecipeStateManager() {
    }

    /** Set by the networking layer so client editor state is re-synced after every mutation. */
    public void setChangeListener(Consumer<MinecraftServer> listener) {
        this.changeListener = listener;
    }

    /**
     * Edits the raw recipe map before anything loads it, from {@link org.mateof24.sce.mixin.RecipeManagerMixin}
     * at the head of {@code RecipeManager.apply}, ahead of KubeJS's own head hook. See the 1.20.1 mirror:
     * removing disabled ids and adding generated JSON here means the loaded set is already the edited one,
     * in place before the game or any viewer indexes it. No parsing happens here, so no registries needed.
     */
    public void beforeRecipeLoad(Map<ResourceLocation, JsonElement> map) {
        SceDebug.reportEnvironment();
        RecipeState s = state();
        long alreadyPresent = s.generated().keySet().stream().filter(map::containsKey).count();
        SceDebug.log(SceDebug.Category.RELOAD,
                "beforeRecipeLoad ENTER: {} entries in {} (mapId={}), {} of our generated ids already present",
                map.size(), map.getClass().getName(), System.identityHashCode(map), alreadyPresent);
        rawJsonCache.clear();
        rawJsonCache.putAll(map);

        int removed = 0;
        for (ResourceLocation id : s.disabled().keySet()) {
            if (map.remove(id) != null) {
                removed++;
            }
        }
        int added = 0;
        int verified = 0;
        for (Map.Entry<ResourceLocation, JsonObject> entry : s.generated().entrySet()) {
            if (s.isGeneratedDisabled(entry.getKey())) {
                continue;
            }
            try {
                repairCookingTime(entry.getKey(), entry.getValue());
                map.put(entry.getKey(), entry.getValue());
                added++;
                if (entry.getValue().equals(map.get(entry.getKey()))) {
                    verified++;
                } else {
                    SceDebug.log(SceDebug.Category.RELOAD,
                            "  WARNING: '{}' did not survive being put into the recipe map", entry.getKey());
                }
            } catch (Exception e) {
                SceDebug.log(SceDebug.Category.RELOAD,
                        "  ERROR putting '{}' into the recipe map: {}", entry.getKey(), e.toString());
            }
        }
        SceDebug.log(SceDebug.Category.RELOAD,
                "beforeRecipeLoad DONE: {} sources, removed {} disabled, added {} generated ({} verified in map)",
                rawJsonCache.size(), removed, added, verified);
    }

    /**
     * Reports, for each generated recipe, whether it actually reached the live recipe manager. Diagnostic
     * only, driven by {@code /sce debug verify}.
     */
    public String verifyGeneratedInManager(MinecraftServer server) {
        RecipeManager manager = server.getRecipeManager();
        StringBuilder sb = new StringBuilder("Generated recipes vs live manager (" + manager.getRecipes().size() + " live):");
        RecipeState s = state();
        if (s.generated().isEmpty()) {
            sb.append("\n  (none authored)");
        }
        for (ResourceLocation id : s.generated().keySet()) {
            boolean present = manager.byKey(id).isPresent();
            boolean off = s.isGeneratedDisabled(id);
            sb.append("\n  ").append(present ? "PRESENT" : "MISSING")
                    .append(off ? " (toggled off)" : "").append(" - ").append(id);
        }
        return sb.toString();
    }

    public RecipeState state() {
        if (state == null) {
            state = RecipeStore.load();
        }
        return state;
    }

    /**
     * Reports where a given recipe id stands right now — see the 1.20.1 mirror. Driven by
     * {@code /sce debug find}, to pin a recipe that keeps crafting after being deleted.
     */
    public String findRecipe(MinecraftServer server, ResourceLocation id) {
        RecipeManager manager = server.getRecipeManager();
        RecipeState s = state();
        StringBuilder sb = new StringBuilder("Recipe '" + id + "':");

        // The manager keeps two structures that can disagree: byKey reads the byName map, while crafting
        // and getRecipes read the by-type map. See the 1.20.1 mirror.
        boolean inByName = manager.byKey(id).isPresent();
        RecipeHolder<?> inByType = null;
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (holder.id().equals(id)) {
                inByType = holder;
                break;
            }
        }
        sb.append("\n  byName (byKey): ").append(inByName ? "PRESENT" : "absent");
        sb.append("\n  by-type set (what crafting reads): ").append(inByType != null ? "PRESENT" : "absent");
        if (inByName != (inByType != null)) {
            sb.append("\n  *** THE TWO DISAGREE — that is the bug ***");
        }

        sb.append("\n  our generated set: ").append(s.isGenerated(id) ? "yes" : "no")
                .append(s.isGeneratedDisabled(id) ? " (toggled off)" : "");
        sb.append("\n  our disabled set: ").append(s.isDisabled(id) ? "yes" : "no");
        sb.append("\n  raw source cache (datapack): ").append(rawJsonCache.containsKey(id) ? "yes" : "no");

        // A ghost craft may be another recipe making the same item, so name every one that does.
        RecipeHolder<?> known = inByType != null ? inByType : manager.byKey(id).orElse(null);
        if (known != null) {
            ItemStack result = known.value().getResultItem(server.registryAccess());
            sb.append("\n  result item: ").append(result);
            sb.append("\n  other live recipes producing the same item:");
            int others = 0;
            for (RecipeHolder<?> holder : manager.getRecipes()) {
                if (holder.id().equals(id)) {
                    continue;
                }
                ItemStack out = holder.value().getResultItem(server.registryAccess());
                if (!out.isEmpty() && ItemStack.isSameItem(out, result)) {
                    sb.append("\n    - ").append(holder.id()).append(" (").append(holder.value().getType()).append(')');
                    others++;
                }
            }
            if (others == 0) {
                sb.append(" none");
            }
        }
        sb.append("\n  live totals: by-type ").append(manager.getRecipes().size())
                .append(", ids ").append(manager.getRecipeIds().count());
        return sb.toString();
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
            deserialize(id, json);
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Rejected authored recipe '{}': {}", id, e.getMessage());
            return false;
        }
        state().putGenerated(id, json);
        reapplyAndSync(server, server.getRecipeManager());
        SceDebug.log(SceDebug.Category.EDIT, "Saved '{}'; sources={}, generated={}",
                id, rawJsonCache.size(), state().generated().size());
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

    /** Datapack recipe sources captured — the base the reload rebuilds from. */
    public int baseSnapshotSize() {
        return rawJsonCache.size();
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
                .map(holder -> holder.value().getResultItem(server.registryAccess()))
                .orElse(ItemStack.EMPTY);
    }

    /** Result stack of a generated recipe from its stored JSON (works even while it is toggled off). */
    public ItemStack generatedResultOf(MinecraftServer server, ResourceLocation id) {
        JsonObject json = state().generated().get(id);
        if (json == null) {
            return ItemStack.EMPTY;
        }
        try {
            return deserialize(id, json).value().getResultItem(server.registryAccess());
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** True if a datapack recipe with this id existed before our edits (a generated recipe is then an edit). */
    public boolean wasBaseRecipe(ResourceLocation id) {
        return rawJsonCache.containsKey(id);
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
        RecipeStore.save(state());
        // See the 1.20.1 mirror: rebuild the recipe set through a datapack reload rather than replacing
        // recipes on the live manager, so create/disable/delete take effect cleanly under mods that own
        // recipe loading, and every recipe viewer refreshes with the reload.
        SceDebug.log(SceDebug.Category.EDIT, "reapplyAndSync: reloading datapacks to apply recipe state");
        server.reloadResources(server.getPackRepository().getSelectedIds()).thenRun(() -> {
            if (changeListener != null) {
                changeListener.accept(server);
            }
        });
    }

    /** Decodes recipe JSON into a holder with the registry-aware codec; throws if the JSON is invalid. */
    private RecipeHolder<?> deserialize(ResourceLocation id, JsonObject json) {
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        Recipe<?> recipe = Recipe.CODEC.parse(ops, json).getOrThrow(JsonParseException::new);
        return new RecipeHolder<>(id, recipe);
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
        registries = null;
        state = null; // re-read from the global config on next use
    }
}
