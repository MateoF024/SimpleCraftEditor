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
import org.mateof24.sce.core.compat.PolymorphRecipeSelection;
import org.mateof24.sce.core.edit.RecipeDraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
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
    private List<Recipe<?>> pureBase = List.of();
    private boolean pureBaseKnown;
    private Consumer<MinecraftServer> changeListener;

    private RecipeStateManager() {
    }

    /** Set by the networking layer so client editor state is re-synced after every mutation. */
    public void setChangeListener(Consumer<MinecraftServer> listener) {
        this.changeListener = listener;
    }

    /**
     * Edits the raw recipe map before anything loads it, from {@link org.mateof24.sce.mixin.RecipeManagerMixin}
     * at the head of {@code RecipeManager.apply}. This is the capture-and-apply path, and it runs before
     * KubeJS's own head hook (higher mixin priority) so it edits the very map KubeJS or vanilla then builds
     * the recipe set from. Removing a disabled id and adding the generated JSON here means the loaded set is
     * already the edited one — in place before the game or any viewer indexes it, no reload race.
     *
     * <p>The incoming map is also the full datapack recipe source, captured for the editor to load from.
     */
    public void beforeRecipeLoad(Map<ResourceLocation, JsonElement> map) {
        SceDebug.reportEnvironment();
        RecipeState s = state();
        // Retention check: if our own recipes are already in the map before we add them, the map is being
        // reused or re-served by another mod rather than built fresh from the datapacks — which would make
        // an authored recipe permanent. Count the ids we would add that are somehow already here.
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
                // Old recipes saved before cooking-time validation can hold a zero, which crashes a recipe
                // viewer; repair it as it goes in.
                repairCookingTime(entry.getKey(), entry.getValue());
                map.put(entry.getKey(), entry.getValue());
                injectedIds.add(entry.getKey());
                added++;
                // Confirm the put actually took — an immutable map would throw or ignore it.
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
     * Reports, for each generated recipe, whether it actually reached the live recipe manager — the answer
     * to "the editor saved it but does the game have it". Diagnostic only, driven by {@code /sce debug verify}.
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
     * Reports where a given recipe id stands right now — in the live manager, in our source cache, in our
     * generated/disabled sets — regardless of what the editor thinks. Driven by {@code /sce debug find}, so
     * a recipe that keeps crafting after being deleted can be pinned to the server or ruled out.
     */
    public String findRecipe(MinecraftServer server, ResourceLocation id) {
        RecipeManager manager = server.getRecipeManager();
        RecipeState s = state();
        StringBuilder sb = new StringBuilder("Recipe '" + id + "':");

        // The manager keeps two structures that can disagree: byKey reads the byName map, while crafting
        // and getRecipes read the by-type map. A recipe left in one but not the other reports as absent
        // here yet still crafts, so both are checked.
        boolean inByName = manager.byKey(id).isPresent();
        net.minecraft.world.item.crafting.Recipe<?> inByType = null;
        for (net.minecraft.world.item.crafting.Recipe<?> recipe : manager.getRecipes()) {
            if (recipe.getId().equals(id)) {
                inByType = recipe;
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

        // A ghost craft may not be this recipe at all but another one making the same item, so name every
        // live recipe that produces the same result.
        net.minecraft.world.item.crafting.Recipe<?> known =
                inByType != null ? inByType : manager.byKey(id).orElse(null);
        if (known != null) {
            ItemStack result = known.getResultItem(server.registryAccess());
            sb.append("\n  result item: ").append(result);
            sb.append("\n  other live recipes producing the same item:");
            int others = 0;
            for (net.minecraft.world.item.crafting.Recipe<?> recipe : manager.getRecipes()) {
                if (recipe.getId().equals(id)) {
                    continue;
                }
                ItemStack out = recipe.getResultItem(server.registryAccess());
                if (!out.isEmpty() && ItemStack.isSameItem(out, result)) {
                    sb.append("\n    - ").append(recipe.getId()).append(" (").append(recipe.getType()).append(')');
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
        // The refusal lives here as well as at each entry point, so no route reaches a script-written
        // recipe: the callers check first only to be able to explain themselves.
        if (!isEditable(id)) {
            return false;
        }
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
    /**
     * The JSON the editor should open, or null when the recipe has no source we can edit faithfully.
     *
     * <p>A recipe only has a source we can work from if it was written as a file: the pack's own datapack,
     * or an earlier edit of yours. A recipe a script creates has none — see {@link #isEditable}.
     */
    public JsonObject editorJson(ResourceLocation id) {
        JsonObject generated = state().generated().get(id);
        if (generated != null) {
            SceDebug.log(SceDebug.Category.EDIT, "Opening '{}' from the version you authored", id);
            return generated;
        }
        JsonObject raw = rawJson(id);
        SceDebug.log(SceDebug.Category.EDIT, "Opening '{}': {}",
                id, raw != null ? "from its datapack file" : "no file for it, so it cannot be edited");
        return raw;
    }

    /**
     * Whether this recipe can be edited at all.
     *
     * <p>A recipe written as a datapack file can be, because the file is what we change and what the game
     * then loads. A recipe a script creates cannot: KubeJS and CraftTweaker run their scripts on every
     * load and write the result straight into the game afterwards, so the script always has the last word
     * and any change of ours is undone the next time the pack loads. We could fight that, but the outcome
     * depends on load order and on what the script does, and an edit that holds sometimes is worse than
     * one that is refused — a pack author would be building on something that quietly reverts.
     *
     * <p>So it is refused outright and said plainly. The script is the right place to change a recipe that
     * a script made, and nothing here touches anybody's scripts.
     */
    public boolean isEditable(ResourceLocation id) {
        return state().isGenerated(id) || rawJsonCache.containsKey(id);
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
        return rawJsonCache.containsKey(id);
    }

    /**
     * Copies a recipe under a new id. Cloning reads the source's JSON, so a script-written recipe cannot be
     * cloned for the same reason it cannot be edited — there is no JSON to copy.
     */
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
     * The pack's own recipes, without the ones we added.
     *
     * <p>Worked out once per load by taking the live set and removing the ids we injected during
     * {@link #beforeRecipeLoad}. At that moment the live set is exactly the pack's recipes plus ours, so
     * the subtraction is precise.
     *
     * <p>This is the part that used to be wrong. The base was taken as the whole live set, which already
     * contained our injected recipes; deleting one then dropped it from the generated set while it stayed
     * in that base, so it survived as though the pack had shipped it. Keying the subtraction on what we
     * injected — not on what is generated right now — is what makes a deletion actually delete.
     */
    private List<Recipe<?>> pureBase(RecipeManager manager) {
        if (pureBaseKnown) {
            return pureBase;
        }
        Map<ResourceLocation, Recipe<?>> base = new LinkedHashMap<>();
        for (Recipe<?> recipe : manager.getRecipes()) {
            if (!injectedIds.contains(recipe.getId())) {
                base.put(recipe.getId(), recipe);
            }
        }
        // A recipe that was already disabled when the pack loaded was taken out of the load, so it is not
        // in the live set to be found here. It still belongs in the base: the base is what the pack ships,
        // and re-enabling one has to bring it back in the same session, without a reload. Parse it back
        // from the pack's own JSON, which was captured before anything was removed.
        int recovered = 0;
        for (ResourceLocation id : state().disabled().keySet()) {
            if (base.containsKey(id)) {
                continue;
            }
            JsonElement raw = rawJsonCache.get(id);
            if (raw == null || !raw.isJsonObject()) {
                continue;
            }
            Recipe<?> parsed = parse(id, raw.getAsJsonObject().deepCopy());
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
     * Applies the current state to the live recipes and tells everyone.
     *
     * <p>Deliberately does <em>not</em> reload datapacks. A reload would refresh recipe viewers for free,
     * but it also reloads every other datapack in the pack, and when to do that belongs to whoever runs
     * the server, not to us. Recipe viewers pick the change up on the next {@code /reload}, which is the
     * behaviour the readme has always described.
     */
    private void reapplyAndSync(MinecraftServer server, RecipeManager manager) {
        RecipeState s = state();
        List<Recipe<?>> base = pureBase(manager);
        // Keyed by id rather than a plain list: replaceRecipes throws on a duplicate id and, because it
        // builds the new maps before assigning them, a throw leaves the live recipes completely untouched
        // — the edit would silently do nothing until someone reloaded. Keying makes that impossible.
        Map<ResourceLocation, Recipe<?>> result = new LinkedHashMap<>();
        for (Recipe<?> recipe : base) {
            ResourceLocation id = recipe.getId();
            if (s.isDisabled(id) || s.isGenerated(id)) {
                continue; // disabled, or about to be replaced by our own version of the same id
            }
            result.put(id, recipe);
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
            Recipe<?> parsed = parse(id, original.getAsJsonObject().deepCopy());
            if (parsed != null) {
                result.put(id, parsed);
                restored++;
            }
        }
        for (Map.Entry<ResourceLocation, JsonObject> entry : s.generated().entrySet()) {
            if (s.isGeneratedDisabled(entry.getKey())) {
                continue;
            }
            Recipe<?> parsed = parse(entry.getKey(), entry.getValue());
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
     * line regardless of the debug switch.
     *
     * <p>An edit that is accepted, saved and then does not affect the running game is the one failure that
     * cannot be allowed to pass silently, and it is not something the person making it can see. This states
     * outright whether the change reached the game, which separates our own bug from another mod caching
     * the recipe list behind us: if this reports the recipe as gone and it is still craftable, the recipe
     * manager is no longer what the crafting code reads.
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

    /**
     * Clears session-scoped caches when a server stops (so singleplayer world switches start clean).
     */
    public void onServerStopped() {
        rawJsonCache.clear();
        state = null; // re-read from the global config on next use
    }
}
