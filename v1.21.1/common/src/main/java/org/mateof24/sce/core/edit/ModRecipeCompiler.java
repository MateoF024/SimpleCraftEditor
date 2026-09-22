package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The recipe types that belong to another mod's workbench: Farmer's Delight's cutting board and cooking
 * pot, Twilight Forest's uncrafting table, drying rack and scepter repair, and Cobblemon's brewing stand.
 *
 * <p>None of them needs anything of the engine that vanilla recipes do not: they are ordinary datapack
 * recipes, loaded by the same {@code RecipeManager} this mod already hooks, and a pack can already replace
 * one by hand. What was missing was an editor that knows which fields each one has — which is all this
 * class is, one pair of read/write methods per type.
 *
 * <p>Kept out of {@link RecipeCompiler} because the two answer different questions. That one knows what
 * the <em>game</em> writes this version and may assume it; this one knows what six other projects write,
 * and every one of those is free to change its mind on its own schedule. Their shapes differ between
 * Minecraft versions in ways vanilla's do not — a cutting result is a flat object on 1.20.1 and a nested
 * stack on 1.21.1, an uncrafting recipe carries its count inside {@code input} on one and beside it on the
 * other — so this file is one of the ones that genuinely differs between the two trees.
 *
 * <p>Every field these types have that this editor does not draw is carried through untouched, the same
 * way the rest of the editor does it: a cutting recipe's {@code sound} and a scepter repair's
 * {@code category} are left exactly as they were found.
 */
public final class ModRecipeCompiler {
    public static final String FD_CUTTING = "farmersdelight:cutting";
    public static final String FD_COOKING = "farmersdelight:cooking";
    public static final String TF_UNCRAFTING = "twilightforest:uncrafting";
    public static final String TF_DRYING = "twilightforest:drying";
    public static final String TF_SCEPTER_REPAIR = "twilightforest:scepter_repair";
    public static final String COBBLEMON_BREWING = "cobblemon:brewing_stand";

    /** Farmer's Delight refuses a cutting recipe with more than four results, by name, in its serializer. */
    private static final int MAX_CUTTING_RESULTS = 4;
    /** The cooking pot has six ingredient slots, and its 1.20.1 serializer says so in as many words. */
    private static final int MAX_COOKING_INGREDIENTS = 6;
    /** How many repair ingredients the editor offers. Every recipe Twilight Forest ships names one. */
    private static final int MAX_REPAIR_INGREDIENTS = 4;

    /**
     * What each type's compiler writes for itself, beyond the keys every recipe has.
     *
     * <p>Per type rather than in one set, because these are ordinary words — {@code input},
     * {@code container}, {@code cost} — and plenty of other mods' recipe types use them for something
     * else. Carrying those through untouched is the whole point of {@link RecipeDraft#extras}, and a
     * global set would quietly eat them.
     */
    private static final Map<String, Set<String>> MODELLED_KEYS = Map.of(
            FD_CUTTING, Set.of("tool"),
            FD_COOKING, Set.of("container", "recipe_book_tab"),
            TF_UNCRAFTING, Set.of("cost", "input", "input_count"),
            TF_DRYING, Set.of("input", "filter_time"),
            TF_SCEPTER_REPAIR, Set.of("scepter", "repair_ingredients", "durability"),
            COBBLEMON_BREWING, Set.of("input", "bottle"));

    private ModRecipeCompiler() {
    }

    /** Whether this editor owns the type, and so writes it rather than remembering it. */
    public static boolean owns(String type) {
        return MODELLED_KEYS.containsKey(type);
    }

    /** The keys the compiler for {@code type} writes itself; everything else in the file is carried. */
    public static Set<String> modelledKeys(String type) {
        return MODELLED_KEYS.getOrDefault(type, Set.of());
    }


