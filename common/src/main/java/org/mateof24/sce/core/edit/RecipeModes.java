package org.mateof24.sce.core.edit;

import dev.architectury.platform.Platform;

/**
 * The selectable recipe types in the editor and the slot layout each one uses. Shared by the menu (which
 * builds the right slots server-side) and the screen (which labels the type button and compiles the draft).
 * Create processing types are only offered when Create is installed.
 */
public final class RecipeModes {
    private static final RecipeDraft.Kind[] KIND = {
            RecipeDraft.Kind.CRAFTING_SHAPELESS, RecipeDraft.Kind.CRAFTING_SHAPED,
            RecipeDraft.Kind.COOKING, RecipeDraft.Kind.COOKING, RecipeDraft.Kind.COOKING, RecipeDraft.Kind.COOKING,
            RecipeDraft.Kind.STONECUTTING,
            RecipeDraft.Kind.CREATE_PROCESSING, RecipeDraft.Kind.CREATE_PROCESSING, RecipeDraft.Kind.CREATE_PROCESSING,
            RecipeDraft.Kind.CREATE_PROCESSING, RecipeDraft.Kind.CREATE_PROCESSING, RecipeDraft.Kind.CREATE_PROCESSING,
            RecipeDraft.Kind.CREATE_PROCESSING, RecipeDraft.Kind.CREATE_PROCESSING, RecipeDraft.Kind.CREATE_PROCESSING,
            RecipeDraft.Kind.CREATE_PROCESSING,
            RecipeDraft.Kind.CREATE_PROCESSING, RecipeDraft.Kind.CREATE_PROCESSING, RecipeDraft.Kind.CREATE_PROCESSING,
            RecipeDraft.Kind.CREATE_PROCESSING, RecipeDraft.Kind.CREATE_PROCESSING,
            RecipeDraft.Kind.MECHANICAL_CRAFTING, RecipeDraft.Kind.SEQUENCED_ASSEMBLY};
    private static final RecipeDraft.Cooking[] COOK = {
            null, null, RecipeDraft.Cooking.SMELTING, RecipeDraft.Cooking.BLASTING,
            RecipeDraft.Cooking.SMOKING, RecipeDraft.Cooking.CAMPFIRE, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null};
    private static final String[] CREATE_TYPE = {
            null, null, null, null, null, null, null,
            "create:mixing", "create:crushing", "create:milling", "create:pressing", "create:compacting",
            "create:cutting", "create:splashing", "create:haunting", "create:sandpaper_polishing", "create:deploying",
            // Spout, Item Drain, Basin, and the two item-swap machines: all plain processing recipes, so they
            // share the ingredient/result layout of the types above.
            "create:filling", "create:emptying", "create:basin", "create:conversion", "create:item_application",
            "create:mechanical_crafting", SequencedAssemblyCompiler.TYPE};
    private static final String[] LABEL_KEY = {
            "sce.mode.shapeless", "sce.mode.shaped", "sce.mode.smelting", "sce.mode.blasting",
            "sce.mode.smoking", "sce.mode.campfire", "sce.mode.stonecutting",
            "sce.mode.create_mixing", "sce.mode.create_crushing", "sce.mode.create_milling",
            "sce.mode.create_pressing", "sce.mode.create_compacting", "sce.mode.create_cutting",
            "sce.mode.create_splashing", "sce.mode.create_haunting", "sce.mode.create_sandpaper",
            "sce.mode.create_deploying",
            "sce.mode.create_filling", "sce.mode.create_emptying", "sce.mode.create_basin",
            "sce.mode.create_conversion", "sce.mode.create_item_application",
            "sce.mode.create_mechanical_crafting", "sce.mode.create_sequenced_assembly"};
    private static final int[] INPUTS = {
            9, 9, 1, 1, 1, 1, 1,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6,
            RecipeDraft.MECHANICAL_SIZE * RecipeDraft.MECHANICAL_SIZE,
            1}; // sequenced assembly is edited on its own screen, not on the slot grid
    private static final int[] OUTPUTS = {
            1, 1, 1, 1, 1, 1, 1,
            4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
            4, 4, 4, 4, 4,
            1, 1};

    public static final int COUNT = KIND.length;
    private static final int FIRST_CREATE = 7;

    private RecipeModes() {
    }

    public static RecipeDraft.Kind kind(int mode) {
        return KIND[clamp(mode)];
    }

    public static RecipeDraft.Cooking cooking(int mode) {
        RecipeDraft.Cooking cooking = COOK[clamp(mode)];
        return cooking != null ? cooking : RecipeDraft.Cooking.SMELTING;
    }

