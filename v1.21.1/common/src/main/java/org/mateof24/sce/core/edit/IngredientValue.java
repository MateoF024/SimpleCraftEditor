package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

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

    /** A bucket in millibuckets — the unit Create counts fluids in. */
    public static final int BUCKET = 1000;

    private static final IngredientValue EMPTY = new IngredientValue(Kind.EMPTY, null, 0);

    private final Kind kind;
    private final ResourceLocation id;
    private final int amount;

    private IngredientValue(Kind kind, ResourceLocation id, int amount) {
        this.kind = kind;
        this.id = id;
        this.amount = amount;
    }

    public static IngredientValue empty() {
        return EMPTY;
    }

    public static IngredientValue item(ResourceLocation item) {
        return new IngredientValue(Kind.ITEM, item, 0);
    }

    public static IngredientValue tag(ResourceLocation tag) {
        return new IngredientValue(Kind.TAG, tag, 0);
    }

    /** A fluid ingredient or result of {@code amount} millibuckets. */
    public static IngredientValue fluid(ResourceLocation fluid, int amount) {
        return new IngredientValue(Kind.FLUID, fluid, Math.max(1, amount));
    }

    /**
     * A fluid ingredient matching any fluid in {@code tag}, of {@code amount} millibuckets. Only valid as an
     * ingredient — a result has to name one concrete fluid.
     */
    public static IngredientValue fluidTag(ResourceLocation tag, int amount) {
        return new IngredientValue(Kind.FLUID_TAG, tag, Math.max(1, amount));
    }

    public Kind kind() {
        return kind;
    }

    public ResourceLocation id() {
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

    /** Serializes to a vanilla ingredient object: {@code {"item": id}} or {@code {"tag": id}}. */
    public JsonObject toIngredientJson() {
        JsonObject json = new JsonObject();
        if (kind == Kind.TAG) {
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
        if (!element.isJsonObject()) {
            return empty();
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("tag")) {
            ResourceLocation id = ResourceLocation.tryParse(object.get("tag").getAsString());
            return id == null ? empty() : tag(id);
        }
        if (object.has("item")) {
            ResourceLocation id = ResourceLocation.tryParse(object.get("item").getAsString());
            return id == null ? empty() : item(id);
        }
        return empty();
    }
}
