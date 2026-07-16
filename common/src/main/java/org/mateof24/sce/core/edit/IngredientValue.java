package org.mateof24.sce.core.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/**
 * A single editable ingredient option: an item, a tag, or empty. Kept deliberately simple (one option
 * per slot) for the vanilla editors; multi-option ingredients collapse to their first option on load.
 */
public final class IngredientValue {
    public enum Kind {EMPTY, ITEM, TAG}

    private static final IngredientValue EMPTY = new IngredientValue(Kind.EMPTY, null);

    private final Kind kind;
    private final ResourceLocation id;

    private IngredientValue(Kind kind, ResourceLocation id) {
        this.kind = kind;
        this.id = id;
    }

    public static IngredientValue empty() {
        return EMPTY;
    }

    public static IngredientValue item(ResourceLocation item) {
        return new IngredientValue(Kind.ITEM, item);
    }

    public static IngredientValue tag(ResourceLocation tag) {
        return new IngredientValue(Kind.TAG, tag);
    }

    public Kind kind() {
        return kind;
    }

    public ResourceLocation id() {
        return id;
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