    /**
     * The one item a recipe of these types is <em>about</em>, for the two that name no result at all.
     *
     * <p>The editor key asks what makes an item and offers the recipes that do. An uncrafting recipe is
     * a craft read backwards, so the item it is about is the one you put in - the thing being taken
     * apart - exactly as the item a crafting recipe is about is the one that comes out. Its grid is the
     * pieces, and offering the recipe under each of them would be like offering a crafting recipe under
     * every plank it uses.
     *
     * <p>A scepter repair names one item and it is both halves at once: the scepter goes in worn out
     * and comes back charged, so there is nothing to choose between.
     *
     * <p>Null for every other type, including the ones here that do name a result: those are already
     * found by it, and this is only for the recipes that would otherwise be found by nothing.
     */
    public static JsonElement subjectOf(JsonObject json) {
        String type = json.has("type") ? json.get("type").getAsString() : "";
        return switch (type) {
            case TF_UNCRAFTING -> json.get("input");
            case TF_SCEPTER_REPAIR -> json.get("scepter");
            default -> null;
        };
    }

    // ------------------------------------------------------------------ draft -> json

    /** Null for a draft that is not one of these types, so the caller can fall through to its own. */
    public static JsonObject toJson(RecipeDraft draft) {
        return switch (draft.kind) {
            case CUTTING_BOARD -> cutting(draft);
            case FD_COOKING -> cooking(draft);
            case UNCRAFTING -> uncrafting(draft);
            case DRYING -> drying(draft);
            case SCEPTER_REPAIR -> scepterRepair(draft);
            case BREWING_STAND -> brewing(draft);
            default -> null;
        };
    }

    private static JsonObject cutting(RecipeDraft draft) {
        JsonObject json = base(draft, FD_CUTTING);
        JsonArray ingredients = new JsonArray();
        ingredients.add(draft.input(0).toIngredientJson());
        json.add("ingredients", ingredients);
        // Written as it was found. On NeoForge the tool is a list of alternatives, on Fabric a custom
        // ingredient object, and on 1.20.1 a tool action - three shapes for the same thing, none of them
        // ours to rebuild. A tool the author picked themselves is one plain ingredient, which the same
        // field reads happily.
        json.add("tool", draft.input(1).toIngredientJson());
        JsonArray results = new JsonArray();
        for (RecipeDraft.ResultEntry entry : draft.results) {
            if (entry.item == null || entry.item.isEmpty()) {
                continue;
            }
            JsonObject result = new JsonObject();
            result.add("item", stack(entry.item, Math.max(1, entry.count), true));
            // Only when it is one: Farmer's Delight defaults a result with no chance to a certain drop,
            // and writing 1.0 on all of them would add a field to every recipe that was opened.
            if (entry.chance != 1.0f) {
                result.addProperty("chance", entry.chance);
            }
            for (Map.Entry<String, JsonElement> extra : entry.extra.entrySet()) {
                if (!result.has(extra.getKey())) {
                    result.add(extra.getKey(), extra.getValue());
                }
            }
            results.add(result);
        }
        json.add("result", results);
        return json;
    }

    private static JsonObject cooking(RecipeDraft draft) {
        JsonObject json = base(draft, FD_COOKING);
        JsonArray ingredients = new JsonArray();
        for (IngredientValue value : draft.inputs) {
            if (!value.isEmpty()) {
                ingredients.add(value.toIngredientJson());
            }
        }
        json.add("ingredients", ingredients);
        json.add("result", stack(draft.result, Math.max(1, draft.resultCount), true));
        // The bowl the meal is served in, and only when there is one: a recipe with no container is the
        // normal case, and an empty stack there is not something the codec accepts.
        if (!draft.container.isEmpty()) {
            json.add("container", stack(draft.container, Math.max(1, draft.containerCount), true));
        }
        json.addProperty("experience", draft.experience);
        json.addProperty("cookingtime", draft.cookingTime);
        json.addProperty("recipe_book_tab", tab(draft));
        return json;
    }

