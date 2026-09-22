package org.mateof24.sce.core.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.ScePerf;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers "which recipes produce this item", on the server, looking at <em>every</em> output a recipe
 * declares rather than only its main one.
 *
 * <p>This exists because of a bug that looked like recipes failing to load and was not. The editor key
 * used to compare against {@code Recipe.getResultItem} alone, and for a machine recipe that is only the
 * first entry of its result list. Create's crushing recipes routinely put the interesting item second or
 * third — netherite scrap out of nether bricks is exactly that shape — so those recipes were present,
 * loaded and craftable, and the key simply never found them. Nothing was broken except the search.
 *
 * <p>Three sources are combined, in order of how much they can be trusted:
 *
 * <ol>
 *   <li>The recipe's own main result.</li>
 *   <li>Any accessor on the recipe that hands back a collection of item stacks, found by name and cached
 *       per recipe class. Create calls its own {@code getRollableResultsAsItemStacks}; other mods use
 *       other names, so several are tried. No mod is named and none is depended on: a recipe that has no
 *       such method simply contributes nothing here.</li>
 *   <li>The recipe's JSON, which the server already keeps for every datapack recipe. A mod whose recipe
 *       object hides its outputs entirely still has to write them in a file, and {@code results} is the
 *       spelling every processing-style recipe uses.</li>
 * </ol>
 *
 * <p><b>Built as one table, not searched per item.</b> The first version walked the whole recipe list
 * once per item asked about, which measured 81 ms per item in a 25,000-recipe pack — fine for one
 * lookup and wasteful for the fifth. Walking it once and inverting it costs about the same as a single
 * search and makes every lookup after that free, which is the shape the job actually has: a pack author
 * browses many items between edits, and the table only has to be thrown away when the recipes change.
 */
public final class RecipeOutputIndex {
    public static final RecipeOutputIndex INSTANCE = new RecipeOutputIndex();

    /** Accessor names that hand back a collection of stacks, ordered by how common they are. */
    private static final String[] EXTRA_OUTPUT_ACCESSORS = {
            "getRollableResultsAsItemStacks", "getResultItems", "getResults", "getOutputs"};

    /** Worked out once per recipe class; null means "this kind of recipe has no such accessor". */
    private final Map<Class<?>, Method> accessorByClass = new HashMap<>();
    /** What produces what. Null until built, and set back to null whenever the recipes change. */
    private Map<Item, List<ResourceLocation>> byItem;

    private RecipeOutputIndex() {
    }

    /** Forgets the table. Called wherever the live recipe set is replaced or reloaded. */
    public void invalidate() {
        byItem = null;
    }

    /**
     * Every loaded recipe that produces {@code item}, sorted by id so that stepping through them with the
     * key walks a stable order.
     */
    public List<ResourceLocation> recipesProducing(MinecraftServer server, Item item) {
        return build(server).getOrDefault(item, List.of());
    }

