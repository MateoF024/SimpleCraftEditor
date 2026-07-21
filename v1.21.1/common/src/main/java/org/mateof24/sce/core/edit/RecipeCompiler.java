package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a {@link RecipeDraft} to and from vanilla recipe JSON. Working on JSON (rather than on
 * parsed {@code Recipe} objects) keeps ingredient tags and item data intact for exact round-tripping.
 * Supports the vanilla crafting (shaped/shapeless), cooking and stonecutting families.
 *
 * <p>1.21.1 recipe JSON differs from 1.20.1: a result is an item-stack object keyed by {@code id}
 * (e.g. {@code {"id":"minecraft:x","count":2}}) rather than the old {@code {"item":..}} object or a
 * bare id string. Ingredients keep the {@code {"item":..}} / {@code {"tag":..}} object form.
 */
public final class RecipeCompiler {
    private static final String KEY_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private RecipeCompiler() {
    }

    // ------------------------------------------------------------------ draft -> json

    public static JsonObject toJson(RecipeDraft draft) {
        return switch (draft.kind) {
            case CRAFTING_SHAPELESS -> shapeless(draft);
            case CRAFTING_SHAPED -> shaped(draft);
            case COOKING -> cooking(draft);
            case STONECUTTING -> stonecutting(draft);
            case CREATE_PROCESSING -> CreateRecipeCompiler.toJson(draft);
            case MECHANICAL_CRAFTING -> mechanicalCrafting(draft);
            case SEQUENCED_ASSEMBLY -> SequencedAssemblyCompiler.toJson(draft);
        };
    }

    private static JsonObject shapeless(RecipeDraft draft) {
        JsonObject json = base(draft, "minecraft:crafting_shapeless");
        JsonArray ingredients = new JsonArray();
        for (IngredientValue value : draft.inputs) {
            if (!value.isEmpty()) {
                ingredients.add(value.toIngredientJson());
            }
        }
        json.add("ingredients", ingredients);
        json.add("result", stackJson(draft, Math.max(1, draft.resultCount)));
        return json;
    }

    private static JsonObject shaped(RecipeDraft draft) {
        JsonObject json = base(draft, "minecraft:crafting_shaped");
        writePattern(json, draft);
        json.add("result", stackJson(draft, Math.max(1, draft.resultCount)));
        return json;
    }

    /**
     * Create's mechanical crafter takes a shaped recipe on a grid bigger than 3x3, so it reuses the pattern
     * and key of a normal shaped recipe and only adds its mirror flag.
     */
    private static JsonObject mechanicalCrafting(RecipeDraft draft) {
        JsonObject json = base(draft, "create:mechanical_crafting");
        writePattern(json, draft);
        json.add("result", stackJson(draft, Math.max(1, draft.resultCount)));
        json.addProperty("accept_mirrored", draft.acceptMirrored);
        return json;
    }

    /** Trims the filled area of the grid into a {@code pattern} plus its {@code key} legend. */
    private static void writePattern(JsonObject json, RecipeDraft draft) {
        int minRow = draft.height, maxRow = -1, minCol = draft.width, maxCol = -1;
        for (int row = 0; row < draft.height; row++) {
            for (int col = 0; col < draft.width; col++) {
                if (!draft.input(row * draft.width + col).isEmpty()) {
                    minRow = Math.min(minRow, row);
                    maxRow = Math.max(maxRow, row);
                    minCol = Math.min(minCol, col);
                    maxCol = Math.max(maxCol, col);
                }
            }
        }
        JsonArray pattern = new JsonArray();
        JsonObject key = new JsonObject();
        Map<String, Character> assigned = new LinkedHashMap<>();
        if (maxRow >= 0) {
            int next = 0;
            for (int row = minRow; row <= maxRow; row++) {
                StringBuilder line = new StringBuilder();
                for (int col = minCol; col <= maxCol; col++) {
                    IngredientValue value = draft.input(row * draft.width + col);
                    if (value.isEmpty()) {
                        line.append(' ');
                        continue;
                    }
                    String signature = value.kind() + "|" + value.id();
                    Character symbol = assigned.get(signature);
                    if (symbol == null) {
                        symbol = KEY_POOL.charAt(Math.min(next++, KEY_POOL.length() - 1));
                        assigned.put(signature, symbol);
                        key.add(symbol.toString(), value.toIngredientJson());
                    }
                    line.append(symbol);
                }
                pattern.add(line.toString());
            }
        }
        json.add("pattern", pattern);
        json.add("key", key);
    }

    private static JsonObject cooking(RecipeDraft draft) {
        JsonObject json = base(draft, draft.cooking.type);
        json.add("ingredient", draft.input(0).toIngredientJson());
        json.add("result", stackJson(draft, 1)); // a cooking recipe always produces a single item
        json.addProperty("experience", draft.experience);
        // Written as given. A time of zero is refused before it gets here, by the editor and again by
        // RecipeStateManager, rather than quietly replaced with a value the author did not choose.
        json.addProperty("cookingtime", draft.cookingTime);
        return json;
    }

    private static JsonObject stonecutting(RecipeDraft draft) {
        JsonObject json = base(draft, "minecraft:stonecutting");
        json.add("ingredient", draft.input(0).toIngredientJson());
        json.add("result", stackJson(draft, Math.max(1, draft.resultCount)));
        return json;
    }

