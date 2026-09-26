package org.mateof24.sce.core.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import org.mateof24.sce.SimpleCraftEditor;
import org.mateof24.sce.core.recipe.InheritingCraftingRecipe;
import net.minecraft.resources.Identifier;
import org.mateof24.sce.core.edit.CreateRecipeCompiler;
import org.mateof24.sce.core.edit.IngredientValue;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings stored recipes into the shape the game of this version reads.
 *
 * <p>A recipe this mod stores is the JSON the game read when it was written, and the game has changed
 * that JSON twice: an ingredient was {@code {"item": "x"}} up to 1.21.1 and is a bare {@code "x"} from
 * 1.21.2 on, and a result was {@code {"item": ..}} on 1.20.1 and is {@code {"id": ..}} after it. A recipe
 * carried to another version therefore did not merely look wrong - it failed to parse, so it stopped
 * crafting, disappeared from every recipe viewer and lost its icon in the manager. That is the one thing
 * an editor must never do to somebody's work.
 *
 * <p><b>By shape, not by a version number.</b> There is no ordering to follow: an upgrade from 1.20.1
 * straight to 26.3 skips four versions, and a player who goes back to test does the same trip the other
 * way. A stamp would only say what wrote the file; what has to be answered is whether what is in it can
 * be read here, and that is a question about the JSON itself. So every recipe is looked at on every
 * start, and the file is only rewritten when something actually had to change.
 *
 * <p><b>Nothing else is touched.</b> Not the recipe's type, not a field this mod does not recognise, not
 * a result carrying components. Reading the recipe into the editor and writing it back would have been
 * the shorter road, but the editor's writer turns a crafting recipe into this mod's own inheriting type
 * and attaches its settings to it - which is right when an author presses save and quite wrong for a
 * file being carried across a version boundary. So only the two things the game changed are rewritten,
 * where a key says that is what they are.
 */
public final class RecipeMigration {
    /** The keys whose value is an ingredient, in the types this mod knows and the ones it does not. */
    private static final Set<String> INGREDIENT_KEYS = Set.of(
            "ingredient", "ingredients", "base", "template", "addition",
            "input", "tool", "bottle", "repair_ingredients");

    /**
     * The types whose result shape is the game's own, and so is this mod's business to rewrite.
     *
     * <p>Another mod's type is left alone on the result side. Its shape is that mod's decision, it can
     * differ between that mod's own builds, and guessing at it would be how a recipe gets damaged rather
     * than carried.
     */
    private static final Set<String> VANILLA_RESULTS = Set.of(
            "minecraft:crafting_shaped", "minecraft:crafting_shapeless",
            "minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
            "minecraft:campfire_cooking", "minecraft:stonecutting", "minecraft:smithing_transform",
            "sce:crafting_shaped_inherit", "sce:crafting_shapeless_inherit");

    private RecipeMigration() {
    }

    /**
     * Rewrites every stored recipe that is not already in this version's shape, and says how many.
     *
     * <p>Only the generated ones: a disabled entry is remembered by its id, and the JSON beside it is
     * never read back.
     */
    public static int apply(RecipeState state, int fileVersion) {
        int changed = 0;
        for (Map.Entry<Identifier, JsonObject> entry : state.generated().entrySet()) {
            JsonObject migrated = migrate(entry.getKey(), entry.getValue(), fileVersion);
            if (migrated != null) {
                entry.setValue(migrated);
                changed++;
            }
        }
        // And the snapshot kept beside a disabled recipe. It is read back - it is where the manager gets
        // the icon and the name of a recipe that is no longer in the game to be asked.
        for (Map.Entry<Identifier, JsonObject> entry : state.disabled().entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            JsonObject migrated = migrate(entry.getKey(), entry.getValue(), fileVersion);
            if (migrated != null) {
                entry.setValue(migrated);
                changed++;
            }
        }
        return changed;
    }

