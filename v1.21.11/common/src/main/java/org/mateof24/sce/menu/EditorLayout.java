package org.mateof24.sce.menu;

import org.mateof24.sce.core.edit.RecipeDraft;
import org.mateof24.sce.core.edit.RecipeModes;

/**
 * Where everything sits in the recipe editor, worked out from the recipe type rather than written down
 * per widget.
 *
 * <p>The panel is one fixed-size texture, so the work is dividing the space between the id row and the
 * player inventory among the rows a type actually uses. Types differ a lot: crafting shows a 3x3 grid and
 * a value row, Create adds an amount row under a grid whose shape is its own recipe's limits, mechanical
 * crafting is a 5x5 square, and cooking and stonecutting need a single slot. Fixed positions sized for
 * the busiest type left the sparse ones with a hole above the inventory and a grid pushed off-centre by a
 * column of fields that were not there.
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
    /** Space between the grid, the arrow and the result slots. */
    private static final int CLUSTER_GAP = 12;

    /**
     * Width a type with a side column takes for its xp and time fields, captions included. The captions
     * are drawn right-aligned against the fields, so the difference between the two is what they have
     * to fit in - enough for "Tiempo:" at the longest.
     */
    private static final int SIDE_RESERVE = 76;
    public static final int SIDE_FIELD_WIDTH = 42;
    /** What is left of that for the caption beside each field. */
    public static final int SIDE_LABEL_WIDTH = SIDE_RESERVE - SIDE_FIELD_WIDTH;

    public final int gridX;
    public final int gridY;
    public final int gridColumns;
    public final int gridRows;

    public final int outputX;
    public final int outputY;
    public final int outputColumns;

    /**
     * Row holding the value field and its buttons; every type has one.
     *
     * <p>It used to be two rows for Create — one for a tag, one for a fluid — which did the same job
     * (put something in the slot that was picked last), looked the same, and between them took the
     * sixteen pixels that mixing's nine-slot grid needs. One row, and what gets put in is decided by
     * what the picked slot accepts.
     */
    public final int tagRowY;

    /**
     * The row holding a type's own rule buttons, or -1 when it has none. Their captions are drawn in the
     * {@link #LABEL_LINE} directly above, which this row reserves.
     */
    public final int ruleRowY;
    /**
     * The row a type keeps for its own fields - Create's chance, time and heat - or -1 for the types
     * that need none.
     */
    public final int extraRowY;
    /**
     * Where that row's caption goes, or -1 when it has none.
     *
     * <p>A row holding one field and nothing else reads better with its name centred over it than with
     * the name beside it and half the panel empty to the right. A row holding several keeps its captions
     * beside the fields, because a column of captions over a row of boxes is harder to pair up.
     */
    public final int extraCaptionY;

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
        boolean sideColumn = RecipeModes.hasSideColumn(mode);
        boolean extraRow = RecipeModes.hasExtraRow(mode);
        boolean mechanical = RecipeModes.isMechanicalCrafting(mode);

        int inputs = RecipeModes.inputCount(mode);
        int outputs = RecipeModes.outputCount(mode);

        // Create's types bring their own shape, because their slot counts are their own recipe's limits
        // and range from one in and one out to eleven in and six out; everything else keeps the shape it
        // has always had.
        int columnsFromType = RecipeModes.inputColumns(mode);
        gridColumns = columnsFromType > 0 ? columnsFromType
                : (mechanical ? RecipeDraft.MECHANICAL_SIZE : (RecipeModes.usesGrid(mode) ? 3 : 1));
        gridRows = ceilDiv(inputs, gridColumns);
        int outputColumnsFromType = RecipeModes.outputColumns(mode);
        outputColumns = outputColumnsFromType > 0 ? outputColumnsFromType : 1;
        int outputRows = ceilDiv(outputs, outputColumns);

        // ---- vertical: one column of blocks, with the free space split evenly between them
        //
        // The blocks are the recipe, a type's own field row, its rule row and the value row, in that
        // order; a caption belongs to the block under it and is part of its height. Every gap is the
        // same size - including the one above the first block and the one under the last - which is the
        // whole point: the space over the grid used to be smaller than the space under the last row,
        // because that gap was capped and the recipe quietly absorbed the rest.
        int recipeHeight = Math.max(gridRows, outputRows) * SLOT;
        boolean extraCaption = extraRow && RecipeModes.extraRowCaptioned(mode);
        int extraHeight = extraRow ? ROW + (extraCaption ? LABEL_LINE : 0) : 0;
        int ruleHeight = ruleRow ? ROW + LABEL_LINE : 0;
        int blocks = 2 + (extraRow ? 1 : 0) + (ruleRow ? 1 : 0); // recipe and value row are always there
        int free = Math.max(0, (BAND_BOTTOM - BAND_TOP) - (recipeHeight + extraHeight + ruleHeight + ROW));
        int gap = free / blocks;
        // The pixels that do not divide evenly go to the topmost gaps, one each. At most three pixels
        // are ever shared out this way, so no two gaps differ by more than one.
        int spare = free % blocks;

        int y = BAND_TOP + gap + (spare-- > 0 ? 1 : 0);
        int recipeY = y;
        y += recipeHeight + gap + (spare-- > 0 ? 1 : 0);
        if (extraRow) {
            extraCaptionY = extraCaption ? y : -1;
            extraRowY = y + (extraCaption ? LABEL_LINE : 0);
            y += extraHeight + gap + (spare-- > 0 ? 1 : 0);
        } else {
            extraRowY = -1;
            extraCaptionY = -1;
        }
        if (ruleRow) {
            ruleRowY = y + LABEL_LINE;
            y += ruleHeight + gap + (spare-- > 0 ? 1 : 0);
        } else {
            ruleRowY = -1;
        }
        tagRowY = y;

        // A short grid and a single result slot both centre on the recipe row rather than hanging off its
        // top, which is what keeps the result beside the middle of a 5x5 or 3x3 grid.
        gridY = recipeY + (recipeHeight - gridRows * SLOT) / 2;
        outputY = recipeY + (recipeHeight - outputRows * SLOT) / 2;

        // ---- horizontal: centre everything the recipe row holds, as one group
        //
        // Everything: the grid, the arrow, the results, the campfire pot's seasoning column and the
        // xp/time column. The last of those used to be pinned to the right margin while the rest was
        // centred in what was left over, which put the grid a long way from the left edge and the
        // fields hard against the right one.
        seasoningCount = RecipeModes.seasoningSlots(mode);
        int clusterWidth = gridColumns * SLOT + CLUSTER_GAP + ARROW_WIDTH + CLUSTER_GAP + outputColumns * SLOT;
        int seasoningWidth = seasoningCount > 0 ? CLUSTER_GAP + seasoningCount * SLOT : 0;
        int sideWidth = sideColumn ? CLUSTER_GAP + SIDE_RESERVE : 0;
        int wholeWidth = clusterWidth + seasoningWidth + sideWidth;
        gridX = PADDING + Math.max(0, (WIDTH - 2 * PADDING - wholeWidth) / 2);
        outputX = gridX + gridColumns * SLOT + CLUSTER_GAP + ARROW_WIDTH + CLUSTER_GAP;

        if (sideColumn) {
            // Past the recipe and whatever sits beside it, with room in front of each field for its
            // caption, which is drawn right-aligned against the field.
            sideX = gridX + clusterWidth + seasoningWidth + CLUSTER_GAP + SIDE_LABEL_WIDTH;
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
}
