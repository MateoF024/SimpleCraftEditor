package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.architectury.hooks.fluid.FluidStackHooks;
import net.minecraft.resources.Identifier;

/**
 * A single editable ingredient option: an item, an item tag, a fluid, or empty. Kept deliberately simple
 * (one option per slot) for the vanilla editors; multi-option ingredients collapse to their first option
 * on load.
 *
 * <p>Fluids only appear in Create recipes, which take them as a quantity rather than as a bucket item.
 * {@link #amount()} carries that quantity in millibuckets (1 bucket = 1000 mB) and is meaningless for the
 * other kinds. The fluid JSON shape differs between Create versions, so it is written by
 * {@link CreateRecipeCompiler} rather than here.
 */
public final class IngredientValue {
    public enum Kind {EMPTY, ITEM, TAG, FLUID, FLUID_TAG}

    /** A bucket in millibuckets — the unit this editor counts fluids in, everywhere except the JSON. */
    public static final int BUCKET = 1000;

    /**
     * Converts a millibucket amount into the unit the recipe file is written in. Forge and NeoForge count
     * millibuckets, Fabric counts droplets at 81000 to the bucket, and Create writes whichever its platform
     * uses — the same recipe ships as {@code 250} on Forge and {@code 27000} on Fabric. Architectury already
     * knows the platform's bucket, so the ratio comes from there rather than from a hardcoded loader check.
     */
    public static int toPlatformAmount(int millibuckets) {
        return scale(millibuckets, FluidStackHooks.bucketAmount(), BUCKET);
    }

    /** The inverse, for reading an amount back out of a recipe file. */
    public static int fromPlatformAmount(long platformAmount) {
        return scale(platformAmount, BUCKET, FluidStackHooks.bucketAmount());
    }

    /** Scales in long arithmetic: droplet amounts overflow an int well before the editor's limits do. */
    private static int scale(long amount, long numerator, long denominator) {
        if (denominator <= 0) {
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, amount));
        }
        long scaled = amount * numerator / denominator;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, scaled));
    }

    private static final IngredientValue EMPTY = new IngredientValue(Kind.EMPTY, null, 0);

    private final Kind kind;
    private final Identifier id;
    private final int amount;

    private IngredientValue(Kind kind, Identifier id, int amount) {
        this.kind = kind;
        this.id = id;
        this.amount = amount;
    }

    public static IngredientValue empty() {
        return EMPTY;
    }

    public static IngredientValue item(Identifier item) {
        return new IngredientValue(Kind.ITEM, item, 0);
    }

    public static IngredientValue tag(Identifier tag) {
        return new IngredientValue(Kind.TAG, tag, 0);
    }

    /** A fluid ingredient or result of {@code amount} millibuckets. */
    public static IngredientValue fluid(Identifier fluid, int amount) {
        return new IngredientValue(Kind.FLUID, fluid, Math.max(1, amount));
    }

    /**
     * A fluid ingredient matching any fluid in {@code tag}, of {@code amount} millibuckets. Only valid as an
     * ingredient — a result has to name one concrete fluid.
     */
    public static IngredientValue fluidTag(Identifier tag, int amount) {
        return new IngredientValue(Kind.FLUID_TAG, tag, Math.max(1, amount));
    }

    public Kind kind() {
        return kind;
    }

    public Identifier id() {
        return id;
    }

    /** Millibuckets, for {@link Kind#FLUID} only; 0 for every other kind. */
    public int amount() {
        return amount;
    }

    /** True for a fluid quantity, whether it names one fluid or a whole tag of them. */
    public boolean isFluid() {
        return (kind == Kind.FLUID || kind == Kind.FLUID_TAG) && id != null;
    }

    public boolean isFluidTag() {
        return kind == Kind.FLUID_TAG && id != null;
    }

    public boolean isEmpty() {
        return kind == Kind.EMPTY || id == null;
    }

    /**
     * Serializes to a vanilla ingredient object: {@code {"item": id}} or {@code {"tag": id}}. An empty
     * value has no id to write, so it falls back to air rather than throwing — callers that care filter
     * empties out first, and the server rejects the recipe anyway.
     */
    /**
     * How vanilla writes an ingredient from 1.21.11: a bare string, with a leading {@code #} for a tag.
     *
     * <p>The {@code {"item": …}} / {@code {"tag": …}} object of earlier versions is not a valid shape any
     * more — an {@code Ingredient} is a {@code HolderSet<Item>} and takes that set's codec, which reads a
     * string, a {@code #tag}, or a list of strings.
     */
    public JsonElement toIngredientJson() {
        if (isEmpty()) {
            return new JsonPrimitive("minecraft:air");
        }
        return new JsonPrimitive(kind == Kind.TAG ? "#" + id : id.toString());
    }

    /**
     * The older object shape, kept for the recipe types that are another mod's rather than the game's.
     * Create reads its own fields with its own codecs and is not bound to what vanilla did this version.
     */
    public JsonObject toIngredientObject() {
        JsonObject json = new JsonObject();
        if (isEmpty()) {
            json.addProperty("item", "minecraft:air");
        } else if (kind == Kind.TAG) {
            json.addProperty("tag", id.toString());
        } else {
            json.addProperty("item", id.toString());
        }
        return json;
    }

    /** Reads a vanilla ingredient (object or array of options); an array collapses to its first option. */
    public static IngredientValue fromIngredientJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return empty();
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            return array.isEmpty() ? empty() : fromIngredientJson(array.get(0));
        }
        if (element.isJsonPrimitive()) {
            // The shape this version writes: "minecraft:wheat", or "#minecraft:planks" for a tag.
            String raw = element.getAsString();
            boolean tagged = raw.startsWith("#");
            Identifier id = Identifier.tryParse(tagged ? raw.substring(1) : raw);
            if (id == null) {
                return empty();
            }
            return tagged ? tag(id) : item(id);
        }
        if (!element.isJsonObject()) {
            return empty();
        }
        // Still read the older object shape: a pack written for an earlier version is opened here too.
        JsonObject object = element.getAsJsonObject();
        if (object.has("tag")) {
            Identifier id = Identifier.tryParse(object.get("tag").getAsString());
            return id == null ? empty() : tag(id);
        }
        if (object.has("item")) {
            Identifier id = Identifier.tryParse(object.get("item").getAsString());
            return id == null ? empty() : item(id);
        }
        return empty();
    }
}
