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
import org.mateof24.sce.core.compat.PolymorphRecipeSelection;
import org.mateof24.sce.core.edit.RecipeDraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Authoritative, server-side coordinator that applies the persisted {@link RecipeState} to the live
 * {@link RecipeManager}. Both crafting and every recipe viewer read from that manager, so removing a
 * recipe here removes it everywhere (JEI, EMI and the game itself), and injecting one adds it everywhere.
 *
 * <p>Edits take effect two ways. On a datapack load the raw recipe JSON is edited before anything reads
 * it ({@link #beforeRecipeLoad}), so the pack loads already-correct. Between loads an edit is applied to
 * the live manager ({@link #reapplyAndSync}), rebuilding from the pack's own recipes rather than from an
 * already-filtered set — which is what lets a recipe be re-enabled precisely within the same session.
 *
 * <p>Neither path reloads datapacks. When to reload a pack is the server owner's call, so recipe viewers
 * refresh on the next {@code /reload} they run, exactly as the readme describes.
 *
 * <p>On 1.21.1 recipes are wrapped in {@link RecipeHolder} and are (de)serialized with the registry-aware
 * {@link Recipe#CODEC} rather than the old Gson serializers, so we hold on to the {@link HolderLookup.Provider}
 * captured at reload time to decode our stored/generated recipe JSON.
 */
public final class RecipeStateManager {
    public static final RecipeStateManager INSTANCE = new RecipeStateManager();

    private RecipeState state;
    private final Map<ResourceLocation, JsonElement> rawJsonCache = new HashMap<>();
    /**
     * The generated recipe ids added to the last recipe load. Subtracting these from the live set is what
     * recovers the pack's own recipes exactly — see {@link #pureBase}.
     */
    private final Set<ResourceLocation> injectedIds = new HashSet<>();
    /** The pack's recipes without ours, worked out once per load and reused by every edit after it. */
    private List<RecipeHolder<?>> pureBase = List.of();
    private boolean pureBaseKnown;
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
                "Recipes loading: {} in the pack. {} of our own are already in there before we add them (should be 0).",
                map.size(), alreadyPresent);
        rawJsonCache.clear();
        rawJsonCache.putAll(map);
        // A new load replaces the recipe set, so anything worked out from the old one is stale.
        injectedIds.clear();
        pureBase = List.of();
        pureBaseKnown = false;

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
                injectedIds.add(entry.getKey());
                added++;
                if (entry.getValue().equals(map.get(entry.getKey()))) {
                    verified++;
                } else {
                    SceDebug.log(SceDebug.Category.RELOAD,
                            "  Could not add '{}' - another mod is refusing changes to the recipe list.", entry.getKey());
                }
            } catch (Exception e) {
                SceDebug.log(SceDebug.Category.RELOAD,
                        "  Failed to add '{}': {}", entry.getKey(), e.toString());
            }
        }
        SceDebug.log(SceDebug.Category.RELOAD,
                "Recipes ready: {} from the pack, {} disabled by us removed, {} of ours added ({} confirmed).",
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

    /** How many of the pack's own recipes we rebuild from — zero until the first edit works it out. */
    public int baseSnapshotSize() {
        return pureBase.size();
    }

    /** JSON to prefill the editor with: a generated recipe's own definition, else the datapack original. */
    public JsonObject editorJson(MinecraftServer server, ResourceLocation id) {
        JsonObject generated = state().generated().get(id);
        if (generated != null) {
            SceDebug.log(SceDebug.Category.EDIT, "Opening '{}' from the version you authored", id);
            return generated;
        }
        JsonObject raw = rawJson(id);
        if (raw != null) {
            SceDebug.log(SceDebug.Category.EDIT, "Opening '{}' from its datapack file", id);
            return raw;
        }
        // No file behind it: written by a script, or built in code. Read it back out of the loaded recipe
        // instead, so where a recipe came from stops deciding whether it can be opened.
        JsonObject rebuilt = server.getRecipeManager().byKey(id).map(this::serialize).orElse(null);
        SceDebug.log(SceDebug.Category.EDIT, "Opening '{}': no file for it, so {}",
                id, rebuilt != null ? "it was read back from the loaded recipe" : "it cannot be opened");
        return rebuilt;
    }

    /**
     * Writes a loaded recipe back out as the datapack JSON that would have produced it.
     *
     * <p>Not every recipe comes from a file. KubeJS and CraftTweaker build theirs from a script, mods
     * build theirs in code, and Create generates some at runtime — none of those exist as JSON anywhere,
     * so the editor, which reads the datapack source, had nothing to open. Here the same registry-aware
     * codec that reads a recipe writes it back, so any type at all can be described, whatever made it.
     */
    private JsonObject serialize(RecipeHolder<?> holder) {
        if (registries == null) {
            return null;
        }
        try {
            RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
            JsonElement json = Recipe.CODEC.encodeStart(ops, holder.value()).getOrThrow(JsonParseException::new);
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (Exception e) {
            SceDebug.log(SceDebug.Category.EDIT, "Could not read '{}' back from the loaded recipe: {}",
                    holder.id(), e.toString());
            return null;
        }
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

    /**
     * The pack's own recipes, without the ones we added. See the 1.20.1 mirror for why the subtraction is
     * keyed on the ids we injected at load time rather than on what is generated right now.
     */
    private List<RecipeHolder<?>> pureBase(RecipeManager manager) {
        if (pureBaseKnown) {
            return pureBase;
        }
        Map<ResourceLocation, RecipeHolder<?>> base = new LinkedHashMap<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!injectedIds.contains(holder.id())) {
                base.put(holder.id(), holder);
            }
        }
        // A recipe that was already disabled when the pack loaded was taken out of the load, so it is not
        // in the live set to be found here. It still belongs in the base — see the 1.20.1 mirror.
        int recovered = 0;
        for (ResourceLocation id : state().disabled().keySet()) {
            if (base.containsKey(id)) {
                continue;
            }
            JsonElement raw = rawJsonCache.get(id);
            if (raw == null || !raw.isJsonObject()) {
                continue;
            }
            RecipeHolder<?> parsed = parse(id, raw.getAsJsonObject().deepCopy());
            if (parsed != null) {
                base.put(id, parsed);
                recovered++;
            }
        }
        pureBase = List.copyOf(base.values());
        pureBaseKnown = true;
        SceDebug.log(SceDebug.Category.RELOAD,
                "The pack's own recipes: {} ({} of the {} loaded were ours, {} disabled ones read back in)",
                pureBase.size(), injectedIds.size(), manager.getRecipes().size(), recovered);
        return pureBase;
    }

    /**
     * Applies the current state to the live recipes and tells everyone. Deliberately does <em>not</em>
     * reload datapacks — see the 1.20.1 mirror.
     */
    private void reapplyAndSync(MinecraftServer server, RecipeManager manager) {
        RecipeState s = state();
        List<RecipeHolder<?>> base = pureBase(manager);
        // Keyed by id rather than a plain list: replaceRecipes throws on a duplicate id and, because it
        // builds the new maps before assigning them, a throw leaves the live recipes completely untouched
        // — the edit would silently do nothing until someone reloaded. Keying makes that impossible.
        Map<ResourceLocation, RecipeHolder<?>> result = new LinkedHashMap<>();
        for (RecipeHolder<?> holder : base) {
            ResourceLocation id = holder.id();
            if (s.isDisabled(id) || s.isGenerated(id)) {
                continue; // disabled, or about to be replaced by our own version of the same id
            }
            result.put(id, holder);
        }
        // An id we injected is missing from the base, so if it was an edit of a pack recipe and that edit is
        // now gone, the pack's own version has to come back — otherwise deleting an edit would delete the
        // recipe it edited.
        int restored = 0;
        for (ResourceLocation id : injectedIds) {
            if (s.isGenerated(id) || s.isDisabled(id)) {
                continue;
            }
            JsonElement original = rawJsonCache.get(id);
            if (original == null || !original.isJsonObject()) {
                continue;
            }
            RecipeHolder<?> parsed = parse(id, original.getAsJsonObject().deepCopy());
            if (parsed != null) {
                result.put(id, parsed);
                restored++;
            }
        }
        for (Map.Entry<ResourceLocation, JsonObject> entry : s.generated().entrySet()) {
            if (s.isGeneratedDisabled(entry.getKey())) {
                continue;
            }
            RecipeHolder<?> parsed = parse(entry.getKey(), entry.getValue());
            if (parsed != null) {
                result.put(entry.getKey(), parsed);
            }
        }
        SceDebug.log(SceDebug.Category.EDIT,
                "Applying: {} pack recipes - {} disabled + {} of ours + {} restored originals = {} live",
                base.size(), s.disabled().size(), s.generated().size(), restored, result.size());
        try {
            manager.replaceRecipes(result.values());
        } catch (Exception e) {
            // Never silently: if the recipe set cannot be swapped, the edit did not happen, and whoever
            // made it needs to see why rather than watch it appear to work and then not.
            SimpleCraftEditor.LOGGER.error("Could not apply the recipe edit to the running game", e);
            return;
        }
        invalidateDerivedCaches(manager);
        PolymorphRecipeSelection.clearRemembered(server);
        server.getPlayerList().broadcastAll(new ClientboundUpdateRecipesPacket(manager.getRecipes()));
        RecipeStore.save(s);
        reportApplied(manager, result.size());
        if (changeListener != null) {
            changeListener.accept(server);
        }
    }

    /**
     * Empties any lookup a mod has built on top of the recipe manager, because the recipes it was built
     * from have just been replaced.
     *
     * <p>{@code replaceRecipes} rewrites the two maps the game itself reads, and nothing more. Performance
     * mods commonly index those maps once into a faster structure and rebuild it only when the datapacks
     * reload — which is the whole point of the optimisation, and which our edits do not do. Crafting then
     * keeps answering from the old index: a recipe that was deleted still crafts, a new one is unknown,
     * and the edit looks ignored even though it landed. The only way out was a reload, which is exactly
     * the thing we will not force on someone's server.
     *
     * <p>Nothing here names a mod. An index like that lives on the recipe manager instance, either in a
     * subclass of it or in a field a mixin added, and either is recognisable without knowing whose it is:
     * a subclass field is declared below {@link RecipeManager}, and a mixin-added field carries a
     * {@code $} in its name, which no field of the game ever does. Both are cleared rather than replaced,
     * so a lazily-built index simply rebuilds itself on the next lookup, and anything that turns out not
     * to be clearable is left exactly as it was.
     */
    private void invalidateDerivedCaches(RecipeManager manager) {
        int cleared = 0;
        for (Class<?> type = manager.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            boolean vanilla = type == RecipeManager.class;
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (vanilla && field.getName().indexOf('$') < 0) {
                    continue; // the game's own fields, including the two replaceRecipes just rewrote
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                        map.clear();
                        cleared++;
                    } else if (value instanceof java.util.Collection<?> collection && !collection.isEmpty()) {
                        collection.clear();
                        cleared++;
                    }
                } catch (Throwable ignored) {
                    // Immutable, inaccessible or simply not a cache. Leaving it alone is always safe.
                }
            }
            if (vanilla) {
                break;
            }
        }
        if (cleared > 0) {
            SceDebug.log(SceDebug.Category.COMPAT,
                    "Cleared {} recipe lookup(s) another mod had built on {}, so it rebuilds from the edited recipes",
                    cleared, manager.getClass().getName());
        }
    }

    /**
     * Checks that the recipe manager now holds what the edit said it should, and says so in a plain log
     * line regardless of the debug switch. See the 1.20.1 mirror for why this is not optional: it is what
     * separates our own bug from another mod caching the recipe list behind us.
     */
    private void reportApplied(RecipeManager manager, int expected) {
        RecipeState s = state();
        int live = manager.getRecipes().size();
        List<String> wrong = new ArrayList<>();
        for (ResourceLocation id : s.disabled().keySet()) {
            if (manager.byKey(id).isPresent()) {
                wrong.add("still present although disabled: " + id);
            }
        }
        for (ResourceLocation id : s.generated().keySet()) {
            boolean present = manager.byKey(id).isPresent();
            if (s.isGeneratedDisabled(id) && present) {
                wrong.add("still present although turned off: " + id);
            } else if (!s.isGeneratedDisabled(id) && !present) {
                wrong.add("missing although it should be there: " + id);
            }
        }
        if (wrong.isEmpty() && live == expected) {
            SimpleCraftEditor.LOGGER.info("Recipe edit applied to the running game: {} recipes now loaded.", live);
            return;
        }
        SimpleCraftEditor.LOGGER.warn("Recipe edit did not fully apply: expected {} recipes, the game has {}. {}",
                expected, live, wrong.isEmpty() ? "" : String.join("; ", wrong));
    }

    /** Re-parses a stored recipe, repairing an old cooking time; null (and logged) if it will not parse. */
    private RecipeHolder<?> parse(ResourceLocation id, JsonObject json) {
        try {
            repairCookingTime(id, json);
            return deserialize(id, json);
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Skipping recipe '{}' that could not be parsed: {}", id, e.getMessage());
            state().markBroken(id);
            return null;
        }
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
