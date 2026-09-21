package org.mateof24.sce.core.edit;

import dev.architectury.platform.Platform;

/**
 * The selectable recipe types in the editor and the slot layout each one uses. Shared by the menu (which
 * builds the right slots server-side) and the screen (which labels the type button and compiles the draft).
 * A type belonging to a mod — Create's machines, Cobblemon's campfire pot — is only offered when that mod
 * is installed.
 *
 * <p>Only types something can actually craft are listed. Create registers two more — {@code create:basin}
 * and {@code create:conversion} — that look like recipe types but are not: {@code BasinRecipe} is the
 * shared parent of mixing and compacting rather than a recipe any machine looks up, and
 * {@code ConversionRecipe} lives in Create's JEI plugin and is built in code to illustrate the chromatic
 * compound, never read from a datapack. Create's own JEI integration registers a category for neither, so
 * a recipe authored as either would load, craft nothing, and never appear in a recipe viewer.
 *
 * <p>Labels use the names Create's own JEI categories display, so what the editor calls a type matches
 * what a player reads in the recipe viewer. Several differ from the id: {@code create:cutting} is Sawing,
 * {@code create:splashing} is Bulk Washing, {@code create:emptying} is Item Draining, and
 * {@code create:sequenced_assembly} is Recipe Sequence.
 */
public final class RecipeModes {
    /**
     * One selectable type. Held as a table of rows rather than as parallel arrays: a row cannot fall out
     * of step with itself, which the six arrays this replaced could and did whenever a type was added.
     */
    private record Mode(RecipeDraft.Kind kind, RecipeDraft.Cooking cooking, String createType,
                        String labelKey, int inputs, int outputs, String requiredMod,
                        int fluidInputs, int fluidOutputs, int inputColumns, int outputColumns,
                        boolean duration, boolean heat) {
        Mode {
            // A row that names another mod's recipe type needs that mod, and the type already says which
            // one: create:mixing is Create's. Taking it from there rather than writing it a second time
            // per row is what keeps the two from disagreeing — they did, and mechanical crafting and
            // sequenced assembly were offered on a game with no Create in it.
            if (requiredMod == null && createType != null) {
                int colon = createType.indexOf(':');
                requiredMod = colon > 0 ? createType.substring(0, colon) : null;
            }
        }

        /** A row that needs nothing installed, or one that names a type and takes its gate from it. */
        Mode(RecipeDraft.Kind kind, RecipeDraft.Cooking cooking, String createType, String labelKey,
             int inputs, int outputs) {
            this(kind, cooking, createType, labelKey, inputs, outputs, null);
        }

        /** The same, for a row whose shape the layout works out for itself. */
        Mode(RecipeDraft.Kind kind, RecipeDraft.Cooking cooking, String createType, String labelKey,
             int inputs, int outputs, String requiredMod) {
            this(kind, cooking, createType, labelKey, inputs, outputs, requiredMod, 0, 0, 0, 0, false, false);
        }
    }

    private static final int MECHANICAL_SLOTS = RecipeDraft.MECHANICAL_SIZE * RecipeDraft.MECHANICAL_SIZE;

