package org.mateof24.sce.core.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.mateof24.sce.SimpleCraftEditor;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.mateof24.sce.mixin.RecipeManagerAccessor;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.level.ServerPlayer;
import com.google.gson.JsonParser;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.BufferedReader;
import java.util.Optional;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.ScePerf;
import org.mateof24.sce.core.compat.PolymorphRecipeSelection;
import org.mateof24.sce.core.edit.RecipeDraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    /**
     * Recipe files read from the pack, kept as they are asked for.
     *
     * <p>Until 1.21.11 the load hook was handed the whole map of raw recipe JSON and this held all of it.
     * From 1.21.11 the hook is handed recipes that are already built, so the JSON has to be read from the
     * pack — and reading all of it again, having just watched the game parse it, would be several hundred
     * milliseconds of work for something almost none of which is ever looked at. It is read per recipe
     * instead, the first time the editor asks for one.
     */
    private final Map<Identifier, JsonObject> rawJsonCache = new HashMap<>();
    /** Where those files come from. Held from the last recipe load; null before the first. */
    private ResourceManager resources;
    /** Recipe files live at {@code data/<namespace>/recipe/<path>.json}, which this knows how to spell. */
    private static final FileToIdConverter RECIPE_FILES = FileToIdConverter.json("recipe");
    /**
     * The generated recipe ids added to the last recipe load. Subtracting these from the live set is what
     * recovers the pack's own recipes exactly — see {@link #pureBase}.
     */
    private final Set<Identifier> injectedIds = new HashSet<>();
    /** The pack's recipes without ours, worked out once per load and reused by every edit after it. */
    private List<RecipeHolder<?>> pureBase = List.of();
    private boolean pureBaseKnown;
    /**
     * The registries the recipe codec needs to resolve items and tags. Taken from the server rather than
     * captured during the recipe load: the load hook runs at the head of the load and does no parsing, so
     * it has nothing to capture, and a field filled in only by some other path is a field that is
     * sometimes null. A server's registries do not change while it runs, so reading them per call is free.
     */
    private HolderLookup.Provider registries;
    private Consumer<MinecraftServer> changeListener;
    /**
     * Changes every time the live recipe set is replaced, so a client can tell whether the list of recipe
     * ids it holds is still the one the server has. Started from the clock rather than from zero: two
     * different servers would both begin at 1, and a client that moved from one to the other would think
     * a list it had from the first was current on the second.
     */
    private long recipeEpoch = System.nanoTime();

    private RecipeStateManager() {
    }

    /** Set by the networking layer so client editor state is re-synced after every mutation. */
    public void setChangeListener(Consumer<MinecraftServer> listener) {
        this.changeListener = listener;
    }

    /**
     * Edits the recipe set before anything reads it, from {@link org.mateof24.sce.mixin.RecipeManagerMixin}
     * at the head of {@code RecipeManager.apply}, ahead of KubeJS's own head hook.
     *
     * <p>What arrives here changed at 1.21.11. It used to be the map of raw recipe JSON, which could be
     * edited in place; now it is a {@code RecipeMap} of recipes already built, so the edit is to hand back
     * a different map: the pack's, minus what is disabled, plus what this mod has authored. Built new
     * rather than mutated, for the reason in the class docs of the mixin.
     *
     * <p>Our own recipes are parsed here rather than at the reload that follows, so a recipe with a
     * mistake in it is left out of the load and named in the log instead of taking the load down.
     */
    public RecipeMap beforeRecipeLoad(RecipeMap incoming, ResourceManager resourceManager,
                                      HolderLookup.Provider loadRegistries) {
        ScePerf.Run perf = ScePerf.start("recipe load");
        SceDebug.reportEnvironment();
        this.resources = resourceManager;
        if (this.registries == null) {
            this.registries = loadRegistries;
        }
        RecipeState s = state();
        perf.stage("read our state");

        rawJsonCache.clear();
        injectedIds.clear();
        pureBase = List.of();
        pureBaseKnown = false;
        RecipeOutputIndex.INSTANCE.invalidate();

        Map<Identifier, RecipeHolder<?>> live = new LinkedHashMap<>();
        int removed = 0;
        for (RecipeHolder<?> holder : incoming.values()) {
            Identifier id = holder.id().identifier();
            if (s.isDisabled(id)) {
                removed++;
                continue;
            }
            live.put(id, holder);
        }
        perf.stage("take out the disabled ones");

        int added = 0;
        for (Map.Entry<Identifier, JsonObject> entry : s.generated().entrySet()) {
            if (s.isGeneratedDisabled(entry.getKey())) {
                continue;
            }
            try {
                repairCookingTime(entry.getKey(), entry.getValue());
                live.put(entry.getKey(), deserialize(entry.getKey(), entry.getValue()));
                injectedIds.add(entry.getKey());
                added++;
            } catch (Exception e) {
                SimpleCraftEditor.LOGGER.warn("Could not load authored recipe '{}': {}",
                        entry.getKey(), e.getMessage());
            }
        }
        perf.stage("add our own");

        SceDebug.log(SceDebug.Category.RELOAD,
                "Recipes loading: {} in the pack, {} taken out, {} of ours added, {} live",
                incoming.values().size(), removed, added, live.size());
        perf.finish("{} recipes in the pack, {} removed, {} added",
                incoming.values().size(), removed, added);
        recipeEpoch++;
        return RecipeMap.create(live.values());
    }

    /**
     * Which version of the recipe set is live. Any client holding a list of recipe ids can compare this
     * with the one its list came from and know whether it is still good.
     */
    public long recipeEpoch() {
        return recipeEpoch;
    }

    /** Every recipe id the server has, for the editor's id field to complete against. */
    public List<Identifier> liveRecipeIds(MinecraftServer server) {
        List<Identifier> ids = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            ids.add(holder.id().identifier());
        }
        return ids;
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
        for (Identifier id : s.generated().keySet()) {
            boolean present = manager.byKey(keyOf(id)).isPresent();
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
    public String findRecipe(MinecraftServer server, Identifier id) {
        RecipeManager manager = server.getRecipeManager();
        RecipeState s = state();
        StringBuilder sb = new StringBuilder("Recipe '" + id + "':");

        // The manager keeps two structures that can disagree: byKey reads the byName map, while crafting
        // and getRecipes read the by-type map. See the 1.20.1 mirror.
        boolean inByName = manager.byKey(keyOf(id)).isPresent();
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
        RecipeHolder<?> known = inByType != null ? inByType : manager.byKey(keyOf(id)).orElse(null);
        if (known != null) {
            ItemStack result = resultOf(server, known);
            sb.append("\n  result item: ").append(result);
            sb.append("\n  other live recipes producing the same item:");
            int others = 0;
            for (RecipeHolder<?> holder : manager.getRecipes()) {
                if (holder.id().equals(id)) {
                    continue;
                }
                ItemStack out = resultOf(server, holder);
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
                .append(", ids ").append(manager.getRecipes().size());
        return sb.toString();
    }

    // ------------------------------------------------------------------ runtime mutations (server thread)

    public boolean disable(MinecraftServer server, Identifier id) {
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
                reapplyAndSync(server, server.getRecipeManager(), "turn our own recipe off");
            }
            return changed;
        }
        if (s.isDisabled(id)) {
            return false;
        }
        RecipeManager manager = server.getRecipeManager();
        if (manager.byKey(keyOf(id)).isEmpty()) {
            return false; // no such recipe currently loaded
        }
        JsonObject raw = rawJson(id);
        s.disable(id, raw != null ? raw.deepCopy() : null);
        reapplyAndSync(server, manager, "disable a recipe");
        return true;
    }

    public boolean enable(MinecraftServer server, Identifier id) {
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
            reapplyAndSync(server, server.getRecipeManager(), "restore a recipe");
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

    public boolean saveGenerated(MinecraftServer server, Identifier id, JsonObject json) {
        useRegistries(server);
        SceDebug.dump(SceDebug.Category.EDIT, () -> "saveGenerated '" + id + "': " + json);
        String rejection = rejectionReason(json);
        if (rejection != null) {
            SimpleCraftEditor.LOGGER.warn("Rejected authored recipe '{}': {}", id, rejection);
            return false;
        }
        long validated = ScePerf.now();
        try {
            deserialize(id, json);
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Rejected authored recipe '{}': {}", id, e.getMessage());
            return false;
        } finally {
            ScePerf.since("check a saved recipe is valid", validated);
        }
        state().putGenerated(id, json);
        reapplyAndSync(server, server.getRecipeManager(), "save a recipe");
        SceDebug.log(SceDebug.Category.EDIT, "Saved '{}'; sources={}, generated={}",
                id, rawJsonCache.size(), state().generated().size());
        return true;
    }

    /**
     * The recipe's file as the pack wrote it, or null when it has none — which is what a recipe written
     * by a script looks like, and why it cannot be edited here.
     */
    public JsonObject rawJson(Identifier id) {
        if (rawJsonCache.containsKey(id)) {
            return rawJsonCache.get(id);
        }
        JsonObject read = readRecipeFile(id);
        rawJsonCache.put(id, read);
        return read;
    }

    private JsonObject readRecipeFile(Identifier id) {
        if (resources == null) {
            return null;
        }
        Optional<Resource> resource = resources.getResource(RECIPE_FILES.idToFile(id));
        if (resource.isEmpty()) {
            return null;
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Could not read the recipe file for '{}': {}", id, e.getMessage());
            return null;
        }
    }

    /** How many recipe files have been read so far. Only a debug figure now; nothing depends on it. */
    public int rawJsonCacheSize() {
        return rawJsonCache.size();
    }

    /** How many of the pack's own recipes we rebuild from — zero until the first edit works it out. */
    public int baseSnapshotSize() {
        return pureBase.size();
    }

    /**
     * The JSON the editor should open, or null when the recipe has no source we can edit faithfully.
     *
     * <p>A recipe only has a source we can work from if it was written as a file: the pack's own datapack,
     * or an earlier edit of yours. A recipe a script creates has none — see {@link #isEditable}.
     */
    public JsonObject editorJson(Identifier id) {
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
     * Whether this recipe can be edited at all. See the 1.20.1 mirror: a recipe a script creates is
     * rewritten by that script on every load, so any change of ours is undone and is refused instead.
     */
    public boolean isEditable(Identifier id) {
        return state().isGenerated(id) || rawJson(id) != null;
    }

    /** What can be done with a recipe id, which is not the same question as whether it is spelled right. */
    public enum Editability {
        /** A recipe file backs it, or it is one of ours: it can be opened, changed and turned off. */
        EDITABLE(null),
        /** Something answers to the id, but nothing wrote a file for it — a script built it in code. */
        SCRIPT("sce.msg.not_editable"),
        /** Nothing in the running game answers to this id at all. */
        UNKNOWN("sce.msg.no_such_recipe");

        private final String refusal;

        Editability(String refusal) {
            this.refusal = refusal;
        }

        /** The message that says why this was refused, or null when there is nothing to refuse. */
        public String refusal() {
            return refusal;
        }
    }

    /**
     * Which of the three a recipe id is.
     *
     * <p>Every route that refuses one used to answer "a script wrote it", because the only question
     * being asked was whether a file backed the id — and a name that is merely wrong has no file
     * either. Typing {@code minecraft:} into the load box and pressing the button therefore claimed a
     * script had written {@code minecraft:}, sending whoever typed it hunting through scripts for
     * something that was never there. The two refusals are different and now say so.
     */
    public Editability editability(MinecraftServer server, Identifier id) {
        if (isEditable(id)) {
            return Editability.EDITABLE;
        }
        return known(server, id) ? Editability.SCRIPT : Editability.UNKNOWN;
    }

    /** Whether anything answers to this id in the running game: the pack's, a mod's, a script's or ours. */
    public boolean known(MinecraftServer server, Identifier id) {
        return server.getRecipeManager().byKey(keyOf(id)).isPresent();
    }

    /** Result stack of a currently-loaded recipe, or {@link ItemStack#EMPTY} if absent. */
    public ItemStack resultOf(MinecraftServer server, Identifier id) {
        return server.getRecipeManager().byKey(keyOf(id))
                .map(holder -> resultOf(server, holder))
                .orElse(ItemStack.EMPTY);
    }

    /** Result stack of a generated recipe from its stored JSON (works even while it is toggled off). */
    public ItemStack generatedResultOf(MinecraftServer server, Identifier id) {
        useRegistries(server);
        JsonObject json = state().generated().get(id);
        if (json == null) {
            return ItemStack.EMPTY;
        }
        try {
            return resultOf(server, deserialize(id, json));
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** True if a datapack recipe with this id existed before our edits (a generated recipe is then an edit). */
    public boolean wasBaseRecipe(Identifier id) {
        return rawJsonCache.containsKey(id);
    }

    /**
     * Copies a recipe under a new id. Cloning reads the source's JSON, so a script-written recipe cannot be
     * cloned for the same reason it cannot be edited — there is no JSON to copy.
     */
    public boolean cloneRecipe(MinecraftServer server, Identifier source, Identifier target) {
        JsonElement raw = rawJsonCache.get(source);
        if (raw == null || !raw.isJsonObject()) {
            return false;
        }
        state().putGenerated(target, raw.getAsJsonObject().deepCopy());
        reapplyAndSync(server, server.getRecipeManager(), "clone a recipe");
        return true;
    }

    public boolean deleteGenerated(MinecraftServer server, Identifier id) {
        if (!state().removeGenerated(id)) {
            return false;
        }
        reapplyAndSync(server, server.getRecipeManager(), "delete a recipe");
        return true;
    }

    public void forceReapply(MinecraftServer server) {
        reapplyAndSync(server, server.getRecipeManager(), "/sce reload");
    }

    /**
     * The pack's own recipes, without the ones we added. See the 1.20.1 mirror for why the subtraction is
     * keyed on the ids we injected at load time rather than on what is generated right now.
     */
    private List<RecipeHolder<?>> pureBase(RecipeManager manager) {
        if (pureBaseKnown) {
            return pureBase;
        }
        Map<Identifier, RecipeHolder<?>> base = new LinkedHashMap<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!injectedIds.contains(holder.id().identifier())) {
                base.put(holder.id().identifier(), holder);
            }
        }
        // A recipe that was already disabled when the pack loaded was taken out of the load, so it is not
        // in the live set to be found here. It still belongs in the base — see the 1.20.1 mirror.
        int recovered = 0;
        for (Identifier id : state().disabled().keySet()) {
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
    private void reapplyAndSync(MinecraftServer server, RecipeManager manager, String trigger) {
        // The trigger names the action a player took, so the timing report says which kind of edit was
        // slow rather than only that applying one was.
        ScePerf.Run perf = ScePerf.start("apply: " + trigger);
        useRegistries(server);
        RecipeState s = state();
        List<RecipeHolder<?>> base = pureBase(manager);
        perf.stage("work out the pack's own set");
        // Keyed by id rather than a plain list: replaceRecipes throws on a duplicate id and, because it
        // builds the new maps before assigning them, a throw leaves the live recipes completely untouched
        // — the edit would silently do nothing until someone reloaded. Keying makes that impossible.
        Map<Identifier, RecipeHolder<?>> result = new LinkedHashMap<>();
        for (RecipeHolder<?> holder : base) {
            Identifier id = holder.id().identifier();
            if (s.isDisabled(id) || s.isGenerated(id)) {
                continue; // disabled, or about to be replaced by our own version of the same id
            }
            result.put(id, holder);
        }
        perf.stage("carry the pack's recipes over");
        // An id we injected is missing from the base, so if it was an edit of a pack recipe and that edit is
        // now gone, the pack's own version has to come back — otherwise deleting an edit would delete the
        // recipe it edited.
        int restored = 0;
        for (Identifier id : injectedIds) {
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
        perf.stage("bring back originals we had edited");
        for (Map.Entry<Identifier, JsonObject> entry : s.generated().entrySet()) {
            if (s.isGeneratedDisabled(entry.getKey())) {
                continue;
            }
            RecipeHolder<?> parsed = parse(entry.getKey(), entry.getValue());
            if (parsed != null) {
                result.put(entry.getKey(), parsed);
            }
        }
        perf.stage("parse our own recipes");
        SceDebug.log(SceDebug.Category.EDIT,
                "Applying: {} pack recipes - {} disabled + {} of ours + {} restored originals = {} live",
                base.size(), s.disabled().size(), s.generated().size(), restored, result.size());
        try {
            // There is no replaceRecipes any more. The map itself is the field, and everything worked out
            // from it — the property sets, the stonecutter list, the displays the client is sent — is
            // rebuilt by finalizeRecipeLoading. A new map rather than a mutated one on purpose: FastSuite
            // hangs its cache off the map object, so building a new one is what throws that cache away.
            ((RecipeManagerAccessor) manager).sce$setRecipes(RecipeMap.create(result.values()));
            manager.finalizeRecipeLoading(server.getWorldData().enabledFeatures());
            recipeEpoch++;
        } catch (Exception e) {
            // Never silently: if the recipe set cannot be swapped, the edit did not happen, and whoever
            // made it needs to see why rather than watch it appear to work and then not.
            SimpleCraftEditor.LOGGER.error("Could not apply the recipe edit to the running game", e);
            perf.finish("failed");
            return;
        }
        perf.stage("swap the live recipes");
        RecipeOutputIndex.INSTANCE.invalidate();
        invalidateDerivedCaches(manager);
        perf.stage("clear other mods' lookups");
        PolymorphRecipeSelection.clearRemembered(server);
        perf.stage("clear Polymorph's choice");
        sendChangeToClients(server, s, result);
        perf.stage("tell players what changed");
        RecipeStore.save(s);
        perf.stage("write our file");
        reportApplied(manager, result.size());
        perf.stage("check it landed");
        if (changeListener != null) {
            changeListener.accept(server);
        }
        perf.finish("{} recipes live, {} of them ours, for {} player(s)",
                result.size(), s.generated().size(), server.getPlayerList().getPlayerCount());
    }

    /**
     * Tells every client which recipes this mod has changed, rather than resending the whole recipe set.
     *
     * <p>The vanilla packet is all-or-nothing, and a client receiving it rebuilds everything derived from
     * the recipe list — measured at 1.2 to 1.9 seconds of frozen game in a 620-mod pack, for a refresh
     * that does not even show the change until the next {@code /reload}. Nothing was gained for that
     * second and a half, so it is not asked for any more.
     *
     * <p>Only the ids this mod has touched can differ from what a client was sent when the pack loaded,
     * so those are the only ones worth describing: each one either has a definition now, or is gone.
     * That is enough for a client to reach exactly the set the server has, and it is a few hundred bytes
     * instead of every recipe in the pack.
     */
    /**
     * Brings every player's recipe data back in line after an edit.
     *
     * <p>On 1.20.1 and 1.21.1 this sent the handful of recipes that changed down a channel of this mod's
     * own, because the client kept a full recipe list and the vanilla packet that refreshed it made every
     * recipe viewer rebuild from nothing — 1.2 to 1.9 seconds of frozen game per edit.
     *
     * <p>From 1.21.11 there is no such list. The client keeps display data for its recipe book and
     * nothing else, so there is nothing here to patch and nothing to hand-roll: what the game itself
     * sends after a datapack reload is exactly right, and it is two things — the synchronized item
     * properties and stonecutter list, and each player's recipe book. Neither carries recipes, so the
     * cost that made the old packet worth avoiding is not there either.
     */
    private void sendChangeToClients(MinecraftServer server, RecipeState s,
                                     Map<Identifier, ?> live) {
        RecipeManager manager = server.getRecipeManager();
        ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(
                manager.getSynchronizedItemProperties(), manager.getSynchronizedStonecutterRecipes());
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            player.connection.send(packet);
            player.getRecipeBook().sendInitialRecipeBook(player);
        }
        SceDebug.log(SceDebug.Category.NETWORK,
                "Resynced recipe data to {} player(s); {} recipes live", players.size(), live.size());
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
        for (Identifier id : s.disabled().keySet()) {
            if (manager.byKey(keyOf(id)).isPresent()) {
                wrong.add("still present although disabled: " + id);
            }
        }
        for (Identifier id : s.generated().keySet()) {
            boolean present = manager.byKey(keyOf(id)).isPresent();
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
    private RecipeHolder<?> parse(Identifier id, JsonObject json) {
        // Counted rather than logged line by line: this runs once per stored recipe on every single edit,
        // so the number worth knowing is the total it adds up to, not any one call.
        long started = ScePerf.now();
        try {
            repairCookingTime(id, json);
            return deserialize(id, json);
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Skipping recipe '{}' that could not be parsed: {}", id, e.getMessage());
            state().markBroken(id);
            return null;
        } finally {
            ScePerf.since("parse one stored recipe", started);
        }
    }

    /** Remembers the registries to decode recipes with. Called wherever a server is in hand. */
    private void useRegistries(MinecraftServer server) {
        registries = server.registryAccess();
    }

    /** The key a recipe is filed under, which is what the manager is addressed by from 1.21.11. */
    private static ResourceKey<Recipe<?>> keyOf(Identifier id) {
        return ResourceKey.create(Registries.RECIPE, id);
    }

    /**
     * What a recipe makes, for the places that show it: the first stack of the first way it can be
     * displayed. A recipe with several outputs is asked about properly by {@link RecipeOutputIndex}; here
     * one stack is all that is wanted, to draw beside a name.
     */
    private static ItemStack resultOf(MinecraftServer server, RecipeHolder<?> holder) {
        try {
            ContextMap context = SlotDisplayContext.fromLevel(server.overworld());
            for (RecipeDisplay display : holder.value().display()) {
                ItemStack stack = display.result().resolveForFirstStack(context);
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
        } catch (Throwable ignored) {
            // A recipe that cannot describe itself simply has no icon.
        }
        return ItemStack.EMPTY;
    }

    /** Decodes recipe JSON into a holder with the registry-aware codec; throws if the JSON is invalid. */
    private RecipeHolder<?> deserialize(Identifier id, JsonObject json) {
        if (registries == null) {
            throw new IllegalStateException("the server's registries are not available yet");
        }
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        Recipe<?> recipe = Recipe.CODEC.parse(ops, json).getOrThrow(JsonParseException::new);
        return new RecipeHolder<>(keyOf(id), recipe);
    }

    /**
     * Gives a stored cooking recipe a usable cooking time. The editor used to save these with a time of
     * zero, which crafts but makes a recipe viewer divide by it to animate its progress arrow — EMI throws
     * and draws "Error Rendering" instead of the recipe. Recipes written back then are still on disk, so
     * they are repaired here rather than only on the next save.
     */
    private void repairCookingTime(Identifier id, JsonObject json) {
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
