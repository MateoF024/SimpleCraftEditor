package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The one-line message an editor screen shows about what just happened: it fades in, stays long enough
 * to be read, and fades out on its own.
 *
 * <p>Every screen used to keep its own {@code Component status} that was set and then never cleared, so
 * the last thing that happened sat on screen until something else did. Fading it out is the difference
 * between a message and a permanent label — and it removes the question of when to clear it, because the
 * message clears itself.
 *
 * <p><b>The alpha floor is not arbitrary.</b> {@code Font} takes a colour whose top six alpha bits are
 * all zero — anything under 4 — as "no alpha given" and draws the text fully opaque. A ramp that walked
 * all the way to zero would therefore end in a flash of solid text. So the ramp stops at 4 and the line
 * simply stops being drawn once its time is up.
 */
@Environment(EnvType.CLIENT)
public final class StatusLine {
    private static final long FADE_IN = 200L;
    private static final long HOLD = 3000L;
    private static final long FADE_OUT = 600L;
    private static final long LIFETIME = FADE_IN + HOLD + FADE_OUT;
    /** The ramp stops here rather than at zero: a colour with no alpha is not drawn at all. */
    private static final int MIN_ALPHA = 4;

    /** The colour these lines have always been drawn in. */
    public static final int DEFAULT_COLOUR = 0xE0E070;

    private Component text = Component.empty();
    private long shownAt;

    /** Shows a message, restarting the ramp even if the same one is already up. */
    public void set(Component message) {
        text = message;
        shownAt = Util.getMillis();
    }

    public void clear() {
        text = Component.empty();
    }

    /** Draws the line centred on {@code centreX}, if it still has time left. */
    public void drawCentered(GuiGraphicsExtractor graphics, Font font, int centreX, int y) {
        drawCentered(graphics, font, centreX, y, DEFAULT_COLOUR);
    }

    public void drawCentered(GuiGraphicsExtractor graphics, Font font, int centreX, int y, int rgb) {
        int colour = colourNow(rgb);
        if (colour != 0) {
            graphics.centeredText(font, text, centreX, y, colour);
        }
    }

    /** The colour to draw in right now, or 0 when there is nothing left to draw. */
    private int colourNow(int rgb) {
        if (text.getString().isEmpty()) {
            return 0;
        }
        long age = Util.getMillis() - shownAt;
        if (age >= LIFETIME) {
            text = Component.empty();
            return 0;
        }
        int alpha = 255;
        if (age < FADE_IN) {
            alpha = (int) (255L * age / FADE_IN);
        } else if (age > FADE_IN + HOLD) {
            alpha = (int) (255L * (LIFETIME - age) / FADE_OUT);
        }
        return (Mth.clamp(alpha, MIN_ALPHA, 255) << 24) | (rgb & 0xFFFFFF);
    }
}