    private static final Mode[] MODES = {
            new Mode(RecipeDraft.Kind.CRAFTING_SHAPELESS, null, null, "sce.mode.shapeless", 9, 1),
            new Mode(RecipeDraft.Kind.CRAFTING_SHAPED, null, null, "sce.mode.shaped", 9, 1),
            new Mode(RecipeDraft.Kind.COOKING, RecipeDraft.Cooking.SMELTING, null, "sce.mode.smelting", 1, 1),
            new Mode(RecipeDraft.Kind.COOKING, RecipeDraft.Cooking.BLASTING, null, "sce.mode.blasting", 1, 1),
            new Mode(RecipeDraft.Kind.COOKING, RecipeDraft.Cooking.SMOKING, null, "sce.mode.smoking", 1, 1),
            new Mode(RecipeDraft.Kind.COOKING, RecipeDraft.Cooking.CAMPFIRE, null, "sce.mode.campfire", 1, 1),
            new Mode(RecipeDraft.Kind.STONECUTTING, null, null, "sce.mode.stonecutting", 1, 1),

            // The smithing table: template, base and addition in a row, and what they turn into.
            //
            // Only the upgrade. The table's other recipe puts a trim on a piece, and there the addition
            // is not a free choice: it has to be one of the trim materials, because that is the thing
            // that carries the colour — anything else and the table simply refuses to craft. A recipe
            // whose only editable part is one of eleven fixed items is not worth an editor.
            new Mode(RecipeDraft.Kind.SMITHING_TRANSFORM, null, null, "sce.mode.smithing_transform", 3, 1),

            // Create's processing machines. All share the ingredient/result layout.
            //     create(type, label, items in, fluids in, in columns,
            //                         items out, fluids out, out columns, duration, heat)
            //
            // These are not a design choice, they are Create's own limits: ProcessingRecipe.validate()
            // counts each of them against a maximum its recipe class declares, and over the limit the
            // recipe does not merely misbehave, it fails to load. Mixing and compacting are BasinRecipe,
            // whose codec would take 64 ingredients; the basin itself is built with nine slots and two
            // tanks, and nine is what Create's own ice recipe uses.
            create("create:mixing", "sce.mode.create_mixing", 9, 2, 4, 4, 2, 3, true, true),
            create("create:crushing", "sce.mode.create_crushing", 1, 0, 1, 7, 0, 4, true, false),
            create("create:milling", "sce.mode.create_milling", 1, 0, 1, 4, 0, 2, true, false),
            create("create:pressing", "sce.mode.create_pressing", 1, 0, 1, 2, 0, 2, false, false),
            create("create:compacting", "sce.mode.create_compacting", 9, 2, 4, 4, 2, 3, true, true),
            create("create:cutting", "sce.mode.create_cutting", 1, 0, 1, 4, 0, 2, true, false),
            create("create:splashing", "sce.mode.create_splashing", 1, 0, 1, 12, 0, 4, false, false),
            create("create:haunting", "sce.mode.create_haunting", 1, 0, 1, 12, 0, 4, false, false),
            create("create:sandpaper_polishing", "sce.mode.create_sandpaper", 1, 0, 1, 1, 0, 1, false, false),
            // The deployer and a player's own hand share ItemApplicationRecipe: the thing being worked
            // on and the thing applied to it, and nothing else.
            create("create:deploying", "sce.mode.create_deploying", 2, 0, 1, 4, 0, 2, false, false),
            create("create:filling", "sce.mode.create_filling", 1, 1, 1, 1, 0, 1, false, false),
            create("create:emptying", "sce.mode.create_emptying", 1, 0, 1, 1, 1, 1, false, false),
            create("create:item_application", "sce.mode.create_item_application", 2, 0, 1, 4, 0, 2, false, false),

            new Mode(RecipeDraft.Kind.MECHANICAL_CRAFTING, null, "create:mechanical_crafting",
                    "sce.mode.create_mechanical_crafting", MECHANICAL_SLOTS, 1),
            // Sequenced assembly is edited on its own screen, not on the slot grid.
            new Mode(RecipeDraft.Kind.SEQUENCED_ASSEMBLY, null, SequencedAssemblyCompiler.TYPE,
                    "sce.mode.create_sequenced_assembly", 1, 1),

            // Cobblemon's campfire pot. Both types are an ordinary grid with the pot's own fields around
            // it, so they reuse the shaped and shapeless layout and differ only in what gets written.
            new Mode(RecipeDraft.Kind.COOKING_POT_SHAPELESS, null, null,
                    "sce.mode.cobblemon_cooking_pot_shapeless", 9, 1, CookingPot.MOD_ID),
            new Mode(RecipeDraft.Kind.COOKING_POT, null, null,
                    "sce.mode.cobblemon_cooking_pot", 9, 1, CookingPot.MOD_ID)};

    public static final int COUNT = MODES.length;
    private static final int FIRST_CREATE = 8;

    private RecipeModes() {
    }

