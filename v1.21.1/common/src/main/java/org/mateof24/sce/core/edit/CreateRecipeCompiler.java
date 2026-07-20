package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
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
            if (entry.item.isFluidTag()) {
                continue; // a result has to name one concrete fluid, so a tag cannot be written here
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
     * On 1.21.1 Create dropped its own fluid ingredient for the platform's. NeoForge's is written flat,
     * with the kind named by {@code type} — exactly as Create's own recipes ship it:
     * {@code {"type": "neoforge:single", "amount": n, "fluid": id}}, or {@code neoforge:tag} with a
     * {@code tag} for a whole tag of fluids. The nested {@code ingredient} wrapper this used to write is
     * not a shape any of those codecs accept.
     *
     * <p>Fabric has no such registry, so Create Fabric keeps the flat 1.20.1 spelling with no {@code type}.
     * That branch is inferred, not verified: there is no Create Fabric 1.21.1 build to check against.
     */
    private static JsonObject fluidIngredientJson(IngredientValue value) {
        JsonObject json = new JsonObject();
        boolean tagged = value.isFluidTag();
        if (Platform.isFabric()) {
            json.addProperty(tagged ? "fluidTag" : "fluid", value.id().toString());
            json.addProperty("amount", IngredientValue.toPlatformAmount(value.amount()));
            return json;
        }
        json.addProperty("type", tagged ? "neoforge:tag" : "neoforge:single");
        json.addProperty("amount", IngredientValue.toPlatformAmount(value.amount()));
        json.addProperty(tagged ? "tag" : "fluid", value.id().toString());
        return json;
    }

    /** A fluid result is a fluid stack: {@code {"id": id, "amount": mB}}. */
    private static JsonObject fluidResultJson(IngredientValue value) {
        JsonObject json = new JsonObject();
        json.addProperty("id", value.id().toString());
        json.addProperty("amount", IngredientValue.toPlatformAmount(value.amount()));
        return json;
    }

    /**
     * Reads a fluid ingredient, or null when the entry is not a fluid.
     *
     * <p>Telling a fluid tag from an item tag is the delicate part: both are written as {@code tag}, and an
     * item tag ingredient may even carry a {@code type} of its own ({@code neoforge:block_tag}). What
     * separates them is {@code amount} — only a fluid is measured. Reading is kept looser than writing so a
     * recipe authored on either loader still loads.
     */
    private static IngredientValue readFluidIngredient(JsonObject object) {
        String key = object.has("fluid") ? "fluid"
                : object.has("fluidTag") ? "fluidTag"
                : (object.has("tag") && object.has("amount")) ? "tag" : null;
        if (key == null) {
            return null;
        }
        ResourceLocation fluid = ResourceLocation.tryParse(object.get(key).getAsString());
        if (fluid == null) {
            return null;
        }
        int amount = object.has("amount")
                ? IngredientValue.fromPlatformAmount(object.get("amount").getAsLong())
                : IngredientValue.BUCKET;
        return key.equals("fluid")
                ? IngredientValue.fluid(fluid, amount)
                : IngredientValue.fluidTag(fluid, amount);
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
        return fluid == null ? null
                : IngredientValue.fluid(fluid, IngredientValue.fromPlatformAmount(object.get("amount").getAsLong()));
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
