package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/**
 * Round-trips Create's sequenced assembly recipes. Unlike every other type this editor handles, one of
 * these is not a single recipe: a base ingredient is carried through an ordered list of processing steps
 * — each step being a whole recipe of its own — repeated a number of loops before yielding the results.
 * Steps are therefore delegated to {@link CreateRecipeCompiler}, which already speaks that format.
 *
 * <p>The transitional item's field is named {@code transitionalItem} on 1.20.1; both spellings are read so
 * a recipe authored on either version still loads.
 */
public final class SequencedAssemblyCompiler {
    public static final String TYPE = "create:sequenced_assembly";

    private SequencedAssemblyCompiler() {
    }

    public static JsonObject toJson(RecipeDraft draft) {
        JsonObject json = new JsonObject();
        json.addProperty("type", TYPE);
        json.add("ingredient", draft.input(0).toIngredientJson());
        json.add("transitionalItem", itemStackJson(draft.transitionalItem, 1));

        JsonArray sequence = new JsonArray();
        for (RecipeDraft step : draft.sequence) {
            sequence.add(stepJson(step, draft.transitionalItem));
        }
        json.add("sequence", sequence);

        JsonArray results = new JsonArray();
        for (RecipeDraft.ResultEntry entry : draft.results) {
            if (entry.item == null || entry.item.isEmpty()) {
                continue;
            }
            JsonObject result = itemStackJson(entry.item, entry.count);
            if (entry.chance < 1.0f) {
                result.addProperty("chance", entry.chance);
            }
            results.add(result);
        }
        json.add("results", results);
        json.addProperty("loops", Math.max(1, draft.loops));
        return json;
    }

    public static RecipeDraft fromJson(ResourceLocation id, JsonObject json) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = RecipeDraft.Kind.SEQUENCED_ASSEMBLY;
        draft.id = id;
        draft.inputs.clear();
        draft.results.clear();
        draft.sequence.clear();

        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("ingredient")));
        draft.transitionalItem = readItemStack(json.get("transitionalItem") != null
                ? json.get("transitionalItem") : json.get("transitional_item"));

        if (json.has("sequence") && json.get("sequence").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("sequence")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                RecipeDraft step = CreateRecipeCompiler.fromJson(id, element.getAsJsonObject());
                if (step == null) {
                    continue;
                }
                // Create replaces a step's first ingredient with the transitional item when the recipe
                // loads, so the ingredient actually worth editing is the one after it.
                IngredientValue applied = step.inputs.size() > 1 ? step.input(1) : step.input(0);
                step.inputs.clear();
                step.inputs.add(applied);
                draft.sequence.add(step);
            }
        }
        if (json.has("results") && json.get("results").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("results")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                IngredientValue item = readItemStack(object);
                if (item.isEmpty()) {
                    continue;
                }
                int count = object.has("count") ? object.get("count").getAsInt() : 1;
                float chance = object.has("chance") ? object.get("chance").getAsFloat() : 1.0f;
                draft.results.add(new RecipeDraft.ResultEntry(item, count, chance));
            }
        }
        draft.loops = json.has("loops") ? Math.max(1, json.get("loops").getAsInt()) : 1;
        return draft;
    }

    /**
     * A step as Create writes it: the transitional item stands in as the first ingredient (Create swaps it
     * for the real one at load time) followed by the item this step applies, and the step yields the
     * transitional item again so the next step has something to work on.
     */
    private static JsonObject stepJson(RecipeDraft step, IngredientValue transitional) {
        RecipeDraft copy = RecipeDraft.blank(RecipeDraft.Kind.CREATE_PROCESSING);
        copy.createType = step.createType;
        copy.processingTime = step.processingTime;
        copy.heat = step.heat;
        copy.inputs.clear();
        copy.inputs.add(transitional);
        if (!step.input(0).isEmpty()) {
            copy.inputs.add(step.input(0));
        }
        copy.results.clear();
        copy.results.add(new RecipeDraft.ResultEntry(transitional, 1, 1.0f));
        return CreateRecipeCompiler.toJson(copy);
    }

    /** An item stack as 1.20.1 Create writes it: {@code {"item": id, "count": n}}. */
    private static JsonObject itemStackJson(IngredientValue value, int count) {
        JsonObject json = new JsonObject();
        json.addProperty("item", value.isEmpty() ? "minecraft:air" : value.id().toString());
        if (count > 1) {
            json.addProperty("count", count);
        }
        return json;
    }

    /** Reads an item stack, tolerating either the {@code item} or the newer {@code id} spelling. */
    private static IngredientValue readItemStack(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return IngredientValue.empty();
        }
        JsonObject object = element.getAsJsonObject();
        String key = object.has("item") ? "item" : (object.has("id") ? "id" : null);
        if (key == null) {
            return IngredientValue.empty();
        }
        ResourceLocation parsed = ResourceLocation.tryParse(object.get(key).getAsString());
        return parsed == null ? IngredientValue.empty() : IngredientValue.item(parsed);
    }
}
