package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/**
 * Round-trips Create processing recipe JSON (mixing, crushing, pressing, …) to and from a {@link RecipeDraft}.
 * Item ingredients and item results (with count and drop chance) are supported; fluid ingredients/results are
 * skipped for now (they are neither shown nor written back), so this handles item-based recipes.
 */
public final class CreateRecipeCompiler {
    private CreateRecipeCompiler() {
    }

    public static JsonObject toJson(RecipeDraft draft) {
        JsonObject json = new JsonObject();
        json.addProperty("type", draft.createType);

        JsonArray ingredients = new JsonArray();
        for (IngredientValue value : draft.inputs) {
            if (!value.isEmpty()) {
                ingredients.add(value.toIngredientJson());
            }
        }
        json.add("ingredients", ingredients);

        JsonArray results = new JsonArray();
        for (RecipeDraft.ResultEntry entry : draft.results) {
            if (entry.item == null || entry.item.isEmpty()) {
                continue;
            }
            JsonObject result = new JsonObject();
            result.addProperty("item", entry.item.id().toString());
            if (entry.count > 1) {
                result.addProperty("count", entry.count);
            }
            if (entry.chance < 1.0f) {
                result.addProperty("chance", entry.chance);
            }
            results.add(result);
        }
        json.add("results", results);

        if (draft.processingTime > 0) {
            json.addProperty("processingTime", draft.processingTime);
        }
        if (draft.heat != null && !draft.heat.isBlank() && !draft.heat.equals("none")) {
            json.addProperty("heatRequirement", draft.heat);
        }
        return json;
    }

    public static RecipeDraft fromJson(ResourceLocation id, JsonObject json) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = RecipeDraft.Kind.CREATE_PROCESSING;
        draft.id = id;
        draft.createType = json.has("type") ? json.get("type").getAsString() : "";
        draft.inputs.clear();
        draft.results.clear();

        if (json.has("ingredients") && json.get("ingredients").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("ingredients")) {
                if (element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    if (object.has("fluid") || object.has("fluidTag")) {
                        continue; // fluid ingredient, not editable yet
                    }
                    draft.inputs.add(IngredientValue.fromIngredientJson(element));
                }
            }
        }
        if (json.has("results") && json.get("results").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("results")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                if (!object.has("item")) {
                    continue; // fluid result, not editable yet
                }
                IngredientValue item = IngredientValue.item(ResourceLocation.tryParse(object.get("item").getAsString()));
                int count = object.has("count") ? object.get("count").getAsInt() : 1;
                float chance = object.has("chance") ? object.get("chance").getAsFloat() : 1.0f;
                draft.results.add(new RecipeDraft.ResultEntry(item, count, chance));
            }
        }
        draft.processingTime = json.has("processingTime") ? json.get("processingTime").getAsInt() : 0;
        draft.heat = json.has("heatRequirement") ? json.get("heatRequirement").getAsString() : "none";
        return draft;
    }
}
