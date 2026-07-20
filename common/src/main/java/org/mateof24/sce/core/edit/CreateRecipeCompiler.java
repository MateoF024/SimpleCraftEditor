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
            ingredients.add(value.isFluid() ? fluidJson(value) : value.toIngredientJson());
        }
        json.add("ingredients", ingredients);

        JsonArray results = new JsonArray();
        for (RecipeDraft.ResultEntry entry : draft.results) {
            if (entry.item == null || entry.item.isEmpty()) {
                continue;
            }
            if (entry.item.isFluidTag()) {
                continue; // a result has to name one concrete fluid, so a tag cannot be written here
            }
            if (entry.item.isFluid()) {
                results.add(fluidJson(entry.item)); // a fluid result carries an amount, not a count/chance
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
        String type = json.has("type") ? json.get("type").getAsString() : "";
        if (!RecipeModes.hasCreateType(type)) {
            // Sequenced assembly and the niche machines carry fields this editor does not model. Parsing
            // them here would open them as the wrong type and drop those fields on save, so hand them to
            // the raw JSON editor instead, which round-trips them untouched.
            return null;
        }
        RecipeDraft draft = new RecipeDraft();
        draft.kind = RecipeDraft.Kind.CREATE_PROCESSING;
        draft.id = id;
        draft.createType = type;
        draft.inputs.clear();
        draft.results.clear();

        if (json.has("ingredients") && json.get("ingredients").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("ingredients")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                IngredientValue fluid = readFluid(object);
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
                IngredientValue fluid = readFluid(object);
                if (fluid != null) {
                    draft.results.add(new RecipeDraft.ResultEntry(fluid, 1, 1.0f));
                    continue;
                }
                if (!object.has("item")) {
                    continue; // neither an item nor a fluid we can show
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

    /**
     * Create 1.20.1 writes a fluid inline as {@code {"fluid": id, "amount": n}}, and a whole tag of fluids
     * as {@code {"fluidTag": id, "amount": n}}. The amount is in the platform's own fluid unit, not always
     * millibuckets — see {@link IngredientValue#toPlatformAmount(int)}.
     */
    private static JsonObject fluidJson(IngredientValue value) {
        JsonObject json = new JsonObject();
        json.addProperty(value.isFluidTag() ? "fluidTag" : "fluid", value.id().toString());
        json.addProperty("amount", IngredientValue.toPlatformAmount(value.amount()));
        return json;
    }

    /** Reads an inline fluid entry, single or tagged, or null if this entry is not a fluid. */
    private static IngredientValue readFluid(JsonObject object) {
        boolean tagged = object.has("fluidTag");
        if (!tagged && !object.has("fluid")) {
            return null;
        }
        ResourceLocation fluid = ResourceLocation.tryParse(object.get(tagged ? "fluidTag" : "fluid").getAsString());
        if (fluid == null) {
            return null;
        }
        int amount = object.has("amount")
                ? IngredientValue.fromPlatformAmount(object.get("amount").getAsLong())
                : IngredientValue.BUCKET;
        return tagged ? IngredientValue.fluidTag(fluid, amount) : IngredientValue.fluid(fluid, amount);
    }
}
