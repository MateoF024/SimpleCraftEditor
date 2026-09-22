package org.mateof24.sce.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.mateof24.sce.client.ClientRecipeIds;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
        /** Items, or item tags once the text starts with {@code #}: the pair the value row offers. */
        ITEMS_OR_TAGS,
        /** Fluids, or fluid tags once the text starts with {@code #}. */
        FLUIDS,
        /** Every recipe on the server, including the ones this mod has authored. */
        RECIPES
    }

    // Taken from CommandSuggestions so the list is indistinguishable from the one chat draws.
    private static final int MAX_SHOWN = 10;
    private static final int LINE_HEIGHT = 12;
    private static final int PANEL_COLOR = 0xD0000000;
    private static final int TEXT_COLOR = 0xFFAAAAAA;
    private static final int SELECTED_COLOR = 0xFFFFFF00;

    // Written in full ARGB, alpha included: from 1.21.11 a colour with no alpha draws nothing at all, and
    // these replace the field's own opaque default on every frame.
    /** The grey the game itself uses for text that is there but not yours. */
    private static final int PLACEHOLDER_TEXT = 0x707070;

    /**
     * A field's placeholder, in that grey.
     *
     * <p>White reads as something somebody typed. The whole point of a placeholder is that nobody did,
     * and on a black field the only thing that can say so is the colour.
     */
    public static net.minecraft.network.chat.Component hint(String key) {
        return net.minecraft.network.chat.Component.translatable(key)
                .withStyle(net.minecraft.network.chat.Style.EMPTY
                        .withColor(net.minecraft.network.chat.TextColor.fromRgb(PLACEHOLDER_TEXT)));
    }

    private static final int INVALID_TEXT = 0xFFFF5555;
    private static final int VALID_TEXT = 0xFFE0E0E0;

    /**
     * One field being watched. The source is a supplier rather than a value because the editor's value
     * row changes what it is naming as the author picks a different slot: a fluid slot completes against
     * fluids and every other slot against items, and the row itself is built once.
     */
    private record Field(EditBox box, Predicate<String> valid, Supplier<Source> source) {
    }

    private final List<Field> fields = new ArrayList<>();
    private final List<String> matches = new ArrayList<>();
    private EditBox target;
    private int selected;
    /**
     * Where the completion list may be drawn. A field narrow enough to sit near the right edge of a
     * panel would otherwise push its list off the side of that panel, which reads as a rendering fault
     * rather than as a list. Untouched means unbounded, which is right for a full-screen editor.
     */
    private int leftLimit = Integer.MIN_VALUE;
    private int rightLimit = Integer.MAX_VALUE;
    /** First row drawn, so a long list scrolls with the selection instead of being cut off. */
    private int offset;

    /**
     * Left edge of the completion list: under the field it belongs to, pulled back when that would push
     * it outside the bounds the screen set. Asked by everything that needs it, so the list is drawn
     * where the pointer is tested against it.
     */
    private int popupLeft() {
        return Math.max(leftLimit, Math.min(target.getX() - 1, rightLimit - width()));
    }

    /** Keeps the completion list inside these bounds. Set it after {@link #clear()}, which forgets them. */
    public void limits(int left, int right) {
        leftLimit = left;
        rightLimit = right;
    }

    /** Forgets every field; call when a screen rebuilds its widgets. */
    public void clear() {
        fields.clear();
        close();
    }

    public void add(EditBox box, Predicate<String> valid, Supplier<Source> source) {
        if (box == null) {
            return;
        }
        fields.add(new Field(box, valid, source));
        if (source.get() == Source.RECIPES) {
            // The only list that is not on this side already. Asked for as the screen is built rather
            // than as the field is typed in, so it is here before there is anything to complete.
            ClientRecipeIds.request();
        }
    }

    public void add(EditBox box, Predicate<String> valid, Source source) {
        add(box, valid, () -> source);
    }

    public void add(EditBox box, Predicate<String> valid) {
        add(box, valid, Source.NONE);
    }

    // ------------------------------------------------------------------ rules

    /** An id such as {@code minecraft:stone}; a bare path is valid too, as vanilla assumes the namespace. */
    public static Predicate<String> id() {
        return text -> Identifier.tryParse(text) != null;
    }

    /** An id, optionally written as a tag with a leading {@code #}. */
    public static Predicate<String> idOrTag() {
        return text -> Identifier.tryParse(text.startsWith("#") ? text.substring(1) : text) != null;
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
     * Recolours every field, recomputes the completions for whichever one has focus, and takes the
     * pointer's row as the highlight.
     *
     * <p>Call this <em>before</em> the widgets are drawn. Everything it decides — the text colour and the
     * ghost text — is read by {@link EditBox} as it draws itself, so deciding afterwards leaves the field
     * showing the previous frame's answer: while typing, the ghost text is one character too long for a
     * frame and the whole line appears to jump right and snap back.
     */
    public void update(int mouseX, int mouseY) {
        EditBox focused = null;
        Source source = Source.NONE;
        for (Field field : fields) {
            String text = field.box().getValue();
            boolean bad = !text.isBlank() && !field.valid().test(text);
            field.box().setTextColor(bad ? INVALID_TEXT : VALID_TEXT);
            if (focused == null && field.box().isFocused()) {
                Source asked = field.source().get();
                if (asked != Source.NONE) {
                    focused = field.box();
                    source = asked;
                }
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
        hover(mouseX, mouseY);
        updateGhost(target.getValue());
    }

    /** Moves the highlight to the row the pointer is over, if it is over one. */
    private void hover(int mouseX, int mouseY) {
        if (matches.isEmpty()) {
            return;
        }
        int row = (int) ((mouseY - popupTop()) / LINE_HEIGHT);
        int x = popupLeft();
        if (row >= 0 && row < shownCount() && mouseX >= x && mouseX <= x + width()) {
            selected = offset + row;
            clampOffset();
        }
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
            case ITEMS_OR_TAGS -> {
                if (tagged) {
                    BuiltInRegistries.ITEM.getTags()
                            .forEach(tag -> out.add("#" + tag.key().location()));
                } else {
                    BuiltInRegistries.ITEM.keySet().forEach(id -> out.add(id.toString()));
                }
            }
            case ITEM_TAGS -> BuiltInRegistries.ITEM.getTags()
                    .forEach(tag -> out.add(tag.key().location().toString()));
            case FLUIDS -> {
                if (tagged) {
                    BuiltInRegistries.FLUID.getTags()
                            .forEach(tag -> out.add("#" + tag.key().location()));
                } else {
                    BuiltInRegistries.FLUID.keySet().forEach(id -> out.add(id.toString()));
                }
            }
            case RECIPES -> {
                // From 1.21.11 the client is not sent the recipes at all, so this list is not read out of
                // a client-side recipe manager as it is on the older versions; it is the answer the
                // server gave when the screen opened. Everything below this line works the same either
                // way — the field completes the datapack, every mod, and the recipes this editor wrote.
                out.addAll(ClientRecipeIds.ids());
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
            target.moveCursorToEnd(false);
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
                || mouseX < popupLeft() || mouseX > popupLeft() + width()) {
            return false;
        }
        selected = index;
        accept();
        return true;
    }

    // ------------------------------------------------------------------ drawing

    /** Draws the completion list over everything else. */
    public void render(GuiGraphics graphics, Font font) {
        if (isEmpty()) {
            return;
        }
        int x = popupLeft();
        int top = popupTop();
        int w = width();
        int rows = shownCount();

        // The pose is a plain 2D matrix from 1.21.11, with no depth to lift anything by. Drawing last
        // is what puts the popup on top now, which is where this already sits in the frame.
        graphics.pose().pushMatrix();
        graphics.fill(x, top, x + w, top + rows * LINE_HEIGHT, PANEL_COLOR);
        for (int i = 0; i < rows; i++) {
            int index = offset + i;
            graphics.drawString(font, matches.get(index), x + 1, top + 2 + i * LINE_HEIGHT,
                    index == selected ? SELECTED_COLOR : TEXT_COLOR, false);
        }
        graphics.pose().popMatrix();
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