    /** The recipe as this version would have written it, or null when it already is one. */
    private static JsonObject migrate(Identifier id, JsonObject json, int fileVersion) {
        JsonObject out = json.deepCopy();
        walk(out);
        if (VANILLA_RESULTS.contains(typeOf(json))) {
            normaliseResult(out);
        } else {
            normaliseModStacks(out);
            renameKnownKeys(out);
        }
        fixDataStacks(out, fileVersion);
        return out.equals(json) ? null : out;
    }

    /**
     * What this game calls its save format. A recipe file records it, so the next version knows exactly
     * how far the stacks inside it have to be brought forward.
     */
    public static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /** 1.20.1, the oldest this mod supports and the last that wrote a stack as {@code Count} and {@code tag}. */
    private static final int V_1_20_1 = 3465;
    /** 1.21.1, the oldest that wrote one as {@code count} and {@code components}. */
    private static final int V_1_21_1 = 3955;

    /**
     * The stacks in this mod's own data block, brought forward.
     *
     * <p>These are the stacks a recipe insists on finding in the grid, and the one whose data it copies
     * onto the result. They are stored as text because the file is the same file on every version this
     * mod supports - and the text of a stack is not. Read here without fixing, an enchanted sword comes
     * back as a plain one: the fields the old version wrote are simply not fields any more, so they are
     * skipped in silence and the requirement turns into something the author never asked for.
     *
     * <p>{@code DataFixerUpper} is what the game uses to carry a saved item across the same boundary, so
     * it is what is used here. Going backwards it cannot help - there are no fixers in that direction -
     * so a stack from a newer version is left exactly as it is and said out loud, rather than rewritten
     * into a guess.
     */
    private static boolean fixDataStacks(JsonObject json, int fileVersion) {
        if (!json.has(InheritingCraftingRecipe.DATA_KEY)
                || !json.get(InheritingCraftingRecipe.DATA_KEY).isJsonObject()) {
            return false;
        }
        JsonObject data = json.getAsJsonObject(InheritingCraftingRecipe.DATA_KEY);
        boolean changed = false;
        if (data.has("require") && data.get("require").isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement element : data.getAsJsonArray("require")) {
                String was = element.isJsonPrimitive() ? element.getAsString() : "";
                String now = fixStack(was, fileVersion);
                changed |= !now.equals(was);
                out.add(now);
            }
            data.add("require", out);
        }
        if (data.has("result") && data.get("result").isJsonPrimitive()) {
            String was = data.get("result").getAsString();
            String now = fixStack(was, fileVersion);
            if (!now.equals(was)) {
                data.addProperty("result", now);
                changed = true;
            }
        }
        return changed;
    }

    private static String fixStack(String snbt, int fileVersion) {
        if (snbt == null || snbt.isEmpty()) {
            return snbt;
        }
        int from = fileVersion > 0 ? fileVersion : guessVersion(snbt);
        if (from >= currentDataVersion()) {
            if (from > currentDataVersion()) {
                SimpleCraftEditor.LOGGER.warn("A stored item stack was written by a newer version of the "
                        + "game and cannot be brought back to this one; leaving it as it is: {}", snbt);
            }
            return snbt;
        }
        try {
            Dynamic<Tag> before = new Dynamic<>(NbtOps.INSTANCE, TagParser.parseCompoundFully(snbt));
            return DataFixers.getDataFixer()
                    .update(References.ITEM_STACK, before, from, currentDataVersion())
                    .getValue().toString();
        } catch (Exception e) {
            SimpleCraftEditor.LOGGER.warn("Could not bring a stored item stack forward: {}", snbt);
            return snbt;
        }
    }

    /**
     * Which version wrote a stack, for a file from before this mod recorded it. The stack says so itself:
     * {@code Count} and {@code tag} are the spelling of 1.20.1 and of nothing after it.
     */
    private static int guessVersion(String snbt) {
        return snbt.contains("Count:") || snbt.contains("tag:") ? V_1_20_1 : V_1_21_1;
    }


    /** Every ingredient in the file, wherever its type put it. */
    private static void walk(JsonElement element) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                walk(child);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (String key : List.copyOf(object.keySet())) {
            JsonElement value = object.get(key);
            if (INGREDIENT_KEYS.contains(key)) {
                object.add(key, IngredientValue.normalise(value));
            } else if (key.equals("key") && value.isJsonObject()) {
                // A shaped recipe's key is a letter per ingredient rather than an ingredient itself.
                JsonObject letters = value.getAsJsonObject();
                for (String letter : List.copyOf(letters.keySet())) {
                    letters.add(letter, IngredientValue.normalise(letters.get(letter)));
                }
            } else {
                walk(value);
            }
        }
    }

    private static String typeOf(JsonObject json) {
        return json.has("type") && json.get("type").isJsonPrimitive() ? json.get("type").getAsString() : "";
    }

    /**
     * The result, written the way this version writes it: an item stack keyed by {@code id}.
     *
     * <p>1.20.1 wrote a crafting result as {@code {"item": ..}} and a cooking or stonecutting result as
     * a bare id, with stonecutting's count beside the result rather than inside it. So the count is
     * picked up from wherever that version left it and the key it left behind is taken away.
     */
    private static void normaliseResult(JsonObject json) {
        JsonElement result = json.get("result");
        if (result == null) {
            return;
        }
        String id = resultId(result);
        if (id == null) {
            return;
        }
        int count = resultCount(result);
        if (result.isJsonPrimitive() && json.has("count") && json.get("count").isJsonPrimitive()) {
            count = Math.max(1, json.get("count").getAsInt());
            json.remove("count");
        }
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        if (count > 1) {
            out.addProperty("count", count);
        }
        carryRest(result, out);
        json.add("result", out);
    }

    /** What the result names and how many of it, out of any of the shapes these versions have written. */
    private static String resultId(JsonElement result) {
        if (result.isJsonPrimitive()) {
            return result.getAsString();
        }
        if (!result.isJsonObject()) {
            return null;
        }
        JsonObject object = result.getAsJsonObject();
        String key = object.has("id") ? "id" : (object.has("item") ? "item" : null);
        return key != null && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    private static int resultCount(JsonElement result) {
        if (result.isJsonObject()) {
            JsonObject object = result.getAsJsonObject();
            if (object.has("count") && object.get("count").isJsonPrimitive()) {
                return Math.max(1, object.get("count").getAsInt());
            }
        }
        return 1;
    }

    /** How this version spells the item inside a stack. */
    private static final String STACK_ID = "id";

    /**
     * Whether a cutting board's drop holds its stack under {@code item} rather than flattened into it.
     */
    private static final boolean DROP_NESTS_STACK = true;

    /**
     * The fields of a type this mod does not own that hold the game's own item stack.
     *
     * <p>A modded recipe's shape is that mod's business and is not rewritten here - but these fields are
     * not that mod's shape at all. The mod hands the field straight to the game's item-stack codec,
     * which spelled the item {@code item} on 1.20.1 and spells it {@code id} after it, so a recipe
     * written on one version stops parsing on the other exactly as a vanilla one would. Each entry was
     * read out of the mod's own class before it was put here:
     *
     * <ul>
     *   <li>{@code create:mechanical_crafting} is vanilla's shaped recipe serializer with one flag
     *       added, result and all.</li>
     *   <li>{@code create:sequenced_assembly} names the item carried through its steps as a stack. Its
     *       outputs, and those of every Create machine, are Create's own {@code ProcessingOutput}, which
     *       reads either spelling - they are written in this version's anyway, because that costs
     *       nothing and is what a player going back to an older version needs.</li>
     *   <li>Farmer's Delight names a cooking pot's dish and the bowl it is served in as stacks, and its
     *       cutting board changed by more than a spelling.</li>
     * </ul>
     */
    private static void normaliseModStacks(JsonObject json) {
        String type = typeOf(json);
        if (type.startsWith("create:")) {
            normaliseFluids(json);
        }
        switch (type) {
            case "farmersdelight:cutting" -> drops(json);
            case "farmersdelight:cooking" -> {
                stackField(json, "result");
                stackField(json, "container");
            }
            case "create:mechanical_crafting" -> stackField(json, "result");
            case "create:sequenced_assembly" -> {
                // Whichever way round the file that is being read spelt it: the rename pass has not run
                // yet, and this has to find the stack before the field it sits in is renamed.
                stackField(json, "transitionalItem");
                stackField(json, "transitional_item");
                stackList(json, "results");
                // Each step is a whole processing recipe of its own, outputs and all.
                if (json.has("sequence") && json.get("sequence").isJsonArray()) {
                    for (JsonElement step : json.getAsJsonArray("sequence")) {
                        if (step.isJsonObject()) {
                            stackList(step.getAsJsonObject(), "results");
                        }
                    }
                }
            }
            default -> {
                if (type.startsWith("create:")) {
                    stackList(json, "results");
                }
            }
        }
    }

    /**
     * Create's fluid entries, in the shape this version reads.
     *
     * <p>The one thing here that changed shape rather than spelling, so neither the key pass nor a
     * comparison with the version's own copy of the recipe can carry it. Written by the same code the
     * editor writes it with, one entry at a time.
     */
    private static void normaliseFluids(JsonObject json) {
        for (String key : List.copyOf(json.keySet())) {
            JsonElement value = json.get(key);
            boolean result = key.equals("results") || key.equals("result");
            boolean ingredient = key.equals("ingredients") || key.equals("ingredient");
            if (key.equals("sequence") && value.isJsonArray()) {
                // Each step of a sequenced assembly is a whole processing recipe, fluids and all.
                for (JsonElement step : value.getAsJsonArray()) {
                    if (step.isJsonObject()) {
                        normaliseFluids(step.getAsJsonObject());
                    }
                }
            } else if (!result && !ingredient) {
                continue;
            } else if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                for (int i = 0; i < array.size(); i++) {
                    JsonObject now = fluidEntry(array.get(i), result);
                    if (now != null) {
                        array.set(i, now);
                    }
                }
            } else {
                JsonObject now = fluidEntry(value, result);
                if (now != null) {
                    json.add(key, now);
                }
            }
        }
    }

    private static JsonObject fluidEntry(JsonElement element, boolean result) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject entry = element.getAsJsonObject();
        return result ? CreateRecipeCompiler.normaliseFluidResult(entry)
                : CreateRecipeCompiler.normaliseFluidIngredient(entry);
    }

    /** One field holding one stack. */
    private static void stackField(JsonObject json, String key) {
        JsonObject stack = asStack(json.get(key));
        if (stack != null) {
            json.add(key, stack);
        }
    }

    /** A list of stacks, the way a machine's outputs are written. */
    private static void stackList(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return;
        }
        JsonArray out = new JsonArray();
        for (JsonElement element : json.getAsJsonArray(key)) {
            JsonObject stack = asStack(element);
            out.add(stack == null ? element : stack);
        }
        json.add(key, out);
    }

    /**
     * A stack as this version spells one, or null when the value is not a stack to rewrite.
     *
     * <p>The count is written only where the file already had one: every codec here reads a missing
     * count as one, and adding it everywhere would rewrite files that had nothing wrong with them.
     */
    private static JsonObject asStack(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        String id = resultId(value);
        if (id == null) {
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty(STACK_ID, id);
        if (value.getAsJsonObject().has("count")) {
            out.addProperty("count", resultCount(value));
        }
        carryRest(value, out);
        return out;
    }

    /** A cutting board's drops, in the shape this version reads them. */
    private static void drops(JsonObject json) {
        if (!json.has("result") || !json.get("result").isJsonArray()) {
            return;
        }
        JsonArray out = new JsonArray();
        for (JsonElement element : json.getAsJsonArray("result")) {
            out.add(drop(element));
        }
        json.add("result", out);
    }

    /**
     * One drop: the stack, and the chance of getting it.
     *
     * <p>Read out of either shape and written in this version's. The chance stays beside the stack in
     * both of them; what moves is everything the stack itself says.
     */
    private static JsonElement drop(JsonElement element) {
        if (!element.isJsonObject()) {
            return element;
        }
        JsonObject entry = element.getAsJsonObject();
        JsonElement item = entry.get("item");
        JsonObject stack = new JsonObject();
        JsonObject beside = new JsonObject();
        if (item != null && item.isJsonObject()) {
            String id = resultId(item);
            if (id == null) {
                return entry;
            }
            stack.addProperty(STACK_ID, id);
            if (item.getAsJsonObject().has("count")) {
                stack.addProperty("count", resultCount(item));
            }
            carryRest(item, stack);
            for (Map.Entry<String, JsonElement> extra : entry.entrySet()) {
                if (!extra.getKey().equals("item")) {
                    beside.add(extra.getKey(), extra.getValue());
                }
            }
        } else if (item != null && item.isJsonPrimitive()) {
            stack.addProperty(STACK_ID, item.getAsString());
            if (entry.has("count")) {
                stack.addProperty("count", resultCount(entry));
            }
            for (Map.Entry<String, JsonElement> extra : entry.entrySet()) {
                String key = extra.getKey();
                if (key.equals("item") || key.equals("count")) {
                    continue;
                }
                // The chance was never part of the stack; everything else beside it was.
                if (key.equals("chance")) {
                    beside.add(key, extra.getValue());
                } else {
                    stack.add(key, extra.getValue());
                }
            }
        } else {
            return entry;
        }
        JsonObject out = new JsonObject();
        if (DROP_NESTS_STACK) {
            out.add("item", stack);
        } else {
            for (Map.Entry<String, JsonElement> field : stack.entrySet()) {
                out.add(field.getKey(), field.getValue());
            }
        }
        for (Map.Entry<String, JsonElement> extra : beside.entrySet()) {
            out.add(extra.getKey(), extra.getValue());
        }
        return out;
    }

    // ------------------------------------------------------------------ asked of the running game

    /** The folders a tag file can live in, across every version this mod supports. */
    private static final List<String> TAG_FOLDERS =
            List.of("tags/item/", "tags/items/", "tags/fluid/", "tags/fluids/");

    /** The folders this version's own recipe files live in: {@code recipe} now, {@code recipes} before. */
    private static final List<String> RECIPE_FOLDERS = List.of("recipe/", "recipes/");

    /**
     * Brings every stored recipe into line with the game that is running, and says how many changed.
     *
     * <p>Two things cross a version boundary badly and neither is a matter of shape, so neither could be
     * fixed by the pass that runs when the file is read:
     *
     * <ul>
     *   <li><b>Field names.</b> A mod that moved from hand-written JSON to codecs renamed its fields on
     *       the way - Create's {@code acceptMirrored} is {@code accept_mirrored} from 1.21.1, and its
     *       codec requires it, so the recipe does not parse at all.</li>
     *   <li><b>Tag ids.</b> The whole {@code forge:} namespace became {@code c:} and several tags were
     *       renamed on the way ({@code forge:stone} is {@code c:stones}). This one is worse: a recipe
     *       whose tag is gone still loads, the tag simply matches nothing, so there is no error anywhere
     *       and the recipe has quietly stopped working.</li>
     * </ul>
     *
     * <p>Neither is guessed at. This version's own packs are asked, because they hold the same recipe
     * under the same id, written the way this version writes it - so a field ours spells one way and
     * theirs spells the other is a rename, full stop, whichever direction the player is travelling and
     * however many versions they skipped. A tag is only replaced when it is really gone from this
     * version's data and the replacement is really there; one that exists is left alone even when it is
     * not what the mod ships, because that was the author's own choice.
     */
    public static int alignToThisVersion(RecipeState state, ResourceManager resources) {
        Map<String, String> renames = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        int changed = 0;
        for (Map.Entry<Identifier, JsonObject> entry : state.generated().entrySet()) {
            if (alignOne(entry.getKey(), entry.getValue(), resources, renames, unresolved)) {
                changed++;
            }
        }
        for (Map.Entry<Identifier, JsonObject> entry : state.disabled().entrySet()) {
            if (entry.getValue() != null
                    && alignOne(entry.getKey(), entry.getValue(), resources, renames, unresolved)) {
                changed++;
            }
        }
        renames.forEach((was, now) -> SimpleCraftEditor.LOGGER.info(
                "The tag {} is not in this version of the game; the stored recipes now say {}", was, now));
        for (String tag : unresolved) {
            SimpleCraftEditor.LOGGER.warn("The tag {} is not in this version of the game and nothing here "
                    + "could say what replaced it, so the recipes that use it are left exactly as they "
                    + "are - they will match nothing until that tag is edited", tag);
        }
        return changed;
    }

    /** One recipe. True when anything about it had to change. */
    private static boolean alignOne(Identifier id, JsonObject json, ResourceManager resources,
                                    Map<String, String> renames, Set<String> unresolved) {
        JsonObject base = baseRecipe(resources, id);
        boolean changed = base != null && alignTo(json, base);
        return retagOne(json, base, resources, renames, unresolved) || changed;
    }

    /**
     * Spells this recipe's fields the way this version's own copy of it spells them.
     *
     * <p>Only a key that is missing here and present there, and only when the two are the same word
     * written differently - {@code acceptMirrored} and {@code accept_mirrored}, {@code transitionalItem}
     * and {@code transitional_item}. A field the author added, or one this version genuinely dropped, is
     * left alone: what is compared is spelling, not content, and a value is never taken from the copy.
     */
    private static boolean alignTo(JsonObject json, JsonObject base) {
        boolean changed = false;
        Map<String, String> theirs = new LinkedHashMap<>();
        for (String key : base.keySet()) {
            theirs.put(sameWord(key), key);
        }
        for (String key : List.copyOf(json.keySet())) {
            String now = theirs.get(sameWord(key));
            if (now != null && !now.equals(key) && !json.has(now)) {
                JsonElement value = json.get(key);
                json.remove(key);
                json.add(now, value);
                changed = true;
            }
        }
        // And the same, one level in, for the objects both of them have under the same name: a step of a
        // sequence, a result, an ingredient.
        for (String key : List.copyOf(json.keySet())) {
            JsonElement mine = json.get(key);
            JsonElement other = base.get(key);
            if (mine != null && other != null && mine.isJsonObject() && other.isJsonObject()) {
                changed |= alignTo(mine.getAsJsonObject(), other.getAsJsonObject());
            } else if (mine != null && other != null && mine.isJsonArray() && other.isJsonArray()) {
                JsonArray ours = mine.getAsJsonArray();
                JsonArray base2 = other.getAsJsonArray();
                for (int i = 0; i < ours.size() && i < base2.size(); i++) {
                    if (ours.get(i).isJsonObject() && base2.get(i).isJsonObject()) {
                        changed |= alignTo(ours.get(i).getAsJsonObject(), base2.get(i).getAsJsonObject());
                    }
                }
            }
        }
        return changed;
    }

    /** A field name with the punctuation and the capitals taken out, which is what two spellings share. */
    private static String sameWord(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c != '_' && c != '-') {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** One recipe's tags. True when any of them had to be rewritten. */
    private static boolean retagOne(JsonObject json, JsonObject base, ResourceManager resources,
                                    Map<String, String> renames, Set<String> unresolved) {
        List<String> tags = new ArrayList<>();
        collectTags(json, tags);
        if (tags.isEmpty()) {
            return false;
        }
        Map<String, String> found = new LinkedHashMap<>();
        for (String tag : tags) {
            if (renames.containsKey(tag)) {
                found.put(tag, renames.get(tag));
                continue;
            }
            if (unresolved.contains(tag) || tagExists(resources, tag)) {
                continue;
            }
            String now = fromBase(base, tag, resources);
            if (now == null) {
                now = rewritten(resources, tag);
            }
            if (now == null) {
                unresolved.add(tag);
                continue;
            }
            renames.put(tag, now);
            found.put(tag, now);
        }
        return !found.isEmpty() && replaceTags(json, found);
    }

    /**
     * Every tag a recipe names, in each of the ways a version writes one: {@code {"tag": "x"}},
     * {@code {"fluidTag": "x"}}, and the bare {@code "#x"} of 1.21.2 and after.
     */
    private static void collectTags(JsonElement element, List<String> into) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectTags(child, into);
            }
            return;
        }
        if (element.isJsonPrimitive()) {
            String hash = hashedTag(element);
            if (hash != null && !into.contains(hash)) {
                into.add(hash);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (isTagKey(entry.getKey()) && isString(value)) {
                String tag = value.getAsString();
                if (!into.contains(tag)) {
                    into.add(tag);
                }
                continue;
            }
            collectTags(value, into);
        }
    }

    /** The same walk, writing the replacements back. True when anything changed. */
    private static boolean replaceTags(JsonElement element, Map<String, String> renames) {
        boolean changed = false;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement child = array.get(i);
                String hash = hashedTag(child);
                if (hash != null && renames.containsKey(hash)) {
                    array.set(i, new JsonPrimitive("#" + renames.get(hash)));
                    changed = true;
                } else {
                    changed |= replaceTags(child, renames);
                }
            }
            return changed;
        }
        if (!element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        for (String key : List.copyOf(object.keySet())) {
            JsonElement value = object.get(key);
            if (isTagKey(key) && isString(value) && renames.containsKey(value.getAsString())) {
                object.addProperty(key, renames.get(value.getAsString()));
                changed = true;
                continue;
            }
            String hash = hashedTag(value);
            if (hash != null && renames.containsKey(hash)) {
                object.addProperty(key, "#" + renames.get(hash));
                changed = true;
                continue;
            }
            changed |= replaceTags(value, renames);
        }
        return changed;
    }

    private static boolean isTagKey(String key) {
        return key.equals("tag") || key.equals("fluidTag") || key.equals("fluid_tag");
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    /** The tag a bare {@code "#namespace:path"} names, or null when the value is not one. */
    private static String hashedTag(JsonElement element) {
        if (!isString(element)) {
            return null;
        }
        String value = element.getAsString();
        return value.length() > 1 && value.charAt(0) == '#' ? value.substring(1) : null;
    }

    /** Whether a tag with this id is declared anywhere in the data this game is running. */
    private static boolean tagExists(ResourceManager resources, String tag) {
        for (String folder : TAG_FOLDERS) {
            Identifier file = fileId(tag, folder);
            if (file != null && !resources.getResourceStack(file).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** This version's own copy of the recipe with that id, as its pack ships it; null when it has none. */
    private static JsonObject baseRecipe(ResourceManager resources, Identifier id) {
        for (String folder : RECIPE_FOLDERS) {
            Identifier file = fileId(id.getNamespace() + ":" + id.getPath(), folder);
            if (file == null) {
                continue;
            }
            for (Resource resource : resources.getResourceStack(file)) {
                try (Reader reader = resource.openAsReader()) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        return parsed.getAsJsonObject();
                    }
                } catch (Exception e) {
                    // A pack whose copy will not read is not an answer; the next one might be.
                }
            }
        }
        return null;
    }

    /**
     * The tag this version's own copy of the same recipe names where the stored one named a tag that is
     * gone - which is the only source here that cannot be wrong about a rename.
     */
    private static String fromBase(JsonObject base, String tag, ResourceManager resources) {
        if (base == null) {
            return null;
        }
        List<String> theirs = new ArrayList<>();
        collectTags(base, theirs);
        String wanted = lastSegment(tag);
        for (String candidate : theirs) {
            if (candidate.equals(tag) || !tagExists(resources, candidate)) {
                continue;
            }
            String segment = lastSegment(candidate);
            if (pathOf(candidate).equals(pathOf(tag)) || segment.equals(wanted)
                    || segment.equals(plural(wanted)) || wanted.equals(plural(segment))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The obvious rewrites of a tag id, for a recipe this version has no copy of.
     *
     * <p>The namespace first, because that is the change every one of these went through at once, then
     * the first path segment in the plural and in the singular - {@code forge:stone} is {@code c:stones}
     * and the category is what was pluralised, not the metal. Each is tried against the data and only a
     * tag that is really there is returned.
     */
    private static String rewritten(ResourceManager resources, String tag) {
        String namespace = namespaceOf(tag);
        String path = pathOf(tag);
        List<String> namespaces = new ArrayList<>();
        if (namespace.equals("forge")) {
            namespaces.add("c");
        } else if (namespace.equals("c")) {
            namespaces.add("forge");
        }
        namespaces.add(namespace);
        int slash = path.indexOf('/');
        String head = slash < 0 ? path : path.substring(0, slash);
        String tail = slash < 0 ? "" : path.substring(slash);
        List<String> paths = new ArrayList<>(List.of(path, plural(head) + tail, singular(head) + tail));
        for (String candidateNamespace : namespaces) {
            for (String candidatePath : paths) {
                String candidate = candidateNamespace + ":" + candidatePath;
                if (!candidate.equals(tag) && tagExists(resources, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String namespaceOf(String tag) {
        int colon = tag.indexOf(':');
        return colon < 0 ? "minecraft" : tag.substring(0, colon);
    }

    private static String pathOf(String tag) {
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(colon + 1);
    }

    private static String lastSegment(String tag) {
        String path = pathOf(tag);
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String plural(String word) {
        return word.endsWith("s") ? word : word + "s";
    }

    private static String singular(String word) {
        return word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }

    /** A tag or recipe id turned into the file that would hold it, or null when it is not a usable id. */
    private static Identifier fileId(String id, String folder) {
        try {
            return Identifier.fromNamespaceAndPath(namespaceOf(id), folder + pathOf(id) + ".json");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The fields a mod renamed when it moved from hand-written JSON to codecs, spelt as this version
     * reads them.
     *
     * <p>This is only what this mod already had to know: every one of these appears in both spellings in
     * {@code RecipeCompiler.MODELLED_KEYS}, because the editor has always had to read a recipe written
     * by either version. A recipe the game ships is handled better than this, by reading the version's
     * own copy of it - see {@link #alignTo}. This is for the one case that has no copy to read: a recipe
     * the player wrote themselves.
     */
    private static final Map<String, String> RENAMED_KEYS = Map.of(
            "acceptMirrored", "accept_mirrored",
            "transitionalItem", "transitional_item",
            "processingTime", "processing_time",
            "heatRequirement", "heat_requirement",
            "keepHeldItem", "keep_held_item");

    /** The renames above, applied wherever the old spelling turns up. */
    private static void renameKnownKeys(JsonElement element) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                renameKnownKeys(child);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (String key : List.copyOf(object.keySet())) {
            JsonElement value = object.get(key);
            String now = RENAMED_KEYS.get(key);
            if (now != null && !object.has(now)) {
                object.remove(key);
                object.add(now, value);
            }
            renameKnownKeys(value);
        }
    }

    /** Components, a custom name, anything else an author put beside the id: not ours to drop. */
    private static void carryRest(JsonElement result, JsonObject into) {
        if (!result.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : result.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            if (!key.equals("id") && !key.equals("item") && !key.equals("count")) {
                into.add(key, entry.getValue());
            }
        }
    }
}
