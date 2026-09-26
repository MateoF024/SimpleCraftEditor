package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.architectury.hooks.fluid.FluidStackHooks;
import net.minecraft.resources.Identifier;

/**
 * A single editable ingredient option: an item, an item tag, a fluid, or empty. Kept deliberately simple
 * (one option per slot) for the vanilla editors.
 *
 * <p>Fluids only appear in Create recipes, which take them as a quantity rather than as a bucket item.
 * {@link #amount()} carries that quantity in millibuckets (1 bucket = 1000 mB) and is meaningless for the
 * other kinds. The fluid JSON shape differs between Create versions, so it is written by
 * {@link CreateRecipeCompiler} rather than here.
 *
 * <p><b>Whatever was read is what gets written back, unless the author changed it.</b> An ingredient in
 * the wild is not always one of the four shapes above: it can be a list of alternatives, a mod's own
 * ingredient type wrapping another one so the recipe survives that mod being absent, or a block tag,
 * which is not the same thing as an item tag. Modelling each shape is neither possible nor the point, so
 * every value remembers the exact JSON it came from in {@link #source} and hands it straight back; only
 * building a value from the screen drops it. The author sees the first item the ingredient admits, can
 * replace it outright, and everything they did not touch survives untouched.
 */
public final class IngredientValue {
    public enum Kind {
        EMPTY, ITEM, TAG, FLUID, FLUID_TAG,
        /** Something real that none of the other kinds describes; carried through by {@link #source}. */
        RAW
    }

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

    private static final IngredientValue EMPTY = new IngredientValue(Kind.EMPTY, null, 0, null);

    private final Kind kind;
    private final Identifier id;
    private final int amount;
    /**
     * The JSON this value was read from, or null when the screen built it. Written back verbatim, which
     * is what keeps an ingredient this editor does not model intact across an open and a save.
     */
    private final JsonElement source;

    private IngredientValue(Kind kind, Identifier id, int amount, JsonElement source) {
        this.kind = kind;
        this.id = id;
        this.amount = amount;
        this.source = source;
    }

    public static IngredientValue empty() {
        return EMPTY;
    }

    public static IngredientValue item(Identifier item) {
        return new IngredientValue(Kind.ITEM, item, 0, null);
    }

    public static IngredientValue tag(Identifier tag) {
        return new IngredientValue(Kind.TAG, tag, 0, null);
    }

    /** A fluid ingredient or result of {@code amount} millibuckets. */
    public static IngredientValue fluid(Identifier fluid, int amount) {
        return new IngredientValue(Kind.FLUID, fluid, Math.max(1, amount), null);
    }

    /**
     * A fluid ingredient matching any fluid in {@code tag}, of {@code amount} millibuckets. Only valid as an
     * ingredient — a result has to name one concrete fluid.
     */
    public static IngredientValue fluidTag(Identifier tag, int amount) {
        return new IngredientValue(Kind.FLUID_TAG, tag, Math.max(1, amount), null);
    }

    /**
     * The same value, but remembering the JSON it was read from. Used by the compilers as they parse, so
     * that a value nobody edits is written back character for character.
     */
    public IngredientValue withSource(JsonElement read) {
        return read == null ? this : new IngredientValue(kind, id, amount, read.deepCopy());
    }

    /**
     * An ingredient shape this editor has no model for, kept whole. {@code preview} is the first item or
     * tag found inside it, used only to draw something in the slot and to fill the id field; it is never
     * written back.
     */
    public static IngredientValue raw(JsonElement read, Identifier preview) {
        return new IngredientValue(Kind.RAW, preview, 0, read == null ? null : read.deepCopy());
    }

    public Kind kind() {
        return kind;
    }

    /** The id this value names, or — for {@link Kind#RAW} — the first one found inside it; may be null. */
    public Identifier id() {
        return id;
    }

    /** Millibuckets, for the two fluid kinds only; 0 for every other kind. */
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

    /** True for an ingredient carried through whole because the editor cannot model its shape. */
    public boolean isRaw() {
        return kind == Kind.RAW;
    }

    /**
     * Whether this value still remembers the JSON it was read from, and so will be written back exactly
     * as it arrived. False for anything the screen built, which is how editing a slot replaces whatever
     * was there rather than resurrecting it.
     */
    public boolean isVerbatim() {
        return source != null;
    }

    public boolean isEmpty() {
        return kind == Kind.EMPTY || (kind != Kind.RAW && id == null);
    }

