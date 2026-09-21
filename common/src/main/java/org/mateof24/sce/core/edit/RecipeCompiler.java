package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.mateof24.sce.core.recipe.InheritingCraftingRecipe;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a {@link RecipeDraft} to and from vanilla recipe JSON. Working on JSON (rather than on
 * parsed {@code Recipe} objects) keeps ingredient tags and item data intact for exact round-tripping.
 * Supports the vanilla crafting (shaped/shapeless), cooking and stonecutting families.
 */
public final class RecipeCompiler {
    private static final String KEY_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private RecipeCompiler() {
    }

    // ------------------------------------------------------------------ draft -> json

    public static JsonObject toJson(RecipeDraft draft) {
        JsonObject json = switch (draft.kind) {
            case CRAFTING_SHAPELESS -> shapeless(draft);
            case CRAFTING_SHAPED -> shaped(draft);
            case COOKING -> cooking(draft);
            case STONECUTTING -> stonecutting(draft);
            case SMITHING_TRANSFORM -> smithing(draft);
            case CREATE_PROCESSING -> CreateRecipeCompiler.toJson(draft);
            case MECHANICAL_CRAFTING -> mechanicalCrafting(draft);
            case SEQUENCED_ASSEMBLY -> SequencedAssemblyCompiler.toJson(draft);
        };
        restoreInto(draft, json);
        applyDataRules(draft, json);
        return json;
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
        json.add("result", craftingResult(draft));
        return json;
    }

    private static JsonObject shaped(RecipeDraft draft) {
        JsonObject json = base(draft, "minecraft:crafting_shaped");
        writePattern(json, draft);
        json.add("result", craftingResult(draft));
        return json;
    }

    /**
     * Create's mechanical crafter takes a shaped recipe on a grid bigger than 3x3, so it reuses the pattern
     * and key of a normal shaped recipe and only adds its mirror flag.
     */
    private static JsonObject mechanicalCrafting(RecipeDraft draft) {
        JsonObject json = base(draft, "create:mechanical_crafting");
        writePattern(json, draft);
        json.add("result", craftingResult(draft));
        json.addProperty("acceptMirrored", draft.acceptMirrored);
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
        json.addProperty("result", resultId(draft));
        json.addProperty("experience", draft.experience);
        // Written as given. A time of zero is refused before it gets here, by the editor and again by
        // RecipeStateManager, rather than quietly replaced with a value the author did not choose.
        json.addProperty("cookingtime", draft.cookingTime);
        return json;
    }

    private static JsonObject stonecutting(RecipeDraft draft) {
        JsonObject json = base(draft, "minecraft:stonecutting");
        json.add("ingredient", draft.input(0).toIngredientJson());
        json.addProperty("result", resultId(draft));
        json.addProperty("count", Math.max(1, draft.resultCount));
        return json;
    }

