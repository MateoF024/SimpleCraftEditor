package org.mateof24.sce.menu;

import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.RecipeModes;

/**
 * Where everything sits in the recipe editor, worked out from the recipe type rather than written down
 * per widget.
 *
 * <p>The panel is one fixed-size texture, so the work is dividing the space between the id row and the
 * player inventory among the rows a type actually uses. Types differ a lot: crafting shows a 3x3 grid and
 * a tag row, Create adds an amount row and a fluid row under a 3x2 grid, mechanical crafting is a 5x5
 * square, and cooking and stonecutting need a single slot. Fixed positions sized for the busiest type
 * left the sparse ones with a hole above the inventory and a grid pushed off-centre by a column of fields
 * that were not there.
 *
 * <p>Two rules produce the layout. Vertically, the rows under the recipe hang from the bottom of the free
 * band and the recipe cluster is centred in what is left between the id row and the first of them. Hanging them from the bottom is what
 * keeps the tag row in one place: what changes between a smelting recipe and a shaped one is the recipe
 * above it, not the row itself, and a row that jumps around because the thing above it got shorter reads
 * as a different screen rather than the same one. Horizontally, the grid/arrow/result cluster is centred
 * in whatever width is left once a side column is reserved — which means it is centred on the panel
 * exactly when nothing sits beside it.
 *
 * <p>Both the menu and the screen build one of these: slot positions are decided server-side while the
 * widgets around them are client-side, and they have to agree.
 */
public final class EditorLayout {
    public static final int WIDTH = 240;
    public static final int HEIGHT = 266;

    public static final int PADDING = 8;
    public static final int SLOT = 18;
    /** Height of a row of text boxes and buttons. */
    public static final int ROW = 16;
    /** Height a caption above a row takes: one line of text and a pixel of air under it. */
    public static final int LABEL_LINE = 10;
    public static final int ARROW_WIDTH = 22;

    public static final int INVENTORY_Y = 158;
    public static final int HOTBAR_Y = 218;
    public static final int BUTTON_ROW_Y = 238;

    /** The recipe id and its Load button, the one row above the band that never moves. */
    public static final int ID_ROW_Y = 22;
    /** Free band between the id row and the player inventory, which every type divides up. */
    private static final int BAND_TOP = ID_ROW_Y + ROW;
    private static final int BAND_BOTTOM = 152;
    /** Gap between rows: wide enough to read as separate, tight enough that Create's four rows fit. */
    private static final int MIN_ROW_GAP = 6;
    private static final int MAX_ROW_GAP = 16;
    /** Space between the grid, the arrow and the result slots. */
    private static final int CLUSTER_GAP = 12;

    /** Width the cooking types reserve on the right for the xp and time fields, labels included. */
    private static final int COOKING_RESERVE = 68;
    public static final int SIDE_FIELD_WIDTH = 42;

    public final int gridX;
    public final int gridY;
    public final int gridColumns;
    public final int gridRows;

    public final int outputX;
    public final int outputY;
    public final int outputColumns;

    /** Row holding the tag field and its buttons; every type has one. */
    public final int tagRowY;

    /**
     * The row holding a type's own rule buttons, or -1 when it has none. Their captions are drawn in the
     * {@link #LABEL_LINE} directly above, which this row reserves.
     */
    public final int ruleRowY;
    /** Create's chance/time/heat row, or -1 for types without one. */
    public final int extraRowY;
    /** Create's fluid row, or -1 for types that take no fluids. */
    public final int fluidRowY;

    /** Left edge of the cooking xp/time column, or -1 when the type has no side column. */
    public final int sideX;
    public final int expY;
    public final int sideTimeY;
    /** Mechanical crafting's mirrored toggle, or -1 for every other type. */
    public final int mirroredY;

    /**
     * The row of seasoning slots, or -1 for a type that has none: beside the result and level with it,
     * where the campfire pot itself puts them, so the editor reads as the block it is editing. Its
     * caption sits in the {@link #LABEL_LINE} above and its button in the row below.
     */
    public final int seasoningX;
    public final int seasoningY;
    public final int seasoningCount;

    /** Row under the seasoning slots, holding the button that opens their editor. */
    public int seasoningButtonY() {
        return seasoningY + SLOT + 4;
    }