    /**
     * How vanilla writes an ingredient from 1.21.11: a bare string, with a leading {@code #} for a tag.
     *
     * <p>The {@code {"item": …}} / {@code {"tag": …}} object of earlier versions is not a valid shape any
     * more — an {@code Ingredient} is a {@code HolderSet<Item>} and takes that set's codec, which reads a
     * string, a {@code #tag}, or a list of strings. A value the author left alone writes back whatever it
     * was read from, which covers the list and anything else this editor does not model.
     */
    public JsonElement toIngredientJson() {
        if (source != null) {
            return source.deepCopy();
        }
        if (isEmpty()) {
            return new JsonPrimitive("minecraft:air");
        }
        return new JsonPrimitive(kind == Kind.TAG ? "#" + id : id.toString());
    }

    /**
     * The older object shape, kept for the recipe types that are another mod's rather than the game's.
     * Create reads its own fields with its own codecs and is not bound to what vanilla did this version.
     */
    public JsonElement toIngredientObject() {
        if (source != null) {
            return source.deepCopy();
        }
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

    /**
     * Reads a vanilla ingredient. The shapes the editor models become their own kind; anything else —
     * a list of alternatives, a mod's own ingredient type, a block tag — becomes {@link Kind#RAW} and is
     * carried through whole.
     */

    /**
     * The same ingredient written the way this version writes it.
     *
     * <p>An ingredient is a bare {@code "x"} here and was {@code {"item": "x"}} up to 1.21.1, and a tag
     * is {@code "#x"} here and was {@code {"tag": "x"}} there. A recipe stored by this mod on one of
     * those versions carries the older shape, and this game's codec cannot read it - so it is put into
     * this one on the way in. Kept sources are written back exactly as they came, which is what makes an
     * untouched ingredient survive a save; normalising before the source is taken is what keeps that
     * promise from also carrying a shape this version cannot read straight back out to the file.
     *
     * <p>Anything with a {@code type} is somebody's own kind of ingredient and is left alone, and so is
     * an object carrying more than the one key, which is not an ingredient of either shape. A list is a
     * list of ingredients, so each of them goes through this too.
     */
    public static JsonElement normalise(JsonElement element) {
        if (element == null) {
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                out.add(normalise(child));
            }
            return out;
        }
        if (!element.isJsonObject()) {
            return element;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("type") || object.size() != 1) {
            return element;
        }
        if (object.has("item")) {
            return new JsonPrimitive(object.get("item").getAsString());
        }
        if (object.has("tag")) {
            return new JsonPrimitive("#" + object.get("tag").getAsString());
        }
        return element;
    }

    public static IngredientValue fromIngredientJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return empty();
        }
        // Whatever version wrote it, read it as this one spells it.
        element = normalise(element);
        if (element.isJsonPrimitive()) {
            // The shape this version writes: "minecraft:wheat", or "#minecraft:planks" for a tag.
            String raw = element.getAsString();
            boolean tagged = raw.startsWith("#");
            Identifier id = Identifier.tryParse(tagged ? raw.substring(1) : raw);
            if (id != null) {
                return (tagged ? tag(id) : item(id)).withSource(element);
            }
        } else if (element.isJsonObject()) {
            // Still read the older object shape: a pack written for an earlier version is opened here
            // too. A "type" means somebody's own ingredient kind, and even when it also carries a tag
            // that tag is not necessarily an item tag — a block tag read as one matches nothing.
            JsonObject object = element.getAsJsonObject();
            if (!object.has("type")) {
                if (object.has("tag")) {
                    Identifier id = Identifier.tryParse(object.get("tag").getAsString());
                    if (id != null) {
                        return tag(id).withSource(element);
                    }
                } else if (object.has("item")) {
                    Identifier id = Identifier.tryParse(object.get("item").getAsString());
                    if (id != null) {
                        return item(id).withSource(element);
                    }
                }
            }
        }
        return raw(element, previewOf(element));
    }

    /**
     * The first item or tag named anywhere inside an ingredient, so an unmodelled one still shows
     * something in its slot instead of an empty square. Depth is bounded by the JSON itself; a wrapper is
     * one level deep and a list of alternatives is two.
     */
    private static Identifier previewOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            String raw = element.getAsString();
            return Identifier.tryParse(raw.startsWith("#") ? raw.substring(1) : raw);
        }
        if (element.isJsonArray()) {
            for (JsonElement option : element.getAsJsonArray()) {
                Identifier found = previewOf(option);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        for (String key : new String[]{"item", "tag"}) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                Identifier id = Identifier.tryParse(object.get(key).getAsString());
                if (id != null) {
                    return id;
                }
            }
        }
        if (object.has("ingredients")) {
            return previewOf(object.get("ingredients"));
        }
        return null;
    }
}