    /**
     * A smithing recipe: template, base and addition, and then a result unless it is a trim.
     *
     * <p>All three are required on this version, so all three are written; the game refuses a recipe
     * that leaves one out, which is the answer the author needs rather than a silent half-recipe.
     *
     * <p>The table's trim recipe is not offered: its addition has to be one of the trim materials,
     * so there is nothing there for an author to choose.
     */
    private static JsonObject smithing(RecipeDraft draft) {
        JsonObject json = base(draft, "minecraft:smithing_transform");
        json.add("template", draft.input(0).toIngredientJson());
        json.add("base", draft.input(1).toIngredientJson());
        json.add("addition", draft.input(2).toIngredientJson());
        json.add("result", craftingResult(draft));
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

    private static JsonObject craftingResult(RecipeDraft draft) {
        JsonObject result = new JsonObject();
        result.addProperty("item", resultId(draft));
        result.addProperty("count", Math.max(1, draft.resultCount));
        return result;
    }

    private static String resultId(RecipeDraft draft) {
        return draft.result.isEmpty() ? "minecraft:air" : draft.result.id().toString();
    }



    /**
     * A recipe whose {@code type} belongs to another mod but whose shape is one this editor draws.
     *
     * <p>Mods routinely register a recipe that <em>is</em> a shaped or shapeless recipe, field for field,
     * and differs only in what its class does when the result is assembled — Sophisticated Backpacks
     * copies the old backpack's contents onto the new one that way, and Cobblemon's cooking pot adds its
     * own seasoning fields around an ordinary grid. Sending all of those to the raw JSON editor because
     * their type is unfamiliar is a poor answer when the grid is exactly what the editor is for.
     *
     * <p>So the shape is read instead of the name — and then checked. It is only accepted if every field
     * inside the parts this editor rewrites is one it understands: see {@link #understandsEveryField}.
     * A recipe that hides anything in there still goes to the raw editor, where nothing can be lost.
     * Together with the type and the untouched extras being written back, editing such a recipe leaves
     * everything that made it that mod's recipe intact.
     */
    private static RecipeDraft fromForeignType(JsonObject json) {
        if (!understandsEveryField(json)) {
            return null;
        }
        if (json.has("pattern") && json.has("key")) {
            RecipeDraft draft = fromShaped(json);
            // A shaped recipe is drawn on a three by three grid here. A bigger pattern would be cut down
            // to fit and the rest of it lost the moment it was saved, so it goes to the raw editor.
            return draft.width <= 3 && draft.height <= 3 ? draft : null;
        }
        if (json.has("ingredients")) {
            RecipeDraft draft = fromShapeless(json);
            // Same reason: the screen offers nine slots and would silently drop a tenth ingredient.
            return draft.inputs.size() <= 9 ? draft : null;
        }
        return null;
    }

    /**
     * Whether every field inside the parts this editor rewrites is one it can reproduce.
     *
     * <p>Deliberately strict, and only used for a type this editor does not own. Keys outside these parts
     * are kept verbatim ({@link RecipeDraft#extras}), but anything nested inside an ingredient or a result
     * would be rewritten from the draft and therefore lost. Refusing here costs the author a visual
     * editor for that one recipe; accepting wrongly costs them the recipe.
     */
    private static boolean understandsEveryField(JsonObject json) {
        if (json.has("key")) {
            if (!json.get("key").isJsonObject()) {
                return false;
            }
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("key").entrySet()) {
                if (!plainIngredient(entry.getValue())) {
                    return false;
                }
            }
        }
        if (json.has("ingredients")) {
            if (!json.get("ingredients").isJsonArray()) {
                return false;
            }
            for (JsonElement element : json.getAsJsonArray("ingredients")) {
                if (!plainIngredient(element)) {
                    return false;
                }
            }
        }
        return !json.has("result") || plainResult(json.get("result"));
    }