    public EditorLayout(int mode) {
        boolean ruleRow = RecipeModes.hasRuleRow(mode);
        boolean cooking = RecipeModes.isCooking(mode);
        boolean create = RecipeModes.isCreate(mode);
        boolean mechanical = RecipeModes.isMechanicalCrafting(mode);

        int inputs = RecipeModes.inputCount(mode);
        int outputs = RecipeModes.outputCount(mode);

        gridColumns = mechanical ? RecipeDraft.MECHANICAL_SIZE : (RecipeModes.usesGrid(mode) ? 3 : 1);
        gridRows = ceilDiv(inputs, gridColumns);
        // Create is the only type with enough results to want a second column of them.
        outputColumns = create ? 2 : 1;
        int outputRows = ceilDiv(outputs, outputColumns);

        // ---- vertical: hang the rows from the bottom of the band, then centre the recipe above them
        int recipeHeight = Math.max(gridRows, outputRows) * SLOT;
        // Rows between the recipe and the tag row: Create's amount and fluid rows, or a type's own rule
        // row, which is taller than a plain one because it carries a caption.
        int middleRows = (create ? 2 : 0) + (ruleRow ? 1 : 0);
        int middleHeight = (create ? 2 * ROW : 0) + (ruleRow ? ROW + LABEL_LINE : 0);
        int stacked = recipeHeight + middleHeight + ROW;
        int band = BAND_BOTTOM - BAND_TOP;
        int gap = clamp((band - stacked) / (middleRows + 1), MIN_ROW_GAP, MAX_ROW_GAP);

        // Placed from the bottom up: the tag row's home is the foot of the band for every type alike.
        tagRowY = BAND_BOTTOM - ROW;
        int cursor = tagRowY;
        if (ruleRow) {
            ruleRowY = cursor - gap - ROW;
            cursor = ruleRowY - LABEL_LINE;
        } else {
            ruleRowY = -1;
        }
        if (create) {
            fluidRowY = cursor - gap - ROW;
            extraRowY = fluidRowY - gap - ROW;
            cursor = extraRowY;
        } else {
            extraRowY = -1;
            fluidRowY = -1;
        }
        // Centred between the id row above and the first thing below, measured to the pixel each of them
        // actually paints: a caption is a line of text with a pixel of air under it, not a full row, and
        // centring against the row would leave the recipe visibly closer to the id field than to what
        // follows it.
        int belowTop = ruleRow ? ruleRowY - LABEL_LINE + 1 : (create ? extraRowY : tagRowY);
        int recipeY = BAND_TOP + Math.max(0, (belowTop - BAND_TOP - recipeHeight) / 2);

        // A short grid and a single result slot both centre on the recipe row rather than hanging off its
        // top, which is what keeps the result beside the middle of a 5x5 or 3x3 grid.
        gridY = recipeY + (recipeHeight - gridRows * SLOT) / 2;
        outputY = recipeY + (recipeHeight - outputRows * SLOT) / 2;

        // ---- horizontal: centre grid → arrow → result in the width left over
        seasoningCount = RecipeModes.seasoningSlots(mode);
        int seasoningWidth = seasoningCount * SLOT;
        int bandWidth = WIDTH - 2 * PADDING - (cooking ? COOKING_RESERVE : 0);
        // A type with no result of its own — a smithing trim only decorates what it is given — is its
        // ingredients and nothing else, so neither the arrow nor a result slot takes up room.
        int clusterWidth = outputs > 0
                ? gridColumns * SLOT + CLUSTER_GAP + ARROW_WIDTH + CLUSTER_GAP + outputColumns * SLOT
                : gridColumns * SLOT;
        // The seasoning column is part of the recipe, not something parked beside it, so what gets
        // centred is the two of them together.
        int wholeWidth = clusterWidth + (seasoningCount > 0 ? CLUSTER_GAP + seasoningWidth : 0);
        gridX = PADDING + Math.max(0, (bandWidth - wholeWidth) / 2);
        outputX = gridX + gridColumns * SLOT + CLUSTER_GAP + ARROW_WIDTH + CLUSTER_GAP;

        if (cooking) {
            sideX = WIDTH - PADDING - SIDE_FIELD_WIDTH;
            int centre = recipeY + recipeHeight / 2;
            expY = centre - 20;
            sideTimeY = centre + 4;
        } else {
            sideX = -1;
            expY = -1;
            sideTimeY = -1;
        }
        if (seasoningCount > 0) {
            // Level with the result: what a player adds there lands on the result, not on the grid.
            seasoningX = gridX + clusterWidth + CLUSTER_GAP;
            seasoningY = outputY;
        } else {
            seasoningX = -1;
            seasoningY = -1;
        }

        // The toggle goes under the result slot, in the space a 5x5 grid leaves to its right.
        mirroredY = mechanical ? outputY + 40 : -1;
    }

    /** Left edge of an input slot, laid out row-major across {@link #gridColumns}. */
    public int inputSlotX(int index) {
        return gridX + (index % gridColumns) * SLOT;
    }

    public int inputSlotY(int index) {
        return gridY + (index / gridColumns) * SLOT;
    }

    public int outputSlotX(int index) {
        return outputX + (index % outputColumns) * SLOT;
    }

    public int outputSlotY(int index) {
        return outputY + (index / outputColumns) * SLOT;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
