package org.mateof24.sce.core.edit;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Editable, loader-agnostic representation of a recipe under construction in the editor UI. Converted
 * to/from vanilla recipe JSON by {@link RecipeCompiler}. Only the vanilla families handled in this
 * phase are modelled; richer/modded recipes come through adapters and typed editors in later phases.
 */
public final class RecipeDraft {
    public enum Kind {
        CRAFTING_SHAPELESS, CRAFTING_SHAPED, COOKING, STONECUTTING, CREATE_PROCESSING, MECHANICAL_CRAFTING,
        SEQUENCED_ASSEMBLY,
        /** The smithing table's upgrade: diamond gear into netherite, and whatever a pack adds. */
        SMITHING_TRANSFORM,
        /** Cobblemon's campfire pot: the two crafting shapes again, with the pot's own fields around them. */
        COOKING_POT, COOKING_POT_SHAPELESS
    }

    /**
     * Side of the square grid the editor offers for mechanical crafting. Create's own recipes fit inside
     * this; the recipe format itself allows far larger patterns, and a pattern loaded from a bigger one is
     * clipped to what the editor can show.
     */
    public static final int MECHANICAL_SIZE = 5;

    /** One item output of a Create processing recipe: an item, a count and a drop chance (0..1). */
    public static final class ResultEntry {
        public IngredientValue item;
        public int count;
        public float chance;

        public ResultEntry(IngredientValue item, int count, float chance) {
            this.item = item;
            this.count = count;
            this.chance = chance;
        }
    }

    /** Cooking sub-type with its recipe-type id and the vanilla default cooking time. */
    public enum Cooking {
        SMELTING("minecraft:smelting", 200),
        BLASTING("minecraft:blasting", 100),
        SMOKING("minecraft:smoking", 100),
        CAMPFIRE("minecraft:campfire_cooking", 600);

        public final String type;
        public final int defaultTime;

        Cooking(String type, int defaultTime) {
            this.type = type;
            this.defaultTime = defaultTime;
        }

        /** The cooking kind a recipe's {@code type} names, or null if it is not a cooking recipe. */
        public static Cooking fromType(String type) {
            for (Cooking cooking : values()) {
                if (cooking.type.equals(type)) {
                    return cooking;
                }
            }
            return null;
        }
    }

    public Kind kind = Kind.CRAFTING_SHAPELESS;
    public ResourceLocation id;
    public String group = "";

    /**
     * The {@code type} this recipe was read with, kept so that editing one belonging to another mod does
     * not quietly turn it into a plain vanilla recipe.
     *
     * <p>Mods commonly write a recipe that is a vanilla shaped or shapeless recipe in every respect
     * except that its own class does something extra when the result is assembled — Sophisticated
     * Backpacks copies the old backpack's contents onto the new one that way. Rewriting such a recipe as
     * {@code minecraft:crafting_shaped} would keep the ingredients and lose the behaviour, and the loss
     * is silent: the recipe still crafts, it just eats what was inside. Writing the type back is what
     * keeps the mod's own half of the recipe alive.
     *
     * <p>Empty when the editor wrote the type itself, and deliberately dropped when the type button is
     * used, because that re-opens the editor without the stored recipe — which is the way to say
     * "make this an ordinary recipe".
     */
    public String sourceType = "";

    /**
     * Top-level JSON this editor does not model, carried through untouched.
     *
     * <p>A recipe is not only the parts an editor draws. Sophisticated Backpacks and Create attach
     * {@code conditions} that decide whether the recipe loads at all; Cobblemon's cooking pot adds its
     * own category and seasoning fields. None of that is ours to understand, and all of it would be lost
     * by writing back only what the screen shows. Kept in order so a re-saved file still reads like the
     * one it came from.
     */
    public final Map<String, JsonElement> extras = new LinkedHashMap<>();

    // Shaped: a width*height row-major grid. Shapeless: an unordered input list. Cooking/stonecutting: inputs[0].
    // Mechanical crafting is shaped too, but on a grid larger than 3x3.
    public int width = 3;
    public int height = 3;
    public final List<IngredientValue> inputs = new ArrayList<>();

    /** Mechanical crafting only: whether Create should also match the pattern mirrored. */
    public boolean acceptMirrored;

