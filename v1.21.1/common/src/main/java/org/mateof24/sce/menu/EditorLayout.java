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
 * <p>Two rules produce the layout. Vertically, the rows in use are stacked with an even gap and the whole
 * block is centred in the free band, so a type with fewer rows sits balanced instead of hugging the top.
 * Horizontally, the grid/arrow/result cluster is centred in whatever width is left once a side column is
 * reserved — which means it is centred on the panel exactly when nothing sits beside it.
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
    public static final int ARROW_WIDTH = 22;

    public static final int INVENTORY_Y = 158;
    public static final int HOTBAR_Y = 218;
    public static final int BUTTON_ROW_Y = 238;

    /** Free band between the id row and the player inventory, which every type divides up. */
    private static final int BAND_TOP = 42;
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

    public EditorLayout(int mode) {
        boolean crafting = RecipeModes.isCrafting(mode);
        boolean cooking = RecipeModes.isCooking(mode);
        boolean create = RecipeModes.isCreate(mode);
        boolean mechanical = RecipeModes.isMechanicalCrafting(mode);

        int inputs = RecipeModes.inputCount(mode);
        int outputs = RecipeModes.outputCount(mode);

        gridColumns = mechanical ? RecipeDraft.MECHANICAL_SIZE : ((crafting || create) ? 3 : 1);
        gridRows = ceilDiv(inputs, gridColumns);
        // Create is the only type with enough results to want a second column of them.
        outputColumns = create ? 2 : 1;
        int outputRows = ceilDiv(outputs, outputColumns);

        // ---- vertical: stack the rows this type uses, then centre the block in the free band
        int recipeHeight = Math.max(gridRows, outputRows) * SLOT;
        int rowCount = 2 + (create ? 2 : 0); // recipe + tag, plus Create's amount and fluid rows
        int stacked = recipeHeight + ROW + (create ? 2 * ROW : 0);
        int band = BAND_BOTTOM - BAND_TOP;
        int gap = clamp((band - stacked) / (rowCount - 1), MIN_ROW_GAP, MAX_ROW_GAP);
        int blockHeight = stacked + gap * (rowCount - 1);
        int cursor = BAND_TOP + Math.max(0, (band - blockHeight) / 2);

        int recipeY = cursor;
        cursor += recipeHeight + gap;
        if (create) {
            extraRowY = cursor;
            cursor += ROW + gap;
            fluidRowY = cursor;
            cursor += ROW + gap;
        } else {
            extraRowY = -1;
            fluidRowY = -1;
        }
        tagRowY = cursor;

        // A short grid and a single result slot both centre on the recipe row rather than hanging off its
        // top, which is what keeps the result beside the middle of a 5x5 or 3x3 grid.
        gridY = recipeY + (recipeHeight - gridRows * SLOT) / 2;
        outputY = recipeY + (recipeHeight - outputRows * SLOT) / 2;

        // ---- horizontal: centre grid → arrow → result in the width left over
        int reserved = cooking ? COOKING_RESERVE : 0;
        int bandWidth = WIDTH - 2 * PADDING - reserved;
        int clusterWidth = gridColumns * SLOT + CLUSTER_GAP + ARROW_WIDTH + CLUSTER_GAP + outputColumns * SLOT;
        gridX = PADDING + Math.max(0, (bandWidth - clusterWidth) / 2);
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
