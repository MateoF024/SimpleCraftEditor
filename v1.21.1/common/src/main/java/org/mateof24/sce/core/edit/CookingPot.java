package org.mateof24.sce.core.edit;

import java.util.List;

/**
 * What Cobblemon's campfire pot needs a recipe to say, and nothing more.
 *
 * <p>Its two recipe types are an ordinary grid with three extra fields around it: a book category, and a
 * pair of seasoning fields that decide what may be added to the pot alongside the ingredients. All 116
 * of the mod's own pot recipes carry all three, so they are not optional — a recipe written without them
 * will not load.
 *
 * <p>The category is modelled here because a recipe being written from nothing has no value to inherit
 * and somebody has to choose one. The seasoning pair is not: it is Cobblemon's own business, it is
 * carried through untouched by {@link RecipeCompiler#preserveFrom}, and the only thing this editor knows
 * about it is what an unseasoned recipe looks like, which is all a new one needs.
 */
public final class CookingPot {
    public static final String MOD_ID = "cobblemon";
    public static final String SHAPED_TYPE = "cobblemon:cooking_pot";
    public static final String SHAPELESS_TYPE = "cobblemon:cooking_pot_shapeless";

    /** The recipe book tabs, in the order Cobblemon's own enum declares them. */
    public static final List<String> CATEGORIES = List.of("foods", "medicines", "complex_dishes", "misc");
    /** What an unlabelled recipe falls back to, and the tab most of the mod's own recipes use. */
    public static final String DEFAULT_CATEGORY = "misc";

    public static final String SEASONING_TAG_KEY = "seasoningTag";
    public static final String SEASONING_PROCESSORS_KEY = "seasoningProcessors";
    /** The seasoning tag on a recipe that takes none, which is what a new recipe starts as. */
    public static final String NO_SEASONING = "cobblemon:empty";

    /**
     * The seven things a recipe can absorb from the seasoning a player adds, by the ids Cobblemon
     * registers them under. Each one reads the seasonings, looks them up in the {@code seasonings} data
     * registry, and writes one component onto the finished dish:
     *
     * <ul>
     *   <li>{@code ingredient} — the list of what was added, so the dish remembers it;</li>
     *   <li>{@code food_colour} — the colours of what was added;</li>
     *   <li>{@code food} — nutrition and saturation, merged over the dish's own;</li>
     *   <li>{@code flavour} — the berry flavours;</li>
     *   <li>{@code mob_effects} — the effects the dish will give;</li>
     *   <li>{@code ride_boosts} — Aprijuice's riding stats, worked out from the flavours;</li>
     *   <li>{@code spawn_bait} — fishing bait effects.</li>
     * </ul>
     *
     * <p>A seasoning is only consumed when the recipe absorbs something it actually carries; otherwise it
     * sits in the pot as a catalyst.
     */
    public static final List<String> PROCESSORS = List.of(
            "ingredient", "food_colour", "food", "flavour", "mob_effects", "ride_boosts", "spawn_bait");

    /** How many seasoning slots the pot has. Fixed by the block, not by the recipe. */
    public static final int SEASONING_SLOTS = 3;

    private CookingPot() {
    }

    public static boolean isPotType(String type) {
        return SHAPED_TYPE.equals(type) || SHAPELESS_TYPE.equals(type);
    }

    /** A category the pot will accept: anything unfamiliar becomes the default rather than failing to load. */
    public static String category(String value) {
        return value != null && CATEGORIES.contains(value) ? value : DEFAULT_CATEGORY;
    }

    /**
     * Whether the pair of seasoning fields says anything at all.
     *
     * <p>They are only meaningful together, which is worth saying out loud because neither half fails
     * loudly on its own: a tag with no processors lets a player drop seasoning into the pot where it does
     * nothing and is not even consumed, and processors with the empty tag run over a list that can never
     * have anything in it. Both are recipes that load, craft, and quietly ignore half of what they say.
     */
    public static boolean seasoningIsCoherent(String tag, List<String> processors) {
        boolean takesSeasoning = tag != null && !tag.isBlank() && !NO_SEASONING.equals(tag);
        return takesSeasoning == !processors.isEmpty();
    }

    /** The tag as the file spells it: no leading {@code #}, and never blank. */
    public static String seasoningTag(String value) {
        if (value == null || value.isBlank()) {
            return NO_SEASONING;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
    }
}
