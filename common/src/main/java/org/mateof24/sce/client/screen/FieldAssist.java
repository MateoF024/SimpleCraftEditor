package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

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
 * native one reads as a rendering fault rather than a warning.
 *
 * <p>The completion list deliberately mirrors the one the chat box shows for commands: same panel colour,
 * same line height, the selected row in yellow, arrow keys to move through it, and the remainder of the
 * selection written into the field as grey ghost text. The colours and metrics are the ones
 * {@code CommandSuggestions} itself uses, so the two read as the same control rather than as a lookalike.
 * Matching follows the same rule as a command argument too: text with no namespace matches against the
 * path, which is what lets {@code stone} find {@code minecraft:stone}.
 */
@Environment(EnvType.CLIENT)
public final class FieldAssist {
    /** Where a field's completions come from, if it has any. */
    public enum Source {
        NONE,
        ITEMS,
        ITEM_TAGS,
        /** Fluids, or fluid tags once the text starts with {@code #}. */
        FLUIDS,
        /** Every recipe the client knows about, including the ones this mod has injected. */
        RECIPES
    }

    // Taken from CommandSuggestions so the list is indistinguishable from the one chat draws.
    private static final int MAX_SHOWN = 10;
    private static final int LINE_HEIGHT = 12;
    private static final int PANEL_COLOR = 0xD0000000;
    private static final int TEXT_COLOR = 0xFFAAAAAA;
    private static final int SELECTED_COLOR = 0xFFFFFF00;

    private static final int INVALID_TEXT = 0xFF5555;
    private static final int VALID_TEXT = 0xE0E0E0;

    private record Field(EditBox box, Predicate<String> valid, Source source) {
    }

    private final List<Field> fields = new ArrayList<>();
    private final List<String> matches = new ArrayList<>();
    private EditBox target;
    private int selected;
    /** First row drawn, so a long list scrolls with the selection instead of being cut off. */
    private int offset;

    /** Forgets every field; call when a screen rebuilds its widgets. */
    public void clear() {
        fields.clear();
        close();
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
        EditBox focused = null;
        Source source = Source.NONE;
        for (Field field : fields) {
            String text = field.box().getValue();
            boolean bad = !text.isBlank() && !field.valid().test(text);
            field.box().setTextColor(bad ? INVALID_TEXT : VALID_TEXT);
            if (focused == null && field.box().isFocused() && field.source() != Source.NONE) {
                focused = field.box();
                source = field.source();
            }
        }
        if (focused != target) {
            close();
            target = focused;
        }
        if (target == null) {
            return;
        }
        String typed = target.getValue();
        String previous = selectedText();
        collect(source, typed);
        // Keep the highlight on the same entry while more of its name is typed, rather than snapping
        // back to the top on every keystroke.
        selected = Math.max(0, matches.indexOf(previous));
        clampOffset();
        updateGhost(typed);
    }

    private void collect(Source source, String text) {
        matches.clear();
        String typed = text.trim().toLowerCase();
        boolean tagged = typed.startsWith("#");
        String needle = tagged ? typed.substring(1) : typed;
        if (needle.isEmpty()) {
            return;
        }
        List<String> all = candidates(source, tagged);
        all.sort(String::compareTo);
        for (String candidate : all) {
            if (candidate.equals(typed)) {
                matches.clear();
                return; // already an exact match; nothing useful left to offer
            }
            if (startsWithLoosely(candidate, needle, tagged)) {
                matches.add(candidate);
                if (matches.size() >= MAX_SHOWN * 4) {
                    break; // enough to scroll through; the rest would never be reached
                }
            }
        }
    }

