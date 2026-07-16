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
    public enum Kind {CRAFTING_SHAPELESS, CRAFTING_SHAPED, COOKING, STONECUTTING}

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
    }

    public Kind kind = Kind.CRAFTING_SHAPELESS;
    public ResourceLocation id;
    public String group = "";

    // Shaped: a width*height row-major grid. Shapeless: an unordered input list. Cooking/stonecutting: inputs[0].
    public int width = 3;
    public int height = 3;
    public final List<IngredientValue> inputs = new ArrayList<>();

    public IngredientValue result = IngredientValue.empty();
    public int resultCount = 1;

    public Cooking cooking = Cooking.SMELTING;
    public float experience = 0.1f;
    public int cookingTime = 200;

    public RecipeDraft() {
    }

    /** A blank draft of the given kind with an input list sized for its layout. */
    public static RecipeDraft blank(Kind kind) {
        RecipeDraft draft = new RecipeDraft();
        draft.kind = kind;
        int slots = switch (kind) {
            case CRAFTING_SHAPED -> draft.width * draft.height;
            case CRAFTING_SHAPELESS -> 9;
            case COOKING, STONECUTTING -> 1;
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