    /** Translation key for a mode's display name (e.g. {@code sce.mode.shapeless}). */
    public static String labelKey(int mode) {
        return LABEL_KEY[clamp(mode)];
    }

    public static String createType(int mode) {
        return CREATE_TYPE[clamp(mode)];
    }

    public static boolean isCrafting(int mode) {
        RecipeDraft.Kind kind = KIND[clamp(mode)];
        return kind == RecipeDraft.Kind.CRAFTING_SHAPED || kind == RecipeDraft.Kind.CRAFTING_SHAPELESS;
    }

    public static boolean isCooking(int mode) {
        return KIND[clamp(mode)] == RecipeDraft.Kind.COOKING;
    }

    public static boolean isCreate(int mode) {
        return KIND[clamp(mode)] == RecipeDraft.Kind.CREATE_PROCESSING;
    }

    /** Create's mechanical crafter: a shaped recipe on a grid bigger than the vanilla 3x3. */
    public static boolean isMechanicalCrafting(int mode) {
        return KIND[clamp(mode)] == RecipeDraft.Kind.MECHANICAL_CRAFTING;
    }

    /** Sequenced assembly is edited on a dedicated screen rather than the shared slot layout. */
    public static boolean isSequencedAssembly(int mode) {
        return KIND[clamp(mode)] == RecipeDraft.Kind.SEQUENCED_ASSEMBLY;
    }

    /**
     * Whether a Create recipe type has an editor mode. Types without one (sequenced assembly and the
     * niche machines) must not be parsed into a draft: they would load as the wrong mode and lose the
     * fields this editor does not model, so they go to the raw JSON editor instead.
     */
    public static boolean hasCreateType(String createType) {
        for (int i = FIRST_CREATE; i < COUNT; i++) {
            if (CREATE_TYPE[i].equals(createType)) {
                return true;
            }
        }
        return false;
    }

    public static int inputCount(int mode) {
        return INPUTS[clamp(mode)];
    }

    public static int outputCount(int mode) {
        return OUTPUTS[clamp(mode)];
    }

    public static int clamp(int mode) {
        return ((mode % COUNT) + COUNT) % COUNT;
    }

    public static boolean createLoaded() {
        return Platform.isModLoaded("create");
    }

    /** Whether a mode can be used right now (Create modes require Create to be installed). */
    public static boolean available(int mode) {
        return clamp(mode) < FIRST_CREATE || createLoaded();
    }

    /** Next selectable mode, skipping Create types when Create is absent. */
    public static int nextAvailable(int mode) {
        int next = clamp(mode);
        for (int i = 0; i < COUNT; i++) {
            next = clamp(next + 1);
            if (available(next)) {
                return next;
            }
        }
        return 0;
    }

    /** Previous selectable mode, so the type button can walk back when you overshoot. */
    public static int previousAvailable(int mode) {
        int previous = clamp(mode);
        for (int i = 0; i < COUNT; i++) {
            previous = clamp(previous - 1);
            if (available(previous)) {
                return previous;
            }
        }
        return 0;
    }

    /** Force a mode into the available range (used server-side when a client asks for an unavailable one). */
    public static int sanitize(int mode) {
        int clamped = clamp(mode);
        return available(clamped) ? clamped : 0;
    }

    /** Maps a parsed recipe back to the matching editor mode (0 if unknown). */
    public static int indexOf(RecipeDraft draft) {
        if (draft.kind == RecipeDraft.Kind.CREATE_PROCESSING) {
            for (int i = FIRST_CREATE; i < COUNT; i++) {
                if (CREATE_TYPE[i].equals(draft.createType)) {
                    return i;
                }
            }
            return FIRST_CREATE;
        }
        if (draft.kind == RecipeDraft.Kind.SEQUENCED_ASSEMBLY) {
            for (int i = FIRST_CREATE; i < COUNT; i++) {
                if (KIND[i] == RecipeDraft.Kind.SEQUENCED_ASSEMBLY) {
                    return i;
                }
            }
            return 0;
        }
        if (draft.kind == RecipeDraft.Kind.MECHANICAL_CRAFTING) {
            for (int i = FIRST_CREATE; i < COUNT; i++) {
                if (KIND[i] == RecipeDraft.Kind.MECHANICAL_CRAFTING) {
                    return i;
                }
            }
            return 0;
        }
        for (int i = 0; i < FIRST_CREATE; i++) {
            if (KIND[i] == draft.kind && (COOK[i] == null || COOK[i] == draft.cooking)) {
                return i;
            }
        }
        return 0;
    }
}