    private static JsonObject uncrafting(RecipeDraft draft) {
        JsonObject json = base(draft, TF_UNCRAFTING);
        json.addProperty("cost", number(draft, "cost", 0));
        json.add("input", draft.input(0).toIngredientJson());
        // Left out when it is one, which is what the field means when it is absent.
        int count = Math.max(1, number(draft, "input_count", 1));
        if (count > 1) {
            json.addProperty("input_count", count);
        }
        writeGrid(json, draft);
        return json;
    }

    private static JsonObject drying(RecipeDraft draft) {
        JsonObject json = base(draft, TF_DRYING);
        json.add("input", draft.input(0).toIngredientJson());
        json.add("result", stack(draft.result, Math.max(1, draft.resultCount), true));
        json.addProperty("filter_time", Math.max(1, number(draft, "filter_time", 40)));
        return json;
    }

    private static JsonObject scepterRepair(RecipeDraft draft) {
        JsonObject json = base(draft, TF_SCEPTER_REPAIR);
        // The scepter is named as a plain item id, not as a stack: the recipe hands back the very item it
        // was given, with some of its charge restored, so there is no count to write.
        json.addProperty("scepter", draft.result.isEmpty() ? "minecraft:air" : draft.result.id().toString());
        JsonArray ingredients = new JsonArray();
        for (IngredientValue value : draft.inputs) {
            if (!value.isEmpty()) {
                ingredients.add(value.toIngredientJson());
            }
        }
        json.add("repair_ingredients", ingredients);
        json.addProperty("durability", Math.max(1, number(draft, "durability", 9)));
        return json;
    }

    private static JsonObject brewing(RecipeDraft draft) {
        JsonObject json = base(draft, COBBLEMON_BREWING);
        json.add("bottle", draft.input(0).toIngredientJson());
        json.add("input", draft.input(1).toIngredientJson());
        // Cobblemon leaves a count of one out, where the other two write it.
        json.add("result", stack(draft.result, Math.max(1, draft.resultCount), false));
        return json;
    }

    // ------------------------------------------------------------------ json -> draft

    /**
     * Null for a type this class does not own, and null again for one it does own but whose recipe does
     * not fit the editor — more results than the screen has slots, a pattern bigger than the grid. The
     * caller sends those to the raw JSON editor, where nothing can be lost.
     */
    public static RecipeDraft fromJson(ResourceLocation id, JsonObject json) {
        String type = json.has("type") ? json.get("type").getAsString() : "";
        return switch (type) {
            case FD_CUTTING -> fromCutting(json);
            case FD_COOKING -> fromCooking(json);
            case TF_UNCRAFTING -> fromUncrafting(json);
            case TF_DRYING -> fromDrying(json);
            case TF_SCEPTER_REPAIR -> fromScepterRepair(json);
            case COBBLEMON_BREWING -> fromBrewing(json);
            default -> null;
        };
    }