    private static Mode create(String createType, String labelKey, int itemsIn, int fluidsIn, int inColumns,
                               int itemsOut, int fluidsOut, int outColumns, boolean duration, boolean heat) {
        return new Mode(RecipeDraft.Kind.CREATE_PROCESSING, null, createType, labelKey,
                itemsIn + fluidsIn, itemsOut + fluidsOut, null, fluidsIn, fluidsOut, inColumns, outColumns,
                duration, heat);
    }

    /**
     * How many of a type's input slots take an item. The fluid slots follow them, so slot {@code i} is a
     * fluid slot exactly when {@code i >= itemInputs(mode)} — which is what lets the menu refuse an item
     * there and the screen draw it as a tank.
     */
    public static int itemInputs(int mode) {
        Mode row = MODES[clamp(mode)];
        return row.inputs() - row.fluidInputs();
    }

    /** The same split on the result side. */
    public static int itemOutputs(int mode) {
        Mode row = MODES[clamp(mode)];
        return row.outputs() - row.fluidOutputs();
    }

    public static boolean isFluidInput(int mode, int index) {
        return index >= itemInputs(mode) && index < inputCount(mode);
    }

    public static boolean isFluidOutput(int mode, int index) {
        return index >= itemOutputs(mode) && index < outputCount(mode);
    }

    /** Whether the type takes fluids at all, which is what puts an amount field on the value row. */
    public static boolean usesFluids(int mode) {
        Mode row = MODES[clamp(mode)];
        return row.fluidInputs() > 0 || row.fluidOutputs() > 0;
    }

    /**
     * Whether a processing time means anything for this type. Create refuses a recipe that names one it
     * cannot use — only crushing, milling, sawing, mixing and compacting can — so the field is not shown
     * for the rest rather than shown and then rejected.
     */
    public static boolean allowsDuration(int mode) {
        return MODES[clamp(mode)].duration();
    }

    /** Whether a heat requirement means anything: the basin types, and only those. */
    public static boolean allowsHeat(int mode) {
        return MODES[clamp(mode)].heat();
    }

    /**
     * Whether the type has Create's "keep held item" flag — the deployer not consuming what it is
     * holding, which is how every waxing and de-oxidising recipe in the game works. 165 of Create's own
     * recipes set it.
     */
    public static boolean hasKeepHeldItem(int mode) {
        String type = MODES[clamp(mode)].createType();
        return "create:deploying".equals(type) || "create:item_application".equals(type);
    }

    /** Columns the input grid is laid out in; 0 for the types whose shape the layout decides itself. */
    public static int inputColumns(int mode) {
        return MODES[clamp(mode)].inputColumns();
    }

    /** Columns the result slots are laid out in; 0 means "let the layout decide". */
    public static int outputColumns(int mode) {
        return MODES[clamp(mode)].outputColumns();
    }

    /**
     * The most input slots any type asks for, so the menu's backing container is built once and fits
     * them all. Read from the table rather than written down, because a type added with more slots than
     * the container holds would drop the surplus silently.
     */
    public static int maxInputCount() {
        int most = 0;
        for (Mode row : MODES) {
            most = Math.max(most, row.inputs());
        }
        return most;
    }

    /** The same for results — Bulk Washing and Bulk Haunting each allow twelve. */
    public static int maxOutputCount() {
        int most = 0;
        for (Mode row : MODES) {
            most = Math.max(most, row.outputs());
        }
        return most;
    }

    public static RecipeDraft.Kind kind(int mode) {
        return MODES[clamp(mode)].kind();
    }

    public static RecipeDraft.Cooking cooking(int mode) {
        RecipeDraft.Cooking cooking = MODES[clamp(mode)].cooking();
        return cooking != null ? cooking : RecipeDraft.Cooking.SMELTING;
    }

    /** Translation key for a mode's display name (e.g. {@code sce.mode.shapeless}). */
    public static String labelKey(int mode) {
        return MODES[clamp(mode)].labelKey();
    }

    public static String createType(int mode) {
        return MODES[clamp(mode)].createType();
    }

    public static boolean isCrafting(int mode) {
        RecipeDraft.Kind kind = MODES[clamp(mode)].kind();
        return kind == RecipeDraft.Kind.CRAFTING_SHAPED || kind == RecipeDraft.Kind.CRAFTING_SHAPELESS;
    }

