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
            result.addProperty("id", entry.item.id().toString()); // Create 6.x results are vanilla item stacks
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
            json.addProperty("processing_time", draft.processingTime);
        }
        if (draft.heat != null && !draft.heat.isBlank() && !draft.heat.equals("none")) {
            json.addProperty("heat_requirement", draft.heat);
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
                String idKey = object.has("id") ? "id" : (object.has("item") ? "item" : null);
                if (idKey == null) {
                    continue; // fluid result (no item id), not editable yet
                }
                IngredientValue item = IngredientValue.item(ResourceLocation.tryParse(object.get(idKey).getAsString()));
                int count = object.has("count") ? object.get("count").getAsInt() : 1;
                float chance = object.has("chance") ? object.get("chance").getAsFloat() : 1.0f;
                draft.results.add(new RecipeDraft.ResultEntry(item, count, chance));
            }
        }
        draft.processingTime = readInt(json, "processing_time", "processingTime");
        draft.heat = readString(json, "heat_requirement", "heatRequirement", "none");
        return draft;
    }

    private static int readInt(JsonObject json, String key, String legacyKey) {
        if (json.has(key)) {
            return json.get(key).getAsInt();
        }
        return json.has(legacyKey) ? json.get(legacyKey).getAsInt() : 0;
    }

    private static String readString(JsonObject json, String key, String legacyKey, String fallback) {
        if (json.has(key)) {
            return json.get(key).getAsString();
        }
        return json.has(legacyKey) ? json.get(legacyKey).getAsString() : fallback;
    }
}
