package org.mateof24.sce.core.edit;

import dev.architectury.platform.Platform;

import java.util.ArrayList;
import java.util.List;

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
                        boolean duration, boolean heat, Extras extras) {
        Mode {
            if (extras == null) {
                extras = Extras.NONE;
            }
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
            this(kind, cooking, createType, labelKey, inputs, outputs, requiredMod, 0, 0, 0, 0, false, false,
                    Extras.NONE);
        }
    }

    /**
     * A number a recipe type asks for that is neither a count nor a time this editor already models — an
     * uncrafting table's level cost, a drying rack's tick count, a scepter's repair value.
     *
     * <p>Held as data rather than as a field per type: each of them is a labelled box on the row under the
     * recipe that reads an integer and writes it back under {@code key}, and the only thing that differs
     * between them is the name, the range and what a recipe that leaves it out means. A type added later
     * is a row in the table below rather than a field, a widget, a label and four branches.
     */
    public record Number(String key, String labelKey, int min, int max, int fallback) {
    }

    /**
     * A choice a recipe type offers, drawn as a button that cycles through {@code options}. The option is
     * written back under {@code key} as the string it is; the button's face is {@code labelPrefix} plus
     * that string, which is how the same row serves a tab name and anything added later.
     */
    public record Choice(String key, String labelPrefix, List<String> options) {
        public String fallback() {
            return options.get(options.size() - 1);
        }

        /** The option after {@code current}, wrapping; a negative {@code by} walks back. */
        public String step(String current, int by) {
            int at = Math.max(0, options.indexOf(current));
            int size = options.size();
            return options.get(((at + by) % size + size) % size);
        }
    }

    /**
     * Everything a type needs beyond its slots. Bundled because most types need none of it: adding five
     * more components to {@link Mode} would make every row spell out values it does not use, which is
     * exactly the kind of table that falls out of step with itself.
     */
    private static final class Extras {
        static final String[] NO_TIPS = new String[0];
        static final Extras NONE = new Extras();

        private final List<Number> numbers = new ArrayList<>();
        private final List<Choice> choices = new ArrayList<>();
        private boolean sideColumn;
        private boolean chance;
        private String[] inputTips = NO_TIPS;
        private String[] outputTips = NO_TIPS;

        Extras number(String key, String labelKey, int min, int max, int fallback) {
            numbers.add(new Number(key, labelKey, min, max, fallback));
            return this;
        }

        Extras choice(String key, String labelPrefix, String... options) {
            choices.add(new Choice(key, labelPrefix, List.of(options)));
            return this;
        }

        /** The xp/time column the cooking types keep on the right. */
        Extras sideColumn() {
            sideColumn = true;
            return this;
        }

        /** A drop chance per result, the way Create's processing types have one. */
        Extras chance() {
            chance = true;
            return this;
        }

        Extras inputTips(String... tips) {
            inputTips = tips;
            return this;
        }

        Extras outputTips(String... tips) {
            outputTips = tips;
            return this;
        }
    }

    private static Extras extras() {
        return new Extras();
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
                    "sce.mode.cobblemon_cooking_pot", 9, 1, CookingPot.MOD_ID),

            // ---- other mods' workbenches. Ordinary datapack recipes with a shape of their own, so the
            // engine already carries them; what was missing was a mode that knows which fields each one
            // has. Every row is gated on its mod by the type's namespace, the same way Create's are.

            // Farmer's Delight's cutting board: one thing on the board, the tool that cuts it, and up to
            // four results, each with a chance. Both of those limits are the serializer's own - over them
            // it refuses the recipe with "Too many results for cutting recipe".
            mod(RecipeDraft.Kind.CUTTING_BOARD, "farmersdelight:cutting", "sce.mode.fd_cutting",
                    2, 1, 4, 2, extras()
                            .chance()
                            .inputTips("sce.slot.cutting_input", "sce.slot.cutting_tool")),

            // Farmer's Delight's cooking pot: up to six ingredients (the pot has six slots), the meal,
            // and the bowl or bottle it is served in - which is a second result rather than a field,
            // because that is what it is: an item the pot hands back along with the food.
            mod(RecipeDraft.Kind.FD_COOKING, "farmersdelight:cooking", "sce.mode.fd_cooking",
                    6, 3, 2, 1, extras()
                            .sideColumn()
                            .choice("recipe_book_tab", "sce.tab.", "meals", "drinks", "misc")
                            .outputTips("sce.slot.meal", "sce.slot.container")),

            // Twilight Forest's uncrafting table: a shaped recipe read backwards. One item goes in and a
            // whole grid comes out, so the pattern and its key sit on the result side; the cost is in
            // experience levels and the count is how many of the input one craft consumes.
            mod(RecipeDraft.Kind.UNCRAFTING, "twilightforest:uncrafting", "sce.mode.tf_uncrafting",
                    1, 1, 9, 3, extras()
                            .number("cost", "sce.label.uncraft_cost", -1, 9999, 0)
                            .number("input_count", "sce.label.uncraft_count", 1, 64, 1)
                            .inputTips("sce.slot.uncraft_input")),

            // Twilight Forest's drying rack: one item, one result, and how long it hangs there in ticks.
            mod(RecipeDraft.Kind.DRYING, "twilightforest:drying", "sce.mode.tf_drying",
                    1, 1, 1, 1, extras()
                            .number("filter_time", "sce.label.dry_time", 1, 1000000, 40)),

            // Twilight Forest's scepter repair: the ingredients that recharge a scepter, and how much
            // each craft gives back. The scepter itself is the result slot - it is both what comes out
            // and, in the file, the single item the recipe names.
            mod(RecipeDraft.Kind.SCEPTER_REPAIR, "twilightforest:scepter_repair", "sce.mode.tf_scepter",
                    4, 4, 1, 1, extras()
                            .number("durability", "sce.label.repair_durability", 1, 100000, 9)
                            .inputTips("sce.slot.repair_ingredient", "sce.slot.repair_ingredient",
                                    "sce.slot.repair_ingredient", "sce.slot.repair_ingredient")
                            .outputTips("sce.slot.scepter")),

            // Cobblemon's brewing stand: the bottle in the lower row and the thing that brews into it.
            mod(RecipeDraft.Kind.BREWING_STAND, "cobblemon:brewing_stand", "sce.mode.cobblemon_brewing",
                    2, 2, 1, 1, extras()
                            .inputTips("sce.slot.bottle", "sce.slot.brew_input"))};

    public static final int COUNT = MODES.length;
    private static final int FIRST_CREATE = 8;

    private RecipeModes() {
    }

    private static Mode create(String createType, String labelKey, int itemsIn, int fluidsIn, int inColumns,
                               int itemsOut, int fluidsOut, int outColumns, boolean duration, boolean heat) {
        return new Mode(RecipeDraft.Kind.CREATE_PROCESSING, null, createType, labelKey,
                itemsIn + fluidsIn, itemsOut + fluidsOut, null, fluidsIn, fluidsOut, inColumns, outColumns,
                duration, heat, Extras.NONE);
    }

    /**
     * A row for another mod's workbench: a fixed shape, no fluids, and whatever fields the type asks for.
     * Its mod is read from the type's namespace by the constructor, so the gate cannot drift from the id.
     */
    private static Mode mod(RecipeDraft.Kind kind, String type, String labelKey, int inputs,
                            int inputColumns, int outputs, int outputColumns, Extras extras) {
        return new Mode(kind, null, type, labelKey, inputs, outputs, null, 0, 0,
                inputColumns, outputColumns, false, false, extras);
    }

    /** The recipe type a mode writes, for the modes that name one. Null for the plain vanilla shapes. */
    public static String typeOf(int mode) {
        return MODES[clamp(mode)].createType();
    }

    /** The numbers this type asks for on the row under the recipe; empty for most of them. */
    public static List<Number> numbers(int mode) {
        return MODES[clamp(mode)].extras().numbers;
    }

    /** The choices this type offers on that same row. */
    public static List<Choice> choices(int mode) {
        return MODES[clamp(mode)].extras().choices;
    }

    /** What a type's input slot is for, as a translation key, or null when the slot needs no explaining. */
    public static String inputTip(int mode, int index) {
        String[] tips = MODES[clamp(mode)].extras().inputTips;
        return index >= 0 && index < tips.length ? tips[index] : null;
    }

    /** The same for a result slot. */
    public static String outputTip(int mode, int index) {
        String[] tips = MODES[clamp(mode)].extras().outputTips;
        return index >= 0 && index < tips.length ? tips[index] : null;
    }

    /**
     * Whether a type has a row of its own between the recipe and the value row. Create's types always do
     * (chance, duration, heat); the others do when they ask for a number or offer a choice.
     */
    public static boolean hasExtraRow(int mode) {
        Mode row = MODES[clamp(mode)];
        return row.kind() == RecipeDraft.Kind.CREATE_PROCESSING
                || row.extras().chance || !row.extras().numbers.isEmpty() || !row.extras().choices.isEmpty();
    }

    /**
     * How many text fields a type's own row holds: the drop chance, the duration Create allows on some
     * of its machines, and whatever numbers the type asks for.
     */
    public static int extraFieldCount(int mode) {
        return (hasChance(mode) ? 1 : 0) + (allowsDuration(mode) ? 1 : 0) + numbers(mode).size();
    }

    /** How many buttons it holds: Create's heat and held-item flags, and whatever choices it offers. */
    public static int extraButtonCount(int mode) {
        return (allowsHeat(mode) ? 1 : 0) + (hasKeepHeldItem(mode) ? 1 : 0) + choices(mode).size();
    }

    /**
     * Whether that row is one field and nothing else, in which case its caption goes centred above it
     * rather than beside it.
     *
     * <p>A lone field with its name to the left sits in the corner of an otherwise empty row, which
     * reads as something unfinished. Centred under its own name it reads as what it is. A row with more
     * than one field keeps its captions beside them: a line of names over a line of boxes is harder to
     * pair up than a name and its box side by side.
     */
    public static boolean extraRowCaptioned(int mode) {
        return extraFieldCount(mode) == 1 && extraButtonCount(mode) == 0;
    }

    /** Whether the type keeps an experience and time column down the right-hand side. */
    public static boolean hasSideColumn(int mode) {
        return isCooking(mode) || MODES[clamp(mode)].extras().sideColumn;
    }

    /** Whether each result carries a drop chance. Create's processing types do, and the cutting board. */
    public static boolean hasChance(int mode) {
        return isCreate(mode) || MODES[clamp(mode)].extras().chance;
    }

    /**
     * Whether the type writes its results as a list with a count and a chance each, the way Create's
     * machines do. The cutting board does the same thing under its own key.
     */
    public static boolean usesResultList(int mode) {
        RecipeDraft.Kind kind = MODES[clamp(mode)].kind();
        return kind == RecipeDraft.Kind.CREATE_PROCESSING || kind == RecipeDraft.Kind.CUTTING_BOARD;
    }

    /**
     * Whether the result side is a pattern rather than a list: the uncrafting table, which is a shaped
     * recipe read backwards and so writes its grid where every other type writes a result.
     */
    public static boolean usesOutputGrid(int mode) {
        return MODES[clamp(mode)].kind() == RecipeDraft.Kind.UNCRAFTING;
    }

    /** The cooking time a new recipe starts at, rather than the zero that crafts but animates nothing. */
    public static int defaultCookingTime(int mode) {
        RecipeDraft.Cooking cooking = MODES[clamp(mode)].cooking();
        return cooking != null ? cooking.defaultTime : 200;
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