    /** One item or one tag and nothing else. A list of options is refused: only the first would survive. */
    private static boolean plainIngredient(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.size() != 1) {
            return false;
        }
        return object.has("item") || object.has("tag");
    }

    /** An item and optionally how many of it. Anything else in there would not survive a rewrite. */
    private static boolean plainResult(JsonElement element) {
        if (element == null) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            return true; // cooking and stonecutting name the result outright
        }
        if (!element.isJsonObject()) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!entry.getKey().equals("item") && !entry.getKey().equals("count")) {
                return false;
            }
        }
        return true;
    }


    /**
     * Writes this mod's own data block, and switches the recipe to the kind that understands it.
     *
     * <p>Only for the crafting shapes, and only when there is something to say: a recipe that neither
     * requires data on its ingredients nor puts any on its result stays an ordinary vanilla recipe, which
     * is what most of them are. Runs after the preserved parts are put back, so asking for this is a
     * deliberate act that wins over a type remembered from the file.
     */
    private static void applyDataRules(RecipeDraft draft, JsonObject json) {
        boolean carries = !draft.carry.isEmpty() && !draft.carry.equals("none");
        if (!carries && draft.requiredStacks.isEmpty()) {
            return;
        }
        if (draft.kind == RecipeDraft.Kind.CRAFTING_SHAPED) {
            json.addProperty("type", InheritingCraftingRecipe.SHAPED_TYPE);
        } else if (draft.kind == RecipeDraft.Kind.CRAFTING_SHAPELESS) {
            json.addProperty("type", InheritingCraftingRecipe.SHAPELESS_TYPE);
        } else {
            return; // nothing else assembles a result this way
        }
        json.add(InheritingCraftingRecipe.DATA_KEY, InheritingCraftingRecipe.writeData(
                draft.carry, draft.requiredStacks, draft.resultStack));
    }

    /** Reads the data block back, so re-opening a recipe shows the rules it was saved with. */
    private static void readDataRules(RecipeDraft draft, JsonObject json) {
        draft.requiredStacks.clear();
        draft.resultStack = "";
        draft.carry = "auto";
        draft.matchData = "auto";
        if (!json.has(InheritingCraftingRecipe.DATA_KEY)
                || !json.get(InheritingCraftingRecipe.DATA_KEY).isJsonObject()) {
            return;
        }
        JsonObject data = json.getAsJsonObject(InheritingCraftingRecipe.DATA_KEY);
        draft.carry = data.has("carry") ? data.get("carry").getAsString() : "none";
        if (data.has("require") && data.get("require").isJsonArray()) {
            for (JsonElement element : data.getAsJsonArray("require")) {
                draft.requiredStacks.add(element.getAsString());
            }
        }
        // Saved rules are explicit: reopening must show what the file says, not guess again.
        draft.matchData = draft.requiredStacks.isEmpty() ? "ignore" : "require";
        draft.resultStack = data.has("result") ? data.get("result").getAsString() : "";
    }

    // ------------------------------------------------------------------ what the editor does not model

    /**
     * Every top-level key some compiler here writes for itself. Anything else in a recipe file belongs to
     * whoever wrote it and is carried through untouched — see {@link RecipeDraft#extras}.
     */
    private static final java.util.Set<String> MODELLED_KEYS = java.util.Set.of(
            "type", "group",
            "pattern", "key", "ingredients", "ingredient", "result", "results",
            "experience", "cookingtime", "count",
            "acceptMirrored", "accept_mirrored",
            "processingTime", "processing_time", "heatRequirement", "heat_requirement",
            // Create's deployer flag. Modelled rather than carried, because a carried one could not be
            // turned off: restoreInto only puts back what the editor did not write, so the old value
            // would come straight back the moment the author cleared the box.
            "keepHeldItem", "keep_held_item",
            "transitionalItem", "transitional_item", "sequence", "loops",
            InheritingCraftingRecipe.DATA_KEY);

    /**
     * The smithing table's own fields, which this editor models — but only for its own recipe. Names as ordinary as {@code base} belong to plenty of other mods' recipe types, and
     * carrying theirs through untouched is the whole point of the set above.
     */
    private static final java.util.Set<String> SMITHING_KEYS =
            java.util.Set.of("template", "base", "addition");

    /** Remembers a recipe's own type and everything about it this editor has no field for. */
    public static void preserveFrom(RecipeDraft draft, JsonObject json) {
        String type = json.has("type") ? json.get("type").getAsString() : "";
        // A type this editor writes for itself is not remembered: it follows the settings on
        // screen, and holding on to it would override turning inheritance back off.
        draft.sourceType = InheritingCraftingRecipe.SHAPED_TYPE.equals(type)
                || InheritingCraftingRecipe.SHAPELESS_TYPE.equals(type) ? "" : type;
        boolean smithing = "minecraft:smithing_transform".equals(type);
        draft.extras.clear();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            if (MODELLED_KEYS.contains(key) || (smithing && SMITHING_KEYS.contains(key))) {
                continue;
            }
            draft.extras.put(key, entry.getValue().deepCopy());
        }
    }

    /**
     * Puts back what {@link #preserveFrom} kept. What the editor wrote always wins: these are the parts
     * it does not understand, so it must not be able to overwrite a decision it did understand.
     */
    public static void restoreInto(RecipeDraft draft, JsonObject json) {
        for (Map.Entry<String, JsonElement> entry : draft.extras.entrySet()) {
            if (!json.has(entry.getKey())) {
                json.add(entry.getKey(), entry.getValue());
            }
        }
        if (!draft.sourceType.isEmpty()) {
            json.addProperty("type", draft.sourceType);
        }
    }

    // ------------------------------------------------------------------ json -> draft

    /** Best-effort parse of a supported vanilla recipe JSON into an editable draft; null if unsupported. */
    public static RecipeDraft fromJson(ResourceLocation id, JsonObject json) {
        String type = json.has("type") ? json.get("type").getAsString() : "";
        RecipeDraft draft = switch (type) {
            case "minecraft:crafting_shapeless", InheritingCraftingRecipe.SHAPELESS_TYPE -> fromShapeless(json);
            case "minecraft:crafting_shaped", InheritingCraftingRecipe.SHAPED_TYPE -> fromShaped(json);
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking" ->
                    fromCooking(json, type);
            case "minecraft:stonecutting" -> fromStonecutting(json);
            case "minecraft:smithing_transform" -> fromSmithing(json);
            case "create:mechanical_crafting" -> fromMechanicalCrafting(json);
            case SequencedAssemblyCompiler.TYPE -> SequencedAssemblyCompiler.fromJson(id, json);
            default -> type.startsWith("create:")
                    ? CreateRecipeCompiler.fromJson(id, json)
                    : fromForeignType(json);
        };
        if (draft != null) {
            draft.id = id;
            if (json.has("group")) {
                draft.group = json.get("group").getAsString();
            }
            preserveFrom(draft, json);
            readDataRules(draft, json);
        }
        return draft;
    }

    private static RecipeDraft fromSmithing(JsonObject json) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = RecipeDraft.Kind.SMITHING_TRANSFORM;
        draft.inputs.clear();
        // Always three, in the table's own order, so each one keeps the slot the author expects.
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("template")));
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("base")));
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("addition")));
        readCraftingResult(draft, json.get("result"));
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
        readCraftingResult(draft, json.get("result"));
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
        readCraftingResult(draft, json.get("result"));
        return draft;
    }

    /** Mechanical crafting reads exactly like a shaped recipe, plus its mirror flag. */
    private static RecipeDraft fromMechanicalCrafting(JsonObject json) {
        RecipeDraft draft = fromShaped(json);
        draft.kind = RecipeDraft.Kind.MECHANICAL_CRAFTING;
        // Create names the flag differently across versions; accept either so a recipe authored on one
        // version still loads on the other.
        String key = json.has("acceptMirrored") ? "acceptMirrored"
                : (json.has("accept_mirrored") ? "accept_mirrored" : null);
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
        draft.result = itemFromString(json.get("result"));
        draft.resultCount = 1;
        draft.experience = json.has("experience") ? json.get("experience").getAsFloat() : 0.0f;
        draft.cookingTime = json.has("cookingtime") ? json.get("cookingtime").getAsInt() : draft.cooking.defaultTime;
        return draft;
    }

    private static RecipeDraft fromStonecutting(JsonObject json) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = RecipeDraft.Kind.STONECUTTING;
        draft.inputs.clear();
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("ingredient")));
        draft.result = itemFromString(json.get("result"));
        draft.resultCount = json.has("count") ? json.get("count").getAsInt() : 1;
        return draft;
    }

    private static void readCraftingResult(RecipeDraft draft, JsonElement result) {
        if (result != null && result.isJsonObject()) {
            JsonObject object = result.getAsJsonObject();
            if (object.has("item")) {
                draft.result = IngredientValue.item(ResourceLocation.tryParse(object.get("item").getAsString()));
            }
            draft.resultCount = object.has("count") ? object.get("count").getAsInt() : 1;
        }
    }

    private static IngredientValue itemFromString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return IngredientValue.empty();
        }
        ResourceLocation id = ResourceLocation.tryParse(element.getAsString());
        return id == null ? IngredientValue.empty() : IngredientValue.item(id);
    }
}