    private static JsonObject base(RecipeDraft draft, String type) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        if (draft.group != null && !draft.group.isBlank()) {
            json.addProperty("group", draft.group);
        }
        return json;
    }

    /** A 1.21.1 result item stack: {@code {"id": "minecraft:x", "count": n}} (count omitted when 1). */
    private static JsonObject stackJson(RecipeDraft draft, int count) {
        JsonObject result = new JsonObject();
        result.addProperty("id", resultId(draft));
        if (count > 1) {
            result.addProperty("count", count);
        }
        return result;
    }

    private static String resultId(RecipeDraft draft) {
        return draft.result.isEmpty() ? "minecraft:air" : draft.result.id().toString();
    }

    // ------------------------------------------------------------------ json -> draft

    /** Best-effort parse of a supported vanilla recipe JSON into an editable draft; null if unsupported. */
    public static RecipeDraft fromJson(ResourceLocation id, JsonObject json) {
        String type = json.has("type") ? json.get("type").getAsString() : "";
        RecipeDraft draft = switch (type) {
            case "minecraft:crafting_shapeless" -> fromShapeless(json);
            case "minecraft:crafting_shaped" -> fromShaped(json);
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking" ->
                    fromCooking(json, type);
            case "minecraft:stonecutting" -> fromStonecutting(json);
            case "create:mechanical_crafting" -> fromMechanicalCrafting(json);
            case SequencedAssemblyCompiler.TYPE -> SequencedAssemblyCompiler.fromJson(id, json);
            default -> type.startsWith("create:") ? CreateRecipeCompiler.fromJson(id, json) : null;
        };
        if (draft != null) {
            draft.id = id;
            if (json.has("group")) {
                draft.group = json.get("group").getAsString();
            }
        }
        return draft;
    }

    private static RecipeDraft fromShapeless(JsonObject json) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = RecipeDraft.Kind.CRAFTING_SHAPELESS;
        draft.inputs.clear();
        if (json.has("ingredients") && json.get("ingredients").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("ingredients")) {
                draft.inputs.add(IngredientValue.fromIngredientJson(element));
            }
        }
        readResult(draft, json.get("result"));
        return draft;
    }

    private static RecipeDraft fromShaped(JsonObject json) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = RecipeDraft.Kind.CRAFTING_SHAPED;
        JsonArray pattern = json.has("pattern") ? json.getAsJsonArray("pattern") : new JsonArray();
        JsonObject key = json.has("key") ? json.getAsJsonObject("key") : new JsonObject();
        int height = Math.max(1, pattern.size());
        int width = 1;
        for (JsonElement row : pattern) {
            width = Math.max(width, row.getAsString().length());
        }
        draft.width = width;
        draft.height = height;
        draft.inputs.clear();
        for (int i = 0; i < width * height; i++) {
            draft.inputs.add(IngredientValue.empty());
        }
        for (int row = 0; row < pattern.size(); row++) {
            String line = pattern.get(row).getAsString();
            for (int col = 0; col < line.length(); col++) {
                char symbol = line.charAt(col);
                if (symbol == ' ') {
                    continue;
                }
                String symbolKey = String.valueOf(symbol);
                if (key.has(symbolKey)) {
                    draft.setInput(row * width + col, IngredientValue.fromIngredientJson(key.get(symbolKey)));
                }
            }
        }
        readResult(draft, json.get("result"));
        return draft;
    }

    /** Mechanical crafting reads exactly like a shaped recipe, plus its mirror flag. */
    private static RecipeDraft fromMechanicalCrafting(JsonObject json) {
        RecipeDraft draft = fromShaped(json);
        draft.kind = RecipeDraft.Kind.MECHANICAL_CRAFTING;
        // Create names the flag differently across versions; accept either so a recipe authored on one
        // version still loads on the other.
        String key = json.has("accept_mirrored") ? "accept_mirrored"
                : (json.has("acceptMirrored") ? "acceptMirrored" : null);
        draft.acceptMirrored = key != null && json.get(key).getAsBoolean();
        return draft;
    }

    private static RecipeDraft fromCooking(JsonObject json, String type) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = RecipeDraft.Kind.COOKING;
        for (RecipeDraft.Cooking cooking : RecipeDraft.Cooking.values()) {
            if (cooking.type.equals(type)) {
                draft.cooking = cooking;
            }
        }
        draft.inputs.clear();
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("ingredient")));
        readResult(draft, json.get("result"));
        draft.experience = json.has("experience") ? json.get("experience").getAsFloat() : 0.0f;
        draft.cookingTime = json.has("cookingtime") ? json.get("cookingtime").getAsInt() : draft.cooking.defaultTime;
        return draft;
    }

    private static RecipeDraft fromStonecutting(JsonObject json) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = RecipeDraft.Kind.STONECUTTING;
        draft.inputs.clear();
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("ingredient")));
        readResult(draft, json.get("result"));
        return draft;
    }

    /** Reads a 1.21.1 result stack ({@code {"id":..,"count":..}}), tolerating the older forms. */
    private static void readResult(RecipeDraft draft, JsonElement result) {
        if (result == null) {
            return;
        }
        if (result.isJsonObject()) {
            JsonObject object = result.getAsJsonObject();
            String idKey = object.has("id") ? "id" : (object.has("item") ? "item" : null);
            if (idKey != null) {
                draft.result = IngredientValue.item(ResourceLocation.tryParse(object.get(idKey).getAsString()));
            }
            draft.resultCount = object.has("count") ? object.get("count").getAsInt() : 1;
        } else if (result.isJsonPrimitive()) {
            ResourceLocation id = ResourceLocation.tryParse(result.getAsString());
            draft.result = id == null ? IngredientValue.empty() : IngredientValue.item(id);
            draft.resultCount = 1;
        }
    }
}
