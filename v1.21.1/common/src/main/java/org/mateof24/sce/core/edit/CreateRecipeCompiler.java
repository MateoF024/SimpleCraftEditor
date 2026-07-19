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
            if (value.isEmpty()) {
                continue;
            }
            ingredients.add(value.isFluid() ? fluidIngredientJson(value) : value.toIngredientJson());
        }
        json.add("ingredients", ingredients);

        JsonArray results = new JsonArray();
        for (RecipeDraft.ResultEntry entry : draft.results) {
            if (entry.item == null || entry.item.isEmpty()) {
                continue;
            }
            if (entry.item.isFluid()) {
                results.add(fluidResultJson(entry.item)); // a fluid result carries an amount, not a count/chance
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
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                IngredientValue fluid = readFluidIngredient(object);
                if (fluid != null) {
                    draft.inputs.add(fluid);
                    continue;
                }
                draft.inputs.add(IngredientValue.fromIngredientJson(element));
            }
        }
        if (json.has("results") && json.get("results").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("results")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                IngredientValue fluid = readFluidResult(object);
                if (fluid != null) {
                    draft.results.add(new RecipeDraft.ResultEntry(fluid, 1, 1.0f));
                    continue;
                }
                String idKey = object.has("id") ? "id" : (object.has("item") ? "item" : null);
                if (idKey == null) {
                    continue; // neither an item nor a fluid we can show
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

    /**
     * Create 6.x takes a fluid ingredient as NeoForge's sized fluid ingredient:
     * {@code {"amount": mB, "ingredient": {"fluid": id}}}.
     */
    private static JsonObject fluidIngredientJson(IngredientValue value) {
        JsonObject fluid = new JsonObject();
        fluid.addProperty("fluid", value.id().toString());
        JsonObject json = new JsonObject();
        json.addProperty("amount", value.amount());
        json.add("ingredient", fluid);
        return json;
    }

    /** A fluid result is a fluid stack: {@code {"id": id, "amount": mB}}. */
    private static JsonObject fluidResultJson(IngredientValue value) {
        JsonObject json = new JsonObject();
        json.addProperty("id", value.id().toString());
        json.addProperty("amount", value.amount());
        return json;
    }

    /** Reads a sized fluid ingredient, or null when the entry is not a fluid. */
    private static IngredientValue readFluidIngredient(JsonObject object) {
        if (!object.has("ingredient") || !object.get("ingredient").isJsonObject()) {
            return null;
        }
        JsonObject inner = object.getAsJsonObject("ingredient");
        if (!inner.has("fluid")) {
            return null; // a fluid tag has no single fluid to show; not editable yet
        }
        ResourceLocation fluid = ResourceLocation.tryParse(inner.get("fluid").getAsString());
        if (fluid == null) {
            return null;
        }
        int amount = object.has("amount") ? object.get("amount").getAsInt() : IngredientValue.BUCKET;
        return IngredientValue.fluid(fluid, amount);
    }

    /**
     * Reads a fluid stack result, or null when the entry is not a fluid. Item and fluid results both carry
     * an {@code id}, so the amount field is what tells them apart — an item result counts with {@code count}.
     */
    private static IngredientValue readFluidResult(JsonObject object) {
        if (!object.has("id") || !object.has("amount")) {
            return null;
        }
        ResourceLocation fluid = ResourceLocation.tryParse(object.get("id").getAsString());
        return fluid == null ? null : IngredientValue.fluid(fluid, object.get("amount").getAsInt());
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