    /** The table, building it first if the recipes have changed since it was last needed. */
    private Map<Item, List<ResourceLocation>> build(MinecraftServer server) {
        if (byItem != null) {
            return byItem;
        }
        ScePerf.Run perf = ScePerf.start("index what every recipe makes");
        Map<Item, List<ResourceLocation>> building = new HashMap<>();
        int scanned = 0;
        int multiOutput = 0;
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            scanned++;
            Set<Item> outputs = outputsOf(server, holder);
            if (outputs.size() > 1) {
                multiOutput++;
            }
            for (Item output : outputs) {
                building.computeIfAbsent(output, key -> new ArrayList<>()).add(holder.id());
            }
        }
        Map<Item, List<ResourceLocation>> finished = new HashMap<>(building.size());
        for (Map.Entry<Item, List<ResourceLocation>> entry : building.entrySet()) {
            List<ResourceLocation> ids = entry.getValue();
            ids.sort(Comparator.comparing(ResourceLocation::toString));
            finished.put(entry.getKey(), List.copyOf(ids));
        }
        byItem = finished;
        perf.finish("{} recipes scanned, {} of them make more than one item, {} distinct items produced",
                scanned, multiOutput, finished.size());
        SceDebug.log(SceDebug.Category.CLIENT,
                "Indexed what makes what: {} recipes, {} of them multi-output, {} items produced",
                scanned, multiOutput, finished.size());
        return byItem;
    }

    /** Every item one recipe can produce, from all three sources at once. */
    private Set<Item> outputsOf(MinecraftServer server, RecipeHolder<?> holder) {
        Set<Item> outputs = new LinkedHashSet<>(2);
        try {
            ItemStack primary = holder.value().getResultItem(server.registryAccess());
            if (!primary.isEmpty()) {
                outputs.add(primary.getItem());
            }
        } catch (Throwable ignored) {
            // A recipe that cannot say what it makes must not break the table for every other one.
        }
        for (ItemStack extra : extraOutputs(holder.value())) {
            if (!extra.isEmpty()) {
                outputs.add(extra.getItem());
            }
        }
        outputs.addAll(outputsFromJson(holder.id()));
        return outputs;
    }

    // ------------------------------------------------------------------ the recipe's own extra outputs

    /** Every stack a recipe's own accessor offers beyond the main result; empty when it has none. */
    private List<ItemStack> extraOutputs(Object recipe) {
        Method accessor = accessorFor(recipe.getClass());
        if (accessor == null) {
            return List.of();
        }
        try {
            Object value = accessor.invoke(recipe);
            if (!(value instanceof Iterable<?> items)) {
                return List.of();
            }
            List<ItemStack> stacks = new ArrayList<>();
            for (Object element : items) {
                if (element instanceof ItemStack stack) {
                    stacks.add(stack);
                }
            }
            return stacks;
        } catch (Throwable e) {
            return List.of();
        }
    }

    /**
     * Looks the accessor up once per recipe class. Building the table reaches this for every loaded
     * recipe, so the reflective search itself must not be repeated; the class is fixed for a recipe type.
     */
    private Method accessorFor(Class<?> type) {
        if (accessorByClass.containsKey(type)) {
            return accessorByClass.get(type);
        }
        Method found = null;
        for (String name : EXTRA_OUTPUT_ACCESSORS) {
            try {
                Method method = type.getMethod(name);
                if (method.getParameterCount() == 0 && Iterable.class.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    found = method;
                    break;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Not this name; try the next.
            }
        }
        accessorByClass.put(type, found);
        return found;
    }

    // ------------------------------------------------------------------ the recipe's file

    /**
     * The items a recipe's own JSON names as results. The last resort, for a recipe whose object gives
     * nothing away: whatever it does in code, a datapack recipe still has to write its outputs down.
     */
    private Set<Item> outputsFromJson(ResourceLocation id) {
        // What the recipe says now, not what the pack shipped: a recipe written in this editor has no
        // datapack file at all, and asking for one is why a freshly saved uncrafting recipe could not
        // be found by the key that had just been used to open the item it takes apart.
        JsonObject json = RecipeStateManager.INSTANCE.currentJson(id);
        if (json == null) {
            return Set.of();
        }
        Set<Item> items = new LinkedHashSet<>(2);
        if (json.has("results") && json.get("results").isJsonArray()) {
            JsonArray results = json.getAsJsonArray("results");
            for (JsonElement element : results) {
                addItem(items, element);
            }
        }
        if (json.has("result")) {
            addItem(items, json.get("result"));
        }
        // And the one item a recipe is about, for the types that name no result at all and would
        // otherwise be found by nothing: an uncrafting recipe has a pattern and a key where every
        // other recipe has a result, and a scepter repair hands back the very item it was given.
        // Which field that is belongs to whoever wrote the compiler for the type, so it is asked
        // rather than guessed - one item, the way a crafting recipe is offered under one item.
        if (!json.has("result") && !json.has("results")) {
            addSubjectItems(items, org.mateof24.sce.core.edit.ModRecipeCompiler.subjectOf(json));
        }
        return items;
    }

    /**
     * The concrete items an ingredient names, for the recipes indexed by the item they are about.
     *
     * <p>Only concrete ones: a tag is not one item, and putting all of its members in the table would
     * offer the recipe under a hundred items that have nothing to do with it. A recipe whose subject is
     * a tag is still reachable by typing its id into the editor and pressing Load.
     */
    private void addSubjectItems(Set<Item> into, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement option : element.getAsJsonArray()) {
                addSubjectItems(into, option);
            }
            return;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                addItem(into, element);
            }
            return;
        }
        if (element.isJsonObject() && element.getAsJsonObject().has("item")) {
            addItem(into, element);
        }
    }

    /** Reads one result entry, which may be a bare id, or an object keyed by {@code item} or {@code id}. */
    private void addItem(Set<Item> into, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        String raw = null;
        if (element.isJsonPrimitive()) {
            raw = element.getAsString();
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String key = object.has("item") ? "item" : (object.has("id") ? "id" : null);
            if (key != null && object.get(key).isJsonPrimitive()) {
                raw = object.get(key).getAsString();
            }
        }
        ResourceLocation itemId = raw == null ? null : ResourceLocation.tryParse(raw);
        if (itemId != null && BuiltInRegistries.ITEM.containsKey(itemId)) {
            into.add(BuiltInRegistries.ITEM.get(itemId));
        }
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * Spells out, for one item, every recipe that makes it and how each one was recognised — the direct
     * answer to "the key does not find the recipe I know exists". Driven by {@code /sce debug produces}.
     *
     * <p>Walks the recipes itself rather than reading the table, because the table records only that a
     * recipe produces the item, and the useful part of the answer is <em>which of the three sources</em>
     * said so: a recipe found only by its extra outputs is exactly the case the old search missed.
     */
    public String describe(MinecraftServer server, Item item) {
        StringBuilder sb = new StringBuilder("Recipes producing " + id(item) + ':');
        int shown = 0;
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            ResourceLocation recipeId = holder.id();
            String how = null;
            try {
                ItemStack primary = holder.value().getResultItem(server.registryAccess());
                if (!primary.isEmpty() && primary.getItem() == item) {
                    how = "its main result";
                }
            } catch (Throwable ignored) {
                // Same reason as when building the table: one bad recipe must not stop the report.
            }
            if (how == null) {
                for (ItemStack extra : extraOutputs(holder.value())) {
                    if (!extra.isEmpty() && extra.getItem() == item) {
                        how = "one of its other outputs, NOT the main result";
                        break;
                    }
                }
            }
            if (how == null && outputsFromJson(recipeId).contains(item)) {
                how = "its recipe file, which lists it as a result";
            }
            if (how == null) {
                continue;
            }
            sb.append("\n  ").append(recipeId).append(" (").append(holder.value().getType()).append(") - found by ")
                    .append(how)
                    .append(RecipeStateManager.INSTANCE.isEditable(recipeId)
                            ? ", editable" : ", NOT editable (no file: a script wrote it)");
            shown++;
        }
        if (shown == 0) {
            sb.append("\n  none. Nothing loaded produces that item.");
        }
        return sb.toString();
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
