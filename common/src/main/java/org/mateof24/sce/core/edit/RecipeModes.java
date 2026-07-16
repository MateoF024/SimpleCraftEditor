package org.mateof24.sce.core.edit;

/**
 * The selectable recipe types in the editor and the slot layout each one uses. Shared by the menu (which
 * builds the right slots server-side) and the screen (which labels the type button and compiles the draft).
 */
public final class RecipeModes {
    public static final int COUNT = 7;

    private static final RecipeDraft.Kind[] KIND = {
            RecipeDraft.Kind.CRAFTING_SHAPELESS, RecipeDraft.Kind.CRAFTING_SHAPED,
            RecipeDraft.Kind.COOKING, RecipeDraft.Kind.COOKING, RecipeDraft.Kind.COOKING, RecipeDraft.Kind.COOKING,
            RecipeDraft.Kind.STONECUTTING};
    private static final RecipeDraft.Cooking[] COOK = {
            null, null, RecipeDraft.Cooking.SMELTING, RecipeDraft.Cooking.BLASTING,
            RecipeDraft.Cooking.SMOKING, RecipeDraft.Cooking.CAMPFIRE, null};
    private static final String[] LABEL = {
            "Shapeless", "Shaped", "Smelting", "Blasting", "Smoking", "Campfire", "Stonecutting"};

    private RecipeModes() {
    }

    public static RecipeDraft.Kind kind(int mode) {
        return KIND[clamp(mode)];
    }

    public static RecipeDraft.Cooking cooking(int mode) {
        RecipeDraft.Cooking cooking = COOK[clamp(mode)];
        return cooking != null ? cooking : RecipeDraft.Cooking.SMELTING;
    }

    public static String label(int mode) {
        return LABEL[clamp(mode)];
    }

    public static boolean isCrafting(int mode) {
        RecipeDraft.Kind kind = KIND[clamp(mode)];
        return kind == RecipeDraft.Kind.CRAFTING_SHAPED || kind == RecipeDraft.Kind.CRAFTING_SHAPELESS;
    }

    public static boolean isCooking(int mode) {
        return KIND[clamp(mode)] == RecipeDraft.Kind.COOKING;
    }

    public static int inputCount(int mode) {
        return isCrafting(mode) ? 9 : 1;
    }

    public static int clamp(int mode) {
        return ((mode % COUNT) + COUNT) % COUNT;
    }

    /** Maps a parsed recipe back to the matching editor mode (0 if unknown). */
    public static int indexOf(RecipeDraft draft) {
        for (int i = 0; i < COUNT; i++) {
            if (KIND[i] == draft.kind && (COOK[i] == null || COOK[i] == draft.cooking)) {
                return i;
            }
        }
        return 0;
    }
}
