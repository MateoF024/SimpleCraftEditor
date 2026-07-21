package org.mateof24.sce.core.edit;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Editable, loader-agnostic representation of a recipe under construction in the editor UI. Converted
 * to/from vanilla recipe JSON by {@link RecipeCompiler}. Only the vanilla families handled in this
 * phase are modelled; richer/modded recipes come through adapters and typed editors in later phases.
 */
public final class RecipeDraft {
    public enum Kind {
        CRAFTING_SHAPELESS, CRAFTING_SHAPED, COOKING, STONECUTTING, CREATE_PROCESSING, MECHANICAL_CRAFTING,
        SEQUENCED_ASSEMBLY
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

    // Shaped: a width*height row-major grid. Shapeless: an unordered input list. Cooking/stonecutting: inputs[0].
    // Mechanical crafting is shaped too, but on a grid larger than 3x3.
    public int width = 3;
    public int height = 3;
    public final List<IngredientValue> inputs = new ArrayList<>();

    /** Mechanical crafting only: whether Create should also match the pattern mirrored. */
    public boolean acceptMirrored;

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