    private static RecipeDraft fromCutting(JsonObject json) {
        RecipeDraft draft = start(RecipeDraft.Kind.CUTTING_BOARD);
        JsonArray ingredients = array(json, "ingredients");
        if (ingredients.size() > 1) {
            return null; // the serializer refuses this too; the raw editor is the honest answer
        }
        draft.inputs.add(ingredients.isEmpty()
                ? IngredientValue.empty() : IngredientValue.fromIngredientJson(ingredients.get(0)));
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("tool")));
        JsonArray results = array(json, "result");
        if (results.size() > MAX_CUTTING_RESULTS) {
            return null;
        }
        for (JsonElement element : results) {
            RecipeDraft.ResultEntry entry = readChanceResult(element);
            if (entry != null) {
                draft.results.add(entry);
            }
        }
        return draft;
    }

    /** One entry of a cutting board's result list: a stack, a chance, and whatever else is in there. */
    private static RecipeDraft.ResultEntry readChanceResult(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement item = object.get("item");
        Stack read = readStack(item);
        RecipeDraft.ResultEntry entry = new RecipeDraft.ResultEntry(read.value(), read.count(),
                object.has("chance") ? object.get("chance").getAsFloat() : 1.0f);
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            if (!field.getKey().equals("item") && !field.getKey().equals("chance")) {
                entry.extra.put(field.getKey(), field.getValue().deepCopy());
            }
        }
        return entry;
    }

    private static RecipeDraft fromCooking(JsonObject json) {
        RecipeDraft draft = start(RecipeDraft.Kind.FD_COOKING);
        JsonArray ingredients = array(json, "ingredients");
        if (ingredients.size() > MAX_COOKING_INGREDIENTS) {
            return null;
        }
        for (JsonElement element : ingredients) {
            draft.inputs.add(IngredientValue.fromIngredientJson(element));
        }
        Stack result = readStack(json.get("result"));
        draft.result = result.value();
        draft.resultCount = result.count();
        Stack container = readStack(json.get("container"));
        draft.container = container.value();
        draft.containerCount = container.count();
        draft.experience = json.has("experience") ? json.get("experience").getAsFloat() : 0.0f;
        draft.cookingTime = json.has("cookingtime") ? json.get("cookingtime").getAsInt() : 200;
        if (json.has("recipe_book_tab") && json.get("recipe_book_tab").isJsonPrimitive()) {
            draft.choices.put("recipe_book_tab", json.get("recipe_book_tab").getAsString());
        }
        return draft;
    }

    private static RecipeDraft fromUncrafting(JsonObject json) {
        RecipeDraft draft = start(RecipeDraft.Kind.UNCRAFTING);
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("input")));
        draft.numbers.put("cost", json.has("cost") ? json.get("cost").getAsInt() : 0);
        draft.numbers.put("input_count",
                json.has("input_count") ? Math.max(1, json.get("input_count").getAsInt()) : 1);
        return readGrid(json, draft) ? draft : null;
    }

    private static RecipeDraft fromDrying(JsonObject json) {
        RecipeDraft draft = start(RecipeDraft.Kind.DRYING);
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("input")));
        Stack result = readStack(json.get("result"));
        draft.result = result.value();
        draft.resultCount = result.count();
        draft.numbers.put("filter_time", json.has("filter_time") ? json.get("filter_time").getAsInt() : 40);
        return draft;
    }

    private static RecipeDraft fromScepterRepair(JsonObject json) {
        RecipeDraft draft = start(RecipeDraft.Kind.SCEPTER_REPAIR);
        JsonArray ingredients = array(json, "repair_ingredients");
        if (ingredients.size() > MAX_REPAIR_INGREDIENTS) {
            return null;
        }
        for (JsonElement element : ingredients) {
            draft.inputs.add(IngredientValue.fromIngredientJson(element));
        }
        if (json.has("scepter") && json.get("scepter").isJsonPrimitive()) {
            ResourceLocation scepter = ResourceLocation.tryParse(json.get("scepter").getAsString());
            if (scepter != null) {
                draft.result = IngredientValue.item(scepter);
            }
        }
        draft.numbers.put("durability", json.has("durability") ? json.get("durability").getAsInt() : 9);
        return draft;
    }

    private static RecipeDraft fromBrewing(JsonObject json) {
        RecipeDraft draft = start(RecipeDraft.Kind.BREWING_STAND);
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("bottle")));
        draft.inputs.add(IngredientValue.fromIngredientJson(json.get("input")));
        Stack result = readStack(json.get("result"));
        draft.result = result.value();
        draft.resultCount = result.count();
        return draft;
    }

    // ------------------------------------------------------------------ shared pieces

    private static RecipeDraft start(RecipeDraft.Kind kind) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = kind;
        draft.inputs.clear();
        return draft;
    }

    private static JsonObject base(RecipeDraft draft, String type) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        if (draft.group != null && !draft.group.isBlank()) {
            json.addProperty("group", draft.group);
        }
        return json;
    }

    private static JsonArray array(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
    }

    private static int number(RecipeDraft draft, String key, int fallback) {
        Integer value = draft.numbers.get(key);
        return value != null ? value : fallback;
    }

    /** The recipe book tab, always one Farmer's Delight knows; anything else would fail to load. */
    private static String tab(RecipeDraft draft) {
        String chosen = draft.choices.get("recipe_book_tab");
        return chosen != null && List.of("meals", "drinks", "misc").contains(chosen) ? chosen : "misc";
    }

    /**
     * The grid on the result side of an uncrafting recipe, written as the {@code pattern} and {@code key}
     * of an ordinary shaped recipe — which is exactly what Twilight Forest reads it with.
     */
    private static void writeGrid(JsonObject json, RecipeDraft draft) {
        int minRow = draft.height, maxRow = -1, minCol = draft.width, maxCol = -1;
        for (int row = 0; row < draft.height; row++) {
            for (int col = 0; col < draft.width; col++) {
                if (!draft.outputCell(row * draft.width + col).isEmpty()) {
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
                    IngredientValue value = draft.outputCell(row * draft.width + col);
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

    /** Reads that grid back; false when the pattern is larger than the editor can show. */
    private static boolean readGrid(JsonObject json, RecipeDraft draft) {
        JsonArray pattern = json.has("pattern") && json.get("pattern").isJsonArray()
                ? json.getAsJsonArray("pattern") : new JsonArray();
        JsonObject key = json.has("key") && json.get("key").isJsonObject()
                ? json.getAsJsonObject("key") : new JsonObject();
        int height = Math.max(1, pattern.size());
        int width = 1;
        for (JsonElement row : pattern) {
            width = Math.max(width, row.getAsString().length());
        }
        if (width > 3 || height > 3) {
            return false; // a crafting grid is three by three, and so is the one the editor draws
        }
        draft.width = 3;
        draft.height = 3;
        draft.outputGrid.clear();
        for (int i = 0; i < 9; i++) {
            draft.outputGrid.add(IngredientValue.empty());
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
                    draft.setOutputCell(row * 3 + col, IngredientValue.fromIngredientJson(key.get(symbolKey)));
                }
            }
        }
        return true;
    }

    private static final String KEY_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** An item and how many of it, as read out of a result field. */
    private record Stack(IngredientValue value, int count) {
    }

    /**
     * A result item stack the way this version writes one: {@code {"id": "minecraft:x", "count": n}}.
     *
     * <p>{@code always} is whether the count is written when it is one. Every codec here defaults it to
     * one, so both spellings load the same recipe - but Farmer's Delight and Twilight Forest write it
     * out and Cobblemon does not, and matching each of them is what makes opening a recipe and saving
     * it again leave the file exactly as it was.
     */
    private static JsonObject stack(IngredientValue value, int count, boolean always) {
        JsonObject json = new JsonObject();
        json.addProperty("id", value.isEmpty() ? "minecraft:air" : value.id().toString());
        if (always || count > 1) {
            json.addProperty("count", count);
        }
        return json;
    }

    /** The same shape read back, tolerating the older {@code item} spelling and a bare id string. */
    private static Stack readStack(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new Stack(IngredientValue.empty(), 1);
        }
        if (element.isJsonPrimitive()) {
            ResourceLocation id = ResourceLocation.tryParse(element.getAsString());
            return new Stack(id == null ? IngredientValue.empty() : IngredientValue.item(id), 1);
        }
        if (!element.isJsonObject()) {
            return new Stack(IngredientValue.empty(), 1);
        }
        JsonObject object = element.getAsJsonObject();
        String idKey = object.has("id") ? "id" : (object.has("item") ? "item" : null);
        IngredientValue value = IngredientValue.empty();
        if (idKey != null && object.get(idKey).isJsonPrimitive()) {
            ResourceLocation id = ResourceLocation.tryParse(object.get(idKey).getAsString());
            if (id != null) {
                value = IngredientValue.item(id);
            }
        }
        return new Stack(value, object.has("count") ? Math.max(1, object.get("count").getAsInt()) : 1);
    }
}
