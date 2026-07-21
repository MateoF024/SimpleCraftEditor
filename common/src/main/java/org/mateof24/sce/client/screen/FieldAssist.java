package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Marks a text field when what is typed in it cannot be used, and completes the ones that name something
 * from a registry.
 *
 * <p>The two belong together: a field either takes an id, a tag or a number, and that same answer decides
 * both what counts as valid and what can be offered as a suggestion. Registering a field once with the
 * rule it follows keeps the two from disagreeing.
 *
 * <p>Invalid text turns red rather than being rejected as it is typed. Half of an id is invalid on the way
 * to a valid one, so refusing keystrokes would make the field unusable; the colour says the value is not
 * usable *yet*. An empty field is never marked — nothing has been typed wrong.
 *
 * <p>Only the text changes colour. Recolouring the field's border would say it more plainly, but
 * {@link EditBox} draws that border itself and exposes no way to tint it, and the two versions do not draw
 * it alike — 1.21.1 blits a sprite where 1.20.1 fills a rectangle. Adding a second border around the
 * native one reads as a rendering fault rather than a warning, so the only faithful alternative is to turn
 * the border off and reproduce the whole widget's chrome, which risks not matching vanilla for a purely
 * cosmetic gain.
 *
 * <p>Candidates come from the live registries, so whatever the instance has loaded is offered and nothing
 * has to be listed ahead of time.
 */
@Environment(EnvType.CLIENT)
public final class FieldAssist {
    /** Where a field's completions come from, if it has any. */
    public enum Source {
        NONE,
        ITEMS,
        ITEM_TAGS,
        /** Fluids, or fluid tags once the text starts with {@code #}. */
        FLUIDS
    }

    private static final int MAX_SHOWN = 7;
    private static final int ROW_HEIGHT = 11;
    private static final int INVALID_TEXT = 0xFF5555;
    private static final int VALID_TEXT = 0xE0E0E0;

    private record Field(EditBox box, Predicate<String> valid, Source source) {
    }

    private final List<Field> fields = new ArrayList<>();
    private final List<String> matches = new ArrayList<>();
    private EditBox target;

    /** Forgets every field; call when a screen rebuilds its widgets. */
    public void clear() {
        fields.clear();
        matches.clear();
        target = null;
    }

    public void add(EditBox box, Predicate<String> valid, Source source) {
        if (box != null) {
            fields.add(new Field(box, valid, source));
        }
    }

    public void add(EditBox box, Predicate<String> valid) {
        add(box, valid, Source.NONE);
    }

    // ------------------------------------------------------------------ rules

    /** An id such as {@code minecraft:stone}; a bare path is valid too, as vanilla assumes the namespace. */
    public static Predicate<String> id() {
        return text -> ResourceLocation.tryParse(text) != null;
    }

    /** An id, optionally written as a tag with a leading {@code #}. */
    public static Predicate<String> idOrTag() {
        return text -> ResourceLocation.tryParse(text.startsWith("#") ? text.substring(1) : text) != null;
    }

    public static Predicate<String> intAtLeast(int min) {
        return text -> {
            try {
                return Integer.parseInt(text.trim()) >= min;
            } catch (NumberFormatException e) {
                return false;
            }
        };
    }

    public static Predicate<String> decimalBetween(float min, float max) {
        return text -> {
            try {
                float value = Float.parseFloat(text.trim());
                return value >= min && value <= max;
            } catch (NumberFormatException e) {
                return false;
            }
        };
    }

    // ------------------------------------------------------------------ per-frame

    /**
     * Recolours every field and recomputes the completions for whichever one has focus. Call once a frame
     * before {@link #render}.
     */
    public void update() {
        target = null;
        for (Field field : fields) {
            String text = field.box().getValue();
            boolean bad = !text.isBlank() && !field.valid().test(text);
            field.box().setTextColor(bad ? INVALID_TEXT : VALID_TEXT);
            if (field.box().isFocused() && target == null && field.source() != Source.NONE) {
                target = field.box();
                collect(field.source(), text);
            }
        }
        if (target == null) {
            matches.clear();
        }
    }

    private void collect(Source source, String text) {
        matches.clear();
        String typed = text.trim().toLowerCase();
        if (typed.isEmpty()) {
            return;
        }
        boolean tagged = typed.startsWith("#");
        String needle = tagged ? typed.substring(1) : typed;
        if (needle.isEmpty()) {
            return;
        }
        for (String candidate : candidates(source, tagged)) {
            if (candidate.equals(typed)) {
                matches.clear();
                return; // already an exact match; nothing useful left to offer
            }
            if (candidate.contains(needle)) {
                matches.add(candidate);
                if (matches.size() >= MAX_SHOWN) {
                    return;
                }
            }
        }
    }

    private static List<String> candidates(Source source, boolean tagged) {
        List<String> out = new ArrayList<>();
        switch (source) {
            case ITEMS -> BuiltInRegistries.ITEM.keySet().forEach(id -> out.add(id.toString()));
            case ITEM_TAGS -> BuiltInRegistries.ITEM.getTagNames()
                    .forEach(tag -> out.add(tag.location().toString()));
            case FLUIDS -> {
                if (tagged) {
                    BuiltInRegistries.FLUID.getTagNames().forEach(tag -> out.add("#" + tag.location()));
                } else {
                    BuiltInRegistries.FLUID.keySet().forEach(id -> out.add(id.toString()));
                }
            }
            default -> {
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return target == null || matches.isEmpty();
    }

    /** Fills the focused field with the first match; used for the tab key. */
    public boolean acceptFirst() {
        if (isEmpty()) {
            return false;
        }
        target.setValue(matches.get(0));
        matches.clear();
        return true;
    }

    /** Fills the focused field with whichever row was clicked. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (isEmpty()) {
            return false;
        }
        int top = popupTop();
        int index = (int) ((mouseY - top) / ROW_HEIGHT);
        if (index < 0 || index >= matches.size() || mouseX < target.getX() || mouseX > target.getX() + width()) {
            return false;
        }
        target.setValue(matches.get(index));
        matches.clear();
        return true;
    }

    /** Draws the completion list over everything else. */
    public void render(GuiGraphics graphics, Font font) {
        if (isEmpty()) {
            return;
        }
        int x = target.getX();
        int top = popupTop();
        int w = width();
        // Text queued in the same batch does not respect draw order, so lift the popup above the fields
        // it overlaps rather than relying on being drawn last.
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 400.0F);
        graphics.fill(x, top, x + w, top + matches.size() * ROW_HEIGHT, 0xF0100010);
        for (int i = 0; i < matches.size(); i++) {
            graphics.drawString(font, matches.get(i), x + 2, top + i * ROW_HEIGHT + 2, 0xE0E0E0, false);
        }
        graphics.pose().popPose();
    }

    private int popupTop() {
        return target.getY() + target.getHeight();
    }

    private int width() {
        return Math.max(target.getWidth(), 150);
    }
}
