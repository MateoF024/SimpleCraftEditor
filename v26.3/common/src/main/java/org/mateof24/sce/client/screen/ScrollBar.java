package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The scroll bar beside a list that is longer than the room it has: the game's own, in look and in
 * behaviour.
 *
 * <p>Both lists in this mod scrolled with the wheel and said nothing about it, so a list of forty
 * recipes looked exactly like a list of eleven. A drawn bar fixed the looking; this one can also be
 * grabbed, because a bar that only reports is a bar every player tries to drag once and then distrusts.
 *
 * <p>Everything here is taken from {@code AbstractSelectionList}: the two sprites the game keeps in its
 * widget atlas, the six-pixel width, the thumb height
 * {@code clamp(visible * visible / content, 32, visible - 8)}, the thumb travel, and the drag rate
 * {@code max(1, maxScroll / (visible - thumb))} with the two clamps for a pointer dragged off either
 * end. Reimplemented rather than inherited because neither list is an {@code AbstractSelectionList} -
 * both are rows painted in a screen's own render pass - and a copy that invents its own numbers is the
 * kind of thing a player notices without being able to say why.
 *
 * <p>Scrolling is by whole rows. The rows are widgets at fixed positions, so a half-scrolled list is
 * not a thing this can show; the thumb steps with the content, which is the truth about where the list
 * is.
 */
@Environment(EnvType.CLIENT)
public final class ScrollBar {
    /** Vanilla's {@code SCROLLBAR_WIDTH}. */
    public static final int WIDTH = 6;
    /** Room between the rows and the bar. */
    public static final int GAP = 4;
    /** Vanilla's floor for the thumb, so it stays grabbable however long the list is. */
    private static final int MIN_THUMB = 32;

    private static final Identifier SCROLLER =
            Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND =
            Identifier.withDefaultNamespace("widget/scroller_background");

    private int x;
    private int top;
    private int rowHeight = 1;
    private int visibleRows = 1;
    private int totalRows;
    private int scroll;
    private boolean dragging;

    /**
     * Where the bar is and how much list there is. Called before anything is drawn or hit-tested, from
     * both the screen's layout and its render, because between those two the window can be resized.
     */
    public void place(int x, int top, int rowHeight, int visibleRows, int totalRows) {
        this.x = x;
        this.top = top;
        this.rowHeight = Math.max(1, rowHeight);
        this.visibleRows = Math.max(1, visibleRows);
        this.totalRows = Math.max(0, totalRows);
        this.scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    public int scroll() {
        return scroll;
    }

    /** Sets the first visible row, clamped. True when it actually moved, which is when to rebuild. */
    public boolean setScroll(int value) {
        int clamped = Mth.clamp(value, 0, maxScroll());
        if (clamped == scroll) {
            return false;
        }
        scroll = clamped;
        return true;
    }

    /** How many rows are hidden below the last visible one. */
    public int maxScroll() {
        return Math.max(0, totalRows - visibleRows);
    }

    /** Whether there is anything to scroll. Nothing is drawn and nothing is grabbable when there is not. */
    public boolean visible() {
        return maxScroll() > 0;
    }

    private int height() {
        return visibleRows * rowHeight;
    }

    /** Vanilla's thumb height: how much of the list is on screen, floored so it stays grabbable. */
    private int thumbHeight() {
        int content = Math.max(1, totalRows * rowHeight);
        return Mth.clamp(height() * height() / content, MIN_THUMB, Math.max(MIN_THUMB, height() - 8));
    }

    private int thumbTop() {
        int travel = height() - thumbHeight();
        return maxScroll() <= 0 ? top : top + travel * scroll / maxScroll();
    }

    // ------------------------------------------------------------------ the mouse

    public boolean isOver(double mouseX, double mouseY) {
        return visible() && mouseX >= x && mouseX < x + WIDTH && mouseY >= top && mouseY < top + height();
    }

    /**
     * Takes the click if it landed on the bar, and jumps the list to it.
     *
     * <p>Vanilla only starts a drag here, because its thumb follows the pointer from wherever it was
     * grabbed. A row list cannot do that - it can only be at whole rows - so a click also moves it to
     * where you clicked, which is what a player pressing a point on a bar means.
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isOver(mouseX, mouseY)) {
            return false;
        }
        dragging = true;
        jumpTo(mouseY);
        return true;
    }

    /** Follows the pointer while the bar is held. True when the list moved. */
    public boolean mouseDragged(double mouseY) {
        return dragging && jumpTo(mouseY);
    }

    public void mouseReleased() {
        dragging = false;
    }

    public boolean dragging() {
        return dragging;
    }

    /**
     * The row the pointer is over, with the thumb's own length taken out of the sum.
     *
     * <p>Grabbing the middle of the thumb and dragging to the bottom has to reach the end of the list,
     * so the travel is the track minus the thumb, and the pointer is measured from half a thumb in -
     * the same relationship vanilla's rate produces, written directly because this moves in rows.
     */
    private boolean jumpTo(double mouseY) {
        int travel = height() - thumbHeight();
        if (travel <= 0 || maxScroll() <= 0) {
            return false;
        }
        double offset = mouseY - top - thumbHeight() / 2.0;
        double fraction = Mth.clamp(offset / travel, 0.0, 1.0);
        return setScroll((int) Math.round(fraction * maxScroll()));
    }

    // ------------------------------------------------------------------ drawing

    /**
     * The two sprites the game draws its own scroll bars with, at the size it draws them.
     *
     * <p>They come out of the widget atlas rather than a texture of ours, so the bar is the resource
     * pack's bar: a player who has replaced the game's scroll bars sees theirs here too.
     */
    public void draw(GuiGraphicsExtractor graphics) {
        if (!visible()) {
            return;
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND, x, top, WIDTH, height());
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, x, thumbTop(), WIDTH, thumbHeight());
    }

    /**
     * The count, right-aligned above the list: which rows are on screen and how many there are.
     *
     * <p>Right-aligned because the space over the left of a list is where its captions go, and a count
     * centred over them lands on top of one. Nothing is drawn when everything fits, because then it
     * would only be saying how many rows are in plain sight.
     */
    public void drawCount(GuiGraphicsExtractor graphics, Font font, int right, int y) {
        if (!visible()) {
            return;
        }
        Component text = Component.translatable("sce.list.showing",
                scroll + 1, Math.min(scroll + visibleRows, totalRows), totalRows);
        graphics.text(font, text, right - font.width(text), y, 0xFFA0A0A0, false);
    }
}