    /**
     * Whether a candidate answers to what has been typed, the way a command argument does: with no
     * namespace given, the path alone is enough to match.
     */
    private static boolean startsWithLoosely(String candidate, String needle, boolean tagged) {
        String bare = tagged && candidate.startsWith("#") ? candidate.substring(1) : candidate;
        if (bare.startsWith(needle)) {
            return true;
        }
        int colon = bare.indexOf(':');
        return !needle.contains(":") && colon >= 0 && bare.substring(colon + 1).startsWith(needle);
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
            case RECIPES -> {
                // The client's own recipe manager, so this covers the datapack, every mod, and the
                // recipes this editor has authored — whatever the server has actually synced.
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.level != null) {
                    minecraft.level.getRecipeManager().getRecipes()
                            .forEach(recipe -> out.add(recipe.getId().toString()));
                }
            }
            default -> {
            }
        }
        return out;
    }

    /** Writes the rest of the highlighted entry into the field as grey ghost text, as commands do. */
    private void updateGhost(String typed) {
        if (target == null) {
            return;
        }
        String selection = selectedText();
        if (selection != null && selection.length() > typed.length()
                && selection.regionMatches(true, 0, typed, 0, typed.length())) {
            target.setSuggestion(selection.substring(typed.length()));
        } else {
            target.setSuggestion(null);
        }
    }

    private String selectedText() {
        return selected >= 0 && selected < matches.size() ? matches.get(selected) : null;
    }

    private void clampOffset() {
        if (selected < offset) {
            offset = selected;
        } else if (selected >= offset + MAX_SHOWN) {
            offset = selected - MAX_SHOWN + 1;
        }
        offset = Math.max(0, Math.min(offset, Math.max(0, matches.size() - MAX_SHOWN)));
    }

    /** Hides the list and clears any ghost text it had put in the field. */
    public void close() {
        if (target != null) {
            target.setSuggestion(null);
        }
        matches.clear();
        selected = 0;
        offset = 0;
    }

    public boolean isEmpty() {
        return target == null || matches.isEmpty();
    }

    // ------------------------------------------------------------------ input

    /** Moves the highlight, wrapping at both ends the way the command list does. */
    private void cycle(int by) {
        selected = Math.floorMod(selected + by, matches.size());
        clampOffset();
        updateGhost(target.getValue());
    }

    private void accept() {
        String selection = selectedText();
        if (selection != null) {
            target.setValue(selection);
            target.moveCursorToEnd();
        }
        close();
    }

    /**
     * Handles the keys the list owns while it is open: the arrows move through it, tab and enter take the
     * highlighted entry, and escape dismisses it. Returns false for everything else so the field and the
     * screen keep their own behaviour.
     */
    public boolean keyPressed(int keyCode) {
        if (isEmpty()) {
            return false;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> {
                cycle(-1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                cycle(1);
                return true;
            }
            case GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                accept();
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                close();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Scrolling over an open list moves through it rather than through whatever is behind it. */
    public boolean mouseScrolled(double amount) {
        if (isEmpty() || amount == 0.0) {
            return false;
        }
        cycle(amount > 0.0 ? -1 : 1);
        return true;
    }

    /** Takes whichever row was clicked. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (isEmpty()) {
            return false;
        }
        int row = (int) ((mouseY - popupTop()) / LINE_HEIGHT);
        int index = offset + row;
        if (row < 0 || row >= shownCount() || index >= matches.size()
                || mouseX < target.getX() - 1 || mouseX > target.getX() - 1 + width()) {
            return false;
        }
        selected = index;
        accept();
        return true;
    }

    // ------------------------------------------------------------------ drawing

    /** Draws the completion list over everything else, highlighting the row the pointer is over. */
    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (isEmpty()) {
            return;
        }
        int x = target.getX() - 1;
        int top = popupTop();
        int w = width();
        int rows = shownCount();

        int hovered = (int) ((mouseY - top) / LINE_HEIGHT);
        if (hovered >= 0 && hovered < rows && mouseX >= x && mouseX <= x + w) {
            selected = offset + hovered;
            updateGhost(target.getValue());
        }

        // Text queued in the same batch does not respect draw order, so lift the popup above the fields
        // it overlaps rather than relying on being drawn last.
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 400.0F);
        graphics.fill(x, top, x + w, top + rows * LINE_HEIGHT, PANEL_COLOR);
        for (int i = 0; i < rows; i++) {
            int index = offset + i;
            graphics.drawString(font, matches.get(index), x + 1, top + 2 + i * LINE_HEIGHT,
                    index == selected ? SELECTED_COLOR : TEXT_COLOR, false);
        }
        graphics.pose().popPose();
    }

    private int shownCount() {
        return Math.min(MAX_SHOWN, matches.size() - offset);
    }

    private int popupTop() {
        return target.getY() + target.getHeight();
    }

    private int width() {
        int longest = 0;
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < shownCount(); i++) {
            longest = Math.max(longest, font.width(matches.get(offset + i)));
        }
        return longest + 2;
    }
}
