package org.mateof24.sce.core.state;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.ScePerf;

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
 * used to compare against the recipe's main result alone, and for a machine recipe that is only the
 * first entry of its result list. Create's crushing recipes routinely put the interesting item second or
 * third — netherite scrap out of nether bricks is exactly that shape — so those recipes were present,
 * loaded and craftable, and the key simply never found them.
 *
 * <p><b>Here the game answers it directly.</b> From 1.21.11 a recipe carries {@code display()}: the list
 * of ways it can be shown, each with the result it produces. That is the same question this class had to
 * reverse-engineer on the older versions from three sources at once — the main result, a reflective
 * search for an accessor returning a list of stacks, and the recipe's own JSON. None of that is needed
 * now, and none of it is here: a recipe that makes four things says so, in the API, because that is what
 * the recipe book and the viewers read too.
 *
 * <p><b>Built as one table, not searched per item.</b> Walking the whole recipe list once and inverting
 * it costs about the same as a single search and makes every lookup after that free, which is the shape
 * the job actually has: a pack author browses many items between edits, and the table only has to be
 * thrown away when the recipes change.
 */
public final class RecipeOutputIndex {
    public static final RecipeOutputIndex INSTANCE = new RecipeOutputIndex();

    /** What produces what. Null until built, and set back to null whenever the recipes change. */
    private Map<Item, List<Identifier>> byItem;

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
    public List<Identifier> recipesProducing(MinecraftServer server, Item item) {
        return build(server).getOrDefault(item, List.of());
    }

    /** The table, building it first if the recipes have changed since it was last needed. */
    private Map<Item, List<Identifier>> build(MinecraftServer server) {
        if (byItem != null) {
            return byItem;
        }
        ScePerf.Run perf = ScePerf.start("index what every recipe makes");
        ContextMap context = displayContext(server);
        Map<Item, List<Identifier>> building = new HashMap<>();
        int scanned = 0;
        int multiOutput = 0;
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            scanned++;
            Set<Item> outputs = outputsOf(holder, context);
            if (outputs.size() > 1) {
                multiOutput++;
            }
            for (Item output : outputs) {
                building.computeIfAbsent(output, key -> new ArrayList<>()).add(holder.id().identifier());
            }
        }
        Map<Item, List<Identifier>> finished = new HashMap<>(building.size());
        for (Map.Entry<Item, List<Identifier>> entry : building.entrySet()) {
            List<Identifier> ids = entry.getValue();
            ids.sort(Comparator.comparing(Identifier::toString));
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

    /**
     * Every item one recipe can produce, from the displays the recipe itself declares.
     *
     * <p>A recipe that cannot say what it makes contributes nothing rather than stopping the table for
     * every other one: one broken recipe in a pack of thousands is not worth losing the rest over.
     */
    private Set<Item> outputsOf(RecipeHolder<?> holder, ContextMap context) {
        Set<Item> outputs = new LinkedHashSet<>(2);
        try {
            for (RecipeDisplay display : holder.value().display()) {
                for (ItemStack stack : display.result().resolveForStacks(context)) {
                    if (!stack.isEmpty()) {
                        outputs.add(stack.getItem());
                    }
                }
            }
        } catch (Throwable ignored) {
            // See above: a recipe that throws while describing itself simply does not appear.
        }
        return outputs;
    }

    /** What a slot display needs to turn itself into stacks: the registries, and the fuel values. */
    private static ContextMap displayContext(MinecraftServer server) {
        Level level = server.overworld();
        return SlotDisplayContext.fromLevel(level);
    }

    // ------------------------------------------------------------------ the debug command

    /**
     * A line per recipe that makes the item, for {@code /sce debug produces}. Says whether each one can
     * be edited, because "the editor will not open it" and "nothing makes it" look the same from outside.
     */
    public String describe(MinecraftServer server, Item item) {
        StringBuilder sb = new StringBuilder("Recipes producing " + id(item) + ':');
        ContextMap context = displayContext(server);
        int shown = 0;
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Set<Item> outputs = outputsOf(holder, context);
            if (!outputs.contains(item)) {
                continue;
            }
            Identifier recipeId = holder.id().identifier();
            sb.append("\n  ").append(recipeId).append(" (").append(holder.value().getType()).append(')')
                    .append(outputs.size() > 1 ? " - one of " + outputs.size() + " outputs" : "")
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
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? String.valueOf(item) : key.toString();
    }
}
