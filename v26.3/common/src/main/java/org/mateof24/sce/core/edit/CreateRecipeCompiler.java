package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
import net.minecraft.resources.Identifier;

/**
 * Round-trips Create processing recipe JSON (mixing, crushing, pressing, …) to and from a
 * {@link RecipeDraft}. Items, item tags, fluids and fluid tags are modelled on both sides, with a count
 * and a drop chance on each result.
 *
 * <p>Anything else is carried through rather than dropped. An ingredient whose shape this editor has no
 * model for keeps the JSON it came from ({@link IngredientValue}); a result's own extra keys —
 * {@code components}, which Create allows so a recipe can hand back a named or enchanted item — are kept
 * beside it. Between them they are the difference between opening one of Create's compatibility recipes
 * and saving it unchanged, and opening it and quietly emptying it: 379 of Create's own recipes wrap
 * another mod's item in {@code neoforge:compound} so the recipe survives that mod being absent.
 *
 * <p>The Create limits that decide how many of each a type may have live in {@link RecipeModes}: they
 * belong to Create's own recipe classes, and going over one of them does not misbehave, it stops the
 * recipe loading at all.
 */
public final class CreateRecipeCompiler {
    /** Result keys this editor models; everything else beside them is the author's and is kept. */
    private static final java.util.Set<String> MODELLED_RESULT_KEYS =
            java.util.Set.of("item", "id", "count", "chance", "fluid", "fluidTag", "amount");

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
            json.addProperty("processing_time", draft.processingTime);
        }
        if (draft.heat != null && !draft.heat.isBlank() && !draft.heat.equals("none")) {
            json.addProperty("heat_requirement", draft.heat);
        }
        if (draft.keepHeldItem) {
            json.addProperty("keep_held_item", true);
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
            return value.toIngredientObject();
        }
        return value.isFluid() ? fluidIngredientJson(value) : value.toIngredientObject();
    }

    /** One result, or null when the slot is empty. */
    private static JsonObject resultJson(RecipeDraft.ResultEntry entry) {
        if (entry.item == null || entry.item.isEmpty() || entry.item.isFluidTag()) {
            // A result has to name one concrete fluid, so a tag cannot be written here.
            return null;
        }
        JsonObject result;
        if (entry.item.isFluid()) {
            result = fluidResultJson(entry.item); // a fluid result carries an amount, not a count/chance
        } else {
            result = new JsonObject();
            result.addProperty("id", entry.item.id().toString()); // Create 6.x results are vanilla item stacks
            if (entry.count > 1) {
                result.addProperty("count", entry.count);
            }
            // Written whenever it is not the default, not only when it is below it: in a recipe sequence
            // the same field is a weight, and those run well above 1.
            if (entry.chance != 1.0f) {
                result.addProperty("chance", entry.chance);
            }
        }
        for (java.util.Map.Entry<String, JsonElement> extra : entry.extra.entrySet()) {
            if (!result.has(extra.getKey())) {
                result.add(extra.getKey(), extra.getValue().deepCopy());
            }
        }
        return result;
    }

    public static RecipeDraft fromJson(Identifier id, JsonObject json) {
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
        draft.processingTime = readInt(json, "processing_time", "processingTime");
        draft.heat = readString(json, "heat_requirement", "heatRequirement", "none");
        draft.keepHeldItem = readBoolean(json, "keep_held_item", "keepHeldItem");
        return draft;
    }

    /**
     * One ingredient from a recipe file. A fluid is told apart first, because a fluid entry is an object
     * like any other; everything the four modelled kinds do not cover comes back whole.
     */
    static IngredientValue readIngredient(JsonElement element) {
        if (element != null && element.isJsonObject()) {
            IngredientValue fluid = readFluidIngredient(element.getAsJsonObject());
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
        IngredientValue fluid = readFluidResult(object);
        if (fluid != null) {
            entry = new RecipeDraft.ResultEntry(fluid, 1, 1.0f);
        } else {
            String idKey = object.has("id") ? "id" : (object.has("item") ? "item" : null);
            if (idKey == null) {
                return null; // neither an item nor a fluid we can show
            }
            Identifier item = Identifier.tryParse(object.get(idKey).getAsString());
            if (item == null) {
                return null;
            }
            int count = object.has("count") ? object.get("count").getAsInt() : 1;
            float chance = object.has("chance") ? object.get("chance").getAsFloat() : 1.0f;
            entry = new RecipeDraft.ResultEntry(IngredientValue.item(item), count, chance);
        }
        for (java.util.Map.Entry<String, JsonElement> key : object.entrySet()) {
            if (!MODELLED_RESULT_KEYS.contains(key.getKey())) {
                entry.extra.put(key.getKey(), key.getValue().deepCopy());
            }
        }
        return entry;
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
     * A fluid ingredient entry in the shape this version reads, or null when the entry is not one.
     *
     * <p>For a recipe crossing a version boundary. Create wrote its own flat entry up to 1.20.1 and
     * hands the field to the platform's fluid ingredient after it - a change of shape, not of spelling,
     * which nothing generic can carry. One entry in, one entry out: no draft is built and nothing else
     * about the recipe is read, so none of the round trip's rounding applies.
     */
    public static JsonObject normaliseFluidIngredient(JsonObject entry) {
        IngredientValue value = readFluidIngredient(entry);
        return value == null ? null : fluidIngredientJson(value);
    }

    /** The same for a fluid result, which is a fluid stack rather than an ingredient. */
    public static JsonObject normaliseFluidResult(JsonObject entry) {
        IngredientValue value = readFluidResult(entry);
        if (value == null) {
            // The older spelling, which named a fluid result the way it named a fluid ingredient.
            value = readFluidIngredient(entry);
        }
        return value == null || value.isFluidTag() ? null : fluidResultJson(value);
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
        Identifier fluid = Identifier.tryParse(object.get(key).getAsString());
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
        Identifier fluid = Identifier.tryParse(object.get("id").getAsString());
        return fluid == null ? null
                : IngredientValue.fluid(fluid, IngredientValue.fromPlatformAmount(object.get("amount").getAsLong()));
    }

    private static int readInt(JsonObject json, String key, String legacyKey) {
        if (json.has(key)) {
            return json.get(key).getAsInt();
        }
        return json.has(legacyKey) ? json.get(legacyKey).getAsInt() : 0;
    }

    private static boolean readBoolean(JsonObject json, String key, String legacyKey) {
        if (json.has(key)) {
            return json.get(key).getAsBoolean();
        }
        return json.has(legacyKey) && json.get(legacyKey).getAsBoolean();
    }

    private static String readString(JsonObject json, String key, String legacyKey, String fallback) {
        if (json.has(key)) {
            return json.get(key).getAsString();
        }
        return json.has(legacyKey) ? json.get(legacyKey).getAsString() : fallback;
    }
}
