package org.mateof24.sce.core.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;

/**
 * Writes a loaded recipe back out as the datapack JSON that would have produced it.
 *
 * <p>Not every recipe comes from a file. KubeJS and CraftTweaker build theirs from a script, mods build
 * theirs in code, and Create generates some at runtime — none of those exist as JSON anywhere, so the
 * editor, which reads the datapack source, had nothing to open and showed an empty screen with only the
 * id filled in. What all of them do have is a recipe object in the game, and for the types the editor
 * knows, that object holds everything the JSON did: the ingredients, the result, the shape, the times.
 *
 * <p>So the source of a recipe stops mattering. A recipe written in a KubeJS script opens the same as one
 * from a datapack, because both are read from the loaded recipe rather than from a file.
 *
 * <p>Only the types the editor can actually edit are written; anything else returns null and the editor
 * says it cannot open the recipe, which is the truth and better than opening it blank. Recipe classes are
 * matched by their game type rather than by their Java class, so a mod that reuses a vanilla type still
 * reads correctly.
 */
public final class LiveRecipeJson {
    private LiveRecipeJson() {
    }

    /** The recipe as datapack JSON, or null if it is not a type the editor can represent. */
    public static JsonObject of(Recipe<?> recipe, RegistryAccess registries) {
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        if (type == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("type", type.toString());
        if (!recipe.getGroup().isEmpty()) {
            json.addProperty("group", recipe.getGroup());
        }
        try {
            if (recipe instanceof ShapedRecipe shaped) {
                return shaped(json, shaped, registries);
            }
            if (recipe instanceof ShapelessRecipe shapeless) {
                return shapeless(json, shapeless, registries);
            }
            if (recipe instanceof AbstractCookingRecipe cooking) {
                return cooking(json, cooking, registries);
            }
            if (recipe instanceof SingleItemRecipe single) {
                return stonecutting(json, single, registries);
            }
        } catch (Exception ignored) {
            // A recipe that will not describe itself is simply one the editor cannot open.
        }
        return null;
    }

    private static JsonObject shaped(JsonObject json, ShapedRecipe recipe, RegistryAccess registries) {
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        int width = recipe.getWidth();
        int height = recipe.getHeight();

        // The grid is rebuilt into a pattern and a key, the way it is written by hand: one letter per
        // distinct ingredient, a space where the slot is empty. Letters are assigned in reading order so
        // the same recipe always comes out the same way.
        JsonArray pattern = new JsonArray();
        JsonObject key = new JsonObject();
        java.util.Map<String, Character> letters = new java.util.LinkedHashMap<>();
        char next = 'A';
        for (int row = 0; row < height; row++) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < width; column++) {
                Ingredient ingredient = ingredients.get(row * width + column);
                if (ingredient.isEmpty()) {
                    line.append(' ');
                    continue;
                }
                String written = ingredient.toJson().toString();
                Character letter = letters.get(written);
                if (letter == null) {
                    letter = next++;
                    letters.put(written, letter);
                    key.add(String.valueOf(letter), ingredient.toJson());
                }
                line.append(letter.charValue());
            }
            pattern.add(line.toString());
        }
        json.add("pattern", pattern);
        json.add("key", key);
        json.add("result", stack(recipe.getResultItem(registries)));
        return json;
    }

    private static JsonObject shapeless(JsonObject json, ShapelessRecipe recipe, RegistryAccess registries) {
        JsonArray ingredients = new JsonArray();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                ingredients.add(ingredient.toJson());
            }
        }
        json.add("ingredients", ingredients);
        json.add("result", stack(recipe.getResultItem(registries)));
        return json;
    }

    private static JsonObject cooking(JsonObject json, AbstractCookingRecipe recipe, RegistryAccess registries) {
        json.add("ingredient", recipe.getIngredients().get(0).toJson());
        // Cooking recipes name their result as a bare item id, not as an object with a count.
        json.addProperty("result", itemId(recipe.getResultItem(registries)));
        json.addProperty("experience", recipe.getExperience());
        json.addProperty("cookingtime", recipe.getCookingTime());
        return json;
    }

    private static JsonObject stonecutting(JsonObject json, SingleItemRecipe recipe, RegistryAccess registries) {
        json.add("ingredient", recipe.getIngredients().get(0).toJson());
        ItemStack result = recipe.getResultItem(registries);
        json.addProperty("result", itemId(result));
        json.addProperty("count", Math.max(1, result.getCount()));
        return json;
    }

    private static JsonObject stack(ItemStack stack) {
        JsonObject json = new JsonObject();
        json.addProperty("item", itemId(stack));
        json.addProperty("count", Math.max(1, stack.getCount()));
        return json;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
