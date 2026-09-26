package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;

/**
 * Round-trips Create processing recipe JSON (mixing, crushing, pressing, …) to and from a
 * {@link RecipeDraft}. Items, item tags, fluids and fluid tags are modelled on both sides, with a count
 * and a drop chance on each result.
 *
 * <p>Anything else is carried through rather than dropped. An ingredient whose shape this editor has no
 * model for keeps the JSON it came from ({@link IngredientValue}); a result's own extra keys —
 * {@code components}, which Create allows so a recipe can hand back a named or enchanted item — are kept
 * beside it. Between them they are the difference between opening one of Create's compatibility recipes
 * and saving it unchanged, and opening it and quietly emptying it.
 *
 * <p>The Create limits that decide how many of each a type may have live in {@link RecipeModes}: they
 * belong to Create's own recipe classes, and going over one of them does not misbehave, it stops the
 * recipe loading at all.
 */
public final class CreateRecipeCompiler {
    /** Result keys this editor models; everything else beside them is the author's and is kept. */
    private static final Set<String> MODELLED_RESULT_KEYS =
            Set.of("item", "id", "count", "chance", "fluid", "fluidTag", "amount");

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
            ingredients.add(ingredientJson(value));
        }
        json.add("ingredients", ingredients);

        JsonArray results = new JsonArray();
        for (RecipeDraft.ResultEntry entry : draft.results) {
            JsonObject result = resultJson(entry);
            if (result != null) {
                results.add(result);
            }
        }
        json.add("results", results);

        if (draft.processingTime > 0) {
            json.addProperty("processingTime", draft.processingTime);
        }
        if (draft.heat != null && !draft.heat.isBlank() && !draft.heat.equals("none")) {
            json.addProperty("heatRequirement", draft.heat);
        }
        if (draft.keepHeldItem) {
            json.addProperty("keepHeldItem", true);
        }
        return json;
    }

    /**
     * One ingredient. A value the author left alone writes back the JSON it was read from, which is what
     * keeps Create's compound wrappers, its lists of alternatives and its block tags intact; one they
     * replaced is written in whichever of the four modelled shapes they chose.
     */
    static JsonElement ingredientJson(IngredientValue value) {
        if (value.isVerbatim()) {
            return value.toIngredientJson();
        }
        return value.isFluid() ? fluidJson(value) : value.toIngredientJson();
    }

    /** One result, or null when the slot is empty. */
    private static JsonObject resultJson(RecipeDraft.ResultEntry entry) {
        if (entry.item == null || entry.item.isEmpty()) {
            return null;
        }
        JsonObject result = new JsonObject();
        if (entry.item.isFluidTag()) {
            return null; // a result has to name one concrete fluid, so a tag cannot be written here
        }
        if (entry.item.isFluid()) {
            // A fluid result carries an amount rather than a count and a chance.
            result.addProperty("fluid", entry.item.id().toString());
            result.addProperty("amount", IngredientValue.toPlatformAmount(entry.item.amount()));
        } else {
            result.addProperty("item", entry.item.id().toString());
            if (entry.count > 1) {
                result.addProperty("count", entry.count);
            }
            // Written whenever it is not the default, not only when it is below it: in a recipe sequence
            // the same field is a weight, and those run well above 1.
            if (entry.chance != 1.0f) {
                result.addProperty("chance", entry.chance);
            }
        }
        for (Map.Entry<String, JsonElement> extra : entry.extra.entrySet()) {
            if (!result.has(extra.getKey())) {
                result.add(extra.getKey(), extra.getValue().deepCopy());
            }
        }
        return result;
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
                draft.inputs.add(readIngredient(element));
            }
        }
        if (json.has("results") && json.get("results").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("results")) {
                RecipeDraft.ResultEntry entry = readResult(element);
                if (entry != null) {
                    draft.results.add(entry);
                }
            }
        }
        draft.processingTime = json.has("processingTime") ? json.get("processingTime").getAsInt() : 0;
        draft.heat = json.has("heatRequirement") ? json.get("heatRequirement").getAsString() : "none";
        draft.keepHeldItem = json.has("keepHeldItem") && json.get("keepHeldItem").getAsBoolean();
        return draft;
    }

    /**
     * One ingredient from a recipe file. A fluid is told apart first, because a fluid entry is an object
     * like any other; everything the four modelled kinds do not cover comes back whole.
     */
    static IngredientValue readIngredient(JsonElement element) {
        if (element != null && element.isJsonObject()) {
            IngredientValue fluid = readFluid(element.getAsJsonObject());
            if (fluid != null) {
                return fluid.withSource(element);
            }
        }
        return IngredientValue.fromIngredientJson(element);
    }

    /** One result from a recipe file, or null when it names neither an item nor a fluid. */
    private static RecipeDraft.ResultEntry readResult(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        RecipeDraft.ResultEntry entry;
        IngredientValue fluid = readFluid(object);
        if (fluid != null) {
            entry = new RecipeDraft.ResultEntry(fluid, 1, 1.0f);
        } else {
            if (!object.has("item")) {
                return null; // neither an item nor a fluid we can show
            }
            ResourceLocation item = ResourceLocation.tryParse(object.get("item").getAsString());
            if (item == null) {
                return null;
            }
            int count = object.has("count") ? object.get("count").getAsInt() : 1;
            float chance = object.has("chance") ? object.get("chance").getAsFloat() : 1.0f;
            entry = new RecipeDraft.ResultEntry(IngredientValue.item(item), count, chance);
        }
        for (Map.Entry<String, JsonElement> key : object.entrySet()) {
            if (!MODELLED_RESULT_KEYS.contains(key.getKey())) {
                entry.extra.put(key.getKey(), key.getValue().deepCopy());
            }
        }
        return entry;
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

    /**
     * A fluid ingredient entry in the shape this version reads, or null when the entry is not one.
     *
     * <p>For a recipe crossing a version boundary. Create wrote its own flat entry up to 1.20.1 and
     * hands the field to the platform's fluid ingredient after it - a change of shape, not of spelling,
     * which nothing generic can carry. One entry in, one entry out: no draft is built and nothing else
     * about the recipe is read, so none of the round trip's rounding applies.
     */
    public static JsonObject normaliseFluidIngredient(JsonObject entry) {
        IngredientValue value = readFluid(entry);
        return value == null ? null : fluidJson(value);
    }

    /** The same for a fluid result, which is a fluid stack rather than an ingredient. */
    public static JsonObject normaliseFluidResult(JsonObject entry) {
        IngredientValue value = readFluid(entry);
        if (value == null || value.isFluidTag()) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("fluid", value.id().toString());
        json.addProperty("amount", IngredientValue.toPlatformAmount(value.amount()));
        return json;
    }

    /**
     * Reads an inline fluid entry, single or tagged, or null if this entry is not a fluid.
     *
     * <p>Read looser than it is written, so an entry that came from a later version still loads. From
     * 1.21.1 Create hands this field to the platform's own fluid ingredient, which names its kind in
     * {@code type} and a whole tag of fluids in {@code tag} rather than {@code fluidTag}. What tells
     * that apart from an item tag is the {@code amount}: only a fluid is measured.
     */
    private static IngredientValue readFluid(JsonObject object) {
        String key = object.has("fluid") ? "fluid"
                : object.has("fluidTag") ? "fluidTag"
                : (object.has("tag") && object.has("amount")) ? "tag" : null;
        if (key == null) {
            return null;
        }
        boolean tagged = !key.equals("fluid");
        ResourceLocation fluid = ResourceLocation.tryParse(object.get(key).getAsString());
        if (fluid == null) {
            return null;
        }
        int amount = object.has("amount")
                ? IngredientValue.fromPlatformAmount(object.get("amount").getAsLong())
                : IngredientValue.BUCKET;
        return tagged ? IngredientValue.fluidTag(fluid, amount) : IngredientValue.fluid(fluid, amount);
    }
}