    /**
     * Cobblemon's campfire pot only: which tab of its recipe book the recipe appears under. One of
     * {@link CookingPot#CATEGORIES}; every pot recipe has to name one.
     */
    public String potCategory = CookingPot.DEFAULT_CATEGORY;

    /**
     * Cobblemon's campfire pot only: the item tag naming what a player may drop into the pot's three
     * seasoning slots. {@code cobblemon:empty} is a real, empty tag and means the recipe takes none.
     *
     * <p>This is the author's decision, not the player's: the recipe never names the seasoning items —
     * the player picks those at the pot — but it decides which are allowed at all.
     */
    public String potSeasoningTag = CookingPot.NO_SEASONING;

    /**
     * Cobblemon's campfire pot only: which of {@link CookingPot#PROCESSORS} the dish absorbs from the
     * seasoning it was cooked with. Empty means the seasoning changes nothing.
     */
    public final List<String> potProcessors = new ArrayList<>();

    /**
     * Cobblemon's campfire pot only: the result exactly as the file spelled it, kept when it carries
     * components this editor has no field for — a suspicious stew's effects, a tea's food block. Written
     * back untouched while the author leaves the result item alone, and dropped the moment they change
     * it, because then the components belonged to the item they replaced.
     */
    public JsonElement potResult;

    /**
     * Crafting only: where the crafted result's data comes from — one of the names of
     * {@code InheritingCraftingRecipe.Carry}, or {@code auto} to let the editor decide from what was put
     * in the slots. {@code none} is an ordinary recipe that produces a fresh result.
     */
    public String carry = "auto";

    /**
     * Crafting only: whether the data on the ingredients is part of the match — {@code auto},
     * {@code ignore} or {@code require}. Requiring it is what makes a recipe work only with the chest
     * that is actually called "Pepito" rather than with any chest at all.
     */
    public String matchData = "auto";

    /**
     * The stacks the grid must contain, each written as the text of its own save tag so that every kind
     * of data travels in one field. Filled in from what the author physically placed in the slots.
     */
    public final List<String> requiredStacks = new ArrayList<>();

    /** The result exactly as the author left it, in the same text form. Empty when they left it plain. */
    public String resultStack = "";

    // Sequenced assembly: one base ingredient (inputs[0]) is carried through an ordered list of processing
    // steps, each of which is itself a recipe, looping a number of times before yielding the results.
    public IngredientValue transitionalItem = IngredientValue.empty();
    public int loops = 1;
    public final List<RecipeDraft> sequence = new ArrayList<>();

    public IngredientValue result = IngredientValue.empty();
    public int resultCount = 1;

    public Cooking cooking = Cooking.SMELTING;
    public float experience = 0.1f;
    public int cookingTime = 200;

    // Create processing
    public String createType = "";
    public int processingTime = 0;
    public String heat = "none";
    public final List<ResultEntry> results = new ArrayList<>();

    public RecipeDraft() {
    }

    /** A blank draft of the given kind with an input list sized for its layout. */
    public static RecipeDraft blank(Kind kind) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = kind;
        if (kind == Kind.MECHANICAL_CRAFTING) {
            draft.width = MECHANICAL_SIZE;
            draft.height = MECHANICAL_SIZE;
        }
        int slots = switch (kind) {
            case CRAFTING_SHAPED -> draft.width * draft.height;
            case CRAFTING_SHAPELESS -> 9;
            case COOKING, STONECUTTING -> 1;
            case CREATE_PROCESSING -> 6;
            case MECHANICAL_CRAFTING -> MECHANICAL_SIZE * MECHANICAL_SIZE;
            case SEQUENCED_ASSEMBLY -> 1; // the single base ingredient the sequence starts from
            // The smithing table's three: template, base and addition, always in that order.
            case SMITHING_TRANSFORM -> 3;
            // The pot is the two crafting shapes over again, so it needs the same slots they do.
            case COOKING_POT -> draft.width * draft.height;
            case COOKING_POT_SHAPELESS -> 9;
        };
        for (int i = 0; i < slots; i++) {
            draft.inputs.add(IngredientValue.empty());
        }
        return draft;
    }

    public IngredientValue input(int index) {
        return index >= 0 && index < inputs.size() ? inputs.get(index) : IngredientValue.empty();
    }

    public void setInput(int index, IngredientValue value) {
        while (inputs.size() <= index) {
            inputs.add(IngredientValue.empty());
        }
        inputs.set(index, value);
    }
}