    public static boolean isCooking(int mode) {
        return MODES[clamp(mode)].kind() == RecipeDraft.Kind.COOKING;
    }

    public static boolean isCreate(int mode) {
        return MODES[clamp(mode)].kind() == RecipeDraft.Kind.CREATE_PROCESSING;
    }

    /** Create's mechanical crafter: a shaped recipe on a grid bigger than the vanilla 3x3. */
    public static boolean isMechanicalCrafting(int mode) {
        return MODES[clamp(mode)].kind() == RecipeDraft.Kind.MECHANICAL_CRAFTING;
    }

    /**
     * The mode a recipe written from nothing starts in: a shapeless crafting recipe, which is the one
     * shape every other can be reached from without losing what has been filled in.
     */
    public static int shapelessMode() {
        for (int i = 0; i < COUNT; i++) {
            if (MODES[i].kind() == RecipeDraft.Kind.CRAFTING_SHAPELESS) {
                return i;
            }
        }
        return 0;
    }

    /** Cobblemon's campfire pot: a crafting grid with the pot's own fields written around it. */
    public static boolean isCookingPot(int mode) {
        RecipeDraft.Kind kind = MODES[clamp(mode)].kind();
        return kind == RecipeDraft.Kind.COOKING_POT || kind == RecipeDraft.Kind.COOKING_POT_SHAPELESS;
    }

    /**
     * Slots a type shows beside the recipe for something the recipe does not name item by item — the
     * campfire pot's seasoning, whose contents the player chooses and whose rules the recipe sets.
     */
    public static int seasoningSlots(int mode) {
        return isCookingPot(mode) ? CookingPot.SEASONING_SLOTS : 0;
    }

    /** Whether a mode is drawn on a three-wide grid rather than a single ingredient slot. */
    public static boolean usesGrid(int mode) {
        return isCrafting(mode) || isCreate(mode) || isCookingPot(mode) || isSmithing(mode);
    }

    /** The smithing table's upgrade recipe. */
    public static boolean isSmithing(int mode) {
        return MODES[clamp(mode)].kind() == RecipeDraft.Kind.SMITHING_TRANSFORM;
    }


    /** Whether a mode has a row of its own rules above the tag row, with a caption over it. */
    public static boolean hasRuleRow(int mode) {
        return isCrafting(mode) || isCookingPot(mode);
    }

    /** Sequenced assembly is edited on a dedicated screen rather than the shared slot layout. */
    public static boolean isSequencedAssembly(int mode) {
        return MODES[clamp(mode)].kind() == RecipeDraft.Kind.SEQUENCED_ASSEMBLY;
    }

    /**
     * Whether a Create recipe type has an editor mode. Types without one must not be parsed into a draft:
     * they would load as the wrong mode and lose the fields this editor does not model, so they go to the
     * raw JSON editor instead.
     */
    public static boolean hasCreateType(String createType) {
        for (Mode mode : MODES) {
            if (createType.equals(mode.createType())) {
                return true;
            }
        }
        return false;
    }

    public static int inputCount(int mode) {
        return MODES[clamp(mode)].inputs();
    }

    public static int outputCount(int mode) {
        return MODES[clamp(mode)].outputs();
    }

    public static int clamp(int mode) {
        return ((mode % COUNT) + COUNT) % COUNT;
    }

    public static boolean createLoaded() {
        return Platform.isModLoaded("create");
    }

    /** Whether a mode can be used right now: a type belonging to a mod needs that mod installed. */
    public static boolean available(int mode) {
        String required = MODES[clamp(mode)].requiredMod();
        return required == null || Platform.isModLoaded(required);
    }

    /** Next selectable mode, skipping the types whose mod is not installed. */
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
                if (MODES[i].createType() != null && MODES[i].createType().equals(draft.createType)) {
                    return i;
                }
            }
            return FIRST_CREATE;
        }
        for (int i = 0; i < COUNT; i++) {
            if (MODES[i].kind() == draft.kind
                    && (MODES[i].cooking() == null || MODES[i].cooking() == draft.cooking)) {
                return i;
            }
        }
        return 0;
    }
}
